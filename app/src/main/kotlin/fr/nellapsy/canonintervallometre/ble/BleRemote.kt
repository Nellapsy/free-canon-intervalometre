package fr.nellapsy.canonintervallometre.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.location.LocationManagerCompat
import fr.nellapsy.canonintervallometre.interval.Declencheur
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Échec d'une étape de la liaison. [codeGatt] est renseigné quand il vient de la pile BLE. */
class EchecLiaison(message: String, val codeGatt: Int? = null) : Exception(message)

/**
 * Liaison BLE avec le boîtier : scan, connexion, appairage, identification, reprise.
 *
 * Deux principes en gouvernent l'écriture :
 *
 * - **Une seule opération GATT en vol.** Android n'en accepte pas davantage. [verrouGatt]
 *   sérialise les écritures ; c'est la file d'attente, posée dès ce jalon parce que la
 *   rajouter après coup imposerait de reprendre tout ce qui l'utilise.
 * - **Rien de bloquant dans un rappel GATT.** Les rappels arrivent sur un thread système.
 *   Ils ne font que pousser un événement dans [evenements] ou reprendre une continuation ;
 *   toute la logique vit dans [boucleDeLiaison].
 *
 * Les appels BLE portent `@SuppressLint("MissingPermission")` : les permissions sont
 * vérifiées à l'exécution par [verifierPrerequis], que la boucle exécute avant toute
 * opération. Le lint ne sait pas remonter jusque-là.
 */
class BleRemote(
    contexte: Context,
    private val portee: CoroutineScope,
) : Declencheur {

    private val contexte = contexte.applicationContext
    private val adresseBoitier = AdresseBoitier(this.contexte)

    private val adaptateur: BluetoothAdapter? =
        this.contexte.getSystemService(BluetoothManager::class.java)?.adapter

    private val etatInterne = MutableStateFlow<EtatLiaison>(EtatLiaison.Inactif)
    val etat: StateFlow<EtatLiaison> = etatInterne.asStateFlow()

    /**
     * Point de passage unique des changements d'état, pour que la trace `adb logcat -s
     * BleRemote` reconstitue le cycle de liaison. Sans elle, un blocage ne se distingue
     * pas d'une boucle de reprise rapide : l'écran affiche la même chose.
     */
    private fun publier(nouvel: EtatLiaison) {
        Log.i(TAG, "état : ${etatInterne.value} → $nouvel")
        etatInterne.value = nouvel
    }

    private val etatDeclencheurInterne =
        MutableStateFlow<EtatDeclencheur>(EtatDeclencheur.Repos)

    /** Issue du dernier déclenchement demandé. Voir [EtatDeclencheur]. */
    val etatDeclencheur: StateFlow<EtatDeclencheur> = etatDeclencheurInterne.asStateFlow()

    private fun publierDeclencheur(nouvel: EtatDeclencheur) {
        Log.i(TAG, "déclencheur : ${etatDeclencheurInterne.value} → $nouvel")
        etatDeclencheurInterne.value = nouvel
    }

    private var travail: Job? = null
    private var gatt: BluetoothGatt? = null

    /** Ce qu'il faut pour envoyer une commande : le lien GATT et la caractéristique de contrôle. */
    private data class LienActif(
        val gatt: BluetoothGatt,
        val controle: BluetoothGattCharacteristic,
    )

    /**
     * Lien utilisable, ou `null` tant qu'il n'y en a pas. `AtomicReference` parce que
     * [declencher] est appelé depuis le thread de l'interface pendant que
     * [boucleDeLiaison] tourne sur sa propre portée.
     */
    private val lienActif = AtomicReference<LienActif?>(null)

    /** Vues prises depuis le lancement. */
    private val vuesPrises = AtomicInteger(0)

    private val pretInterne = MutableStateFlow(true)

    /**
     * Faux pendant une vue et pendant le délai de garde qui la suit. L'interface s'en sert
     * pour éteindre son bouton ; [declencher] s'en sert pour refuser. Voir [DELAI_GARDE_MS].
     */
    val pretADeclencher: StateFlow<Boolean> = pretInterne.asStateFlow()

    /** Sérialise les opérations GATT. Le `Mutex` de kotlinx.coroutines sert les demandes en FIFO. */
    private val verrouGatt = Mutex()

    /**
     * Vrai tant qu'une séquence tourne. Posé par `ShutterService`, lu par [boucleDeLiaison].
     *
     * Il ne change qu'une chose, mais elle est décisive : la reprise de liaison perd son
     * plafond (NF3). Hors séquence le plafond tient, et c'est voulu — une application
     * ouverte devant un boîtier éteint doit finir par afficher un échec plutôt que de
     * scanner la nuit entière. `AtomicBoolean` parce que l'écriture vient du service et la
     * lecture de la boucle, sur deux threads.
     */
    private val sequenceActive = AtomicBoolean(false)

    /**
     * Signale au cycle de liaison qu'une séquence commence ou finit. Voir [sequenceActive].
     */
    fun signalerSequence(active: Boolean) {
        sequenceActive.set(active)
        Log.i(TAG, "liaison : séquence ${if (active) "démarrée" else "terminée"}")
    }

    /**
     * Écriture en attente d'acquittement. `AtomicReference` et non `@Volatile` : le rappel
     * d'écriture et celui de déconnexion peuvent courir en même temps, et la reprise ne
     * doit avoir lieu qu'une fois.
     */
    private val ecritureEnAttente = AtomicReference<CancellableContinuation<Int>?>(null)

    /** Événements GATT republiés du thread système vers la boucle. */
    private val evenements = Channel<EvenementGatt>(Channel.UNLIMITED)

    private sealed interface EvenementGatt {
        data object Connecte : EvenementGatt
        data class Deconnecte(val codeGatt: Int) : EvenementGatt
        data class ServicesDecouverts(val codeGatt: Int) : EvenementGatt
    }

    // ----------------------------------------------------------------- API publique

    /** Démarre ou redémarre le cycle de liaison. Sans effet si un cycle est déjà en cours. */
    fun connecter() {
        if (travail?.isActive == true) return
        travail = portee.launch { boucleDeLiaison() }
    }

    /**
     * Oublie l'adresse mémorisée et relance un cycle, qui repassera donc par un scan.
     *
     * C'est la seule porte vers le scan une fois un boîtier connu, et elle est volontairement
     * explicite : le repli automatique du jalon 1 a été retiré (voir [appareilCible]). Elle
     * sert au boîtier remplacé ou réinitialisé, cas où l'adresse mémorisée ne désigne plus
     * rien et où aucune reprise ne peut aboutir.
     */
    fun oublierBoitier() {
        travail?.cancel()
        travail = null
        fermerGatt()
        publier(EtatLiaison.Inactif)
        travail = portee.launch {
            adresseBoitier.oublier()
            Log.i(TAG, "adresse : oubliée sur demande, retour au scan")
            boucleDeLiaison()
        }
    }

    /** Interrompt le cycle de liaison et ferme le GATT. */
    fun deconnecter() {
        travail?.cancel()
        travail = null
        fermerGatt()
        publier(EtatLiaison.Inactif)
    }

    /**
     * Demande une photo : [CanonProtocol.DECLENCHEMENT] puis [CanonProtocol.RELACHEMENT]
     * sur la caractéristique de contrôle. **Deux écritures, pas une** — sans relâchement le
     * boîtier garde le bouton enfoncé et ignore la vue suivante (vérifié sur R100 le
     * 1er septembre 2026, voir [CanonProtocol.RELACHEMENT]).
     *
     * Feu et oubli : c'est la porte du bouton de l'écran, et l'issue se lit dans
     * [etatDeclencheur]. Une séquence emploie [prendreVue], qui attend cette issue.
     */
    fun declencher() {
        portee.launch { prendreVue() }
    }

    /**
     * Prend une vue et attend son acquittement — c'est par là que passe `IntervalEngine`.
     *
     * Exécutée sur [portee] plutôt que sur la portée de l'appelant, puis attendue : si la
     * séquence est annulée pendant une vue, l'attente est rompue mais la paire appui /
     * relâchement va jusqu'à son terme. Une annulation entre les deux écritures laisserait
     * le bouton enfoncé côté boîtier, défaut corrigé au jalon 2 qui ne doit pas revenir par
     * la porte de l'arrêt manuel.
     *
     * Rend `false` sans rien envoyer si le délai de garde court encore ([pretADeclencher] à
     * `false`) : le créneau est perdu plutôt que compté à tort. Voir [DELAI_GARDE_MS].
     */
    override suspend fun prendreVue(): Boolean = portee.async { vueAvecGarde() }.await()

    private suspend fun vueAvecGarde(): Boolean {
        // `compareAndSet` et non une lecture suivie d'une écriture : deux appuis simultanés
        // ne doivent pas passer tous les deux.
        if (!pretInterne.compareAndSet(expect = true, update = false)) {
            Log.i(TAG, "déclencheur : appui ignoré, délai de garde en cours")
            return false
        }
        return try {
            val issue = executerVue()
            // Le délai de garde ne protège que d'une commande trop rapprochée : il n'a rien
            // à faire là où rien n'est parti. En séquence, l'appliquer quand même ferait
            // perdre le créneau suivant à chaque tentative pendant une coupure.
            if (issue != IssueVue.RIEN_ENVOYE) delay(DELAI_GARDE_MS)
            issue == IssueVue.ACQUITTEE
        } finally {
            pretInterne.value = true
        }
    }

    /** Ce qu'il est possible de savoir du sort d'une vue, vu de la pile BLE. */
    private enum class IssueVue { ACQUITTEE, ECHOUEE, RIEN_ENVOYE }

    private suspend fun executerVue(): IssueVue {
        val lien = lienActif.get()
        if (lien == null) {
            publierDeclencheur(EtatDeclencheur.Echec("Aucune liaison : rien n'a été envoyé."))
            return IssueVue.RIEN_ENVOYE
        }

        publierDeclencheur(EtatDeclencheur.EnCours)
        return try {
            // Les deux écritures sont indissociables et tiennent sous un seul verrou :
            // rien ne doit s'intercaler entre l'appui et son relâchement.
            verrouGatt.withLock {
                ecrireSousVerrou(lien.gatt, lien.controle, byteArrayOf(CanonProtocol.DECLENCHEMENT))
                // La photo est partie ; la vue est acquise même si la suite échoue.
                val vues = vuesPrises.incrementAndGet()
                val instant = System.currentTimeMillis()

                try {
                    ecrireSousVerrou(
                        lien.gatt,
                        lien.controle,
                        byteArrayOf(CanonProtocol.RELACHEMENT),
                    )
                    publierDeclencheur(EtatDeclencheur.Reussi(vues, instant))
                    IssueVue.ACQUITTEE
                } catch (echec: EchecLiaison) {
                    // Cas à ne pas travestir en simple succès : la vue est bien prise, mais
                    // le bouton reste enfoncé côté boîtier et la suivante sera ignorée en
                    // silence. C'est exactement le défaut corrigé le 1er septembre 2026 ;
                    // il ne doit pas pouvoir revenir sans se voir.
                    publierDeclencheur(
                        EtatDeclencheur.Echec(
                            "Vue $vues prise, mais le relâchement a échoué : reconnexion " +
                                "pour réarmer le boîtier.",
                            echec.codeGatt,
                        ),
                    )
                    // Rendue échouée bien que la photo existe : la séquence ne doit pas
                    // compter une vue dont elle sait que la suivante est compromise.
                    forcerReconnexion()
                    IssueVue.ECHOUEE
                }
            }
        } catch (echec: EchecLiaison) {
            // La boucle de liaison n'est pas interrompue : si la coupure est réelle, le
            // rappel GATT la signale et la reprise est déjà son travail. Un échec de
            // l'appui n'a pas à décider du sort de la liaison — contrairement à un échec
            // du relâchement, qui laisse le boîtier dans un état dont il faut le sortir.
            publierDeclencheur(EtatDeclencheur.Echec(echec.message.orEmpty(), echec.codeGatt))
            IssueVue.ECHOUEE
        }
    }

    /**
     * Coupe le lien pour que [boucleDeLiaison] le rétablisse.
     *
     * Seul remède connu à un relâchement échoué : le boîtier garde alors le bouton enfoncé
     * et acquitte `GATT_SUCCESS` toutes les vues suivantes **sans en produire aucune**.
     * Rien dans le dialogue BLE ne permet de le détecter — une séquence de nuit continuerait
     * à vide jusqu'au matin. Le jalon 2 a établi qu'une reconnexion réarme le déclencheur
     * (vérifié sur R100 le 1er septembre 2026, voir `doc/jalon-2-declenchement.md`) ; on la
     * provoque plutôt que de l'attendre.
     *
     * [lienActif] est vidé d'abord : aucune commande ne doit plus partir sur ce lien, et
     * `getAndSet` rend l'appel idempotent si la coupure était déjà réelle.
     */
    @SuppressLint("MissingPermission")
    private fun forcerReconnexion() {
        val lien = lienActif.getAndSet(null) ?: return
        Log.w(TAG, "déclencheur : relâchement échoué, reconnexion forcée pour réarmer le boîtier")
        runCatching { lien.gatt.disconnect() }
    }

    // ------------------------------------------------------------- Boucle de liaison

    private suspend fun boucleDeLiaison() {
        var tentatives = 0
        var cyclesAppairage = 0

        while (currentCoroutineContext().isActive) {
            verifierPrerequis()?.let { manquant ->
                publier(manquant)

                // Pendant une séquence, un prérequis qui peut revenir de lui-même ne met
                // pas fin au cycle : le Bluetooth coupé à 2 h du matin — mode avion effleuré,
                // économiseur zélé — ne doit pas condamner la nuit entière (NF3). Les autres
                // (pas de radio BLE, permissions refusées) exigent l'utilisateur, donc
                // l'application au premier plan : boucler n'y changerait rien.
                if (!sequenceActive.get() || !reversible(manquant)) return
                tentatives++
                delay(temporisation(tentatives))
                continue
            }

            try {
                val appareil = appareilCible()
                etablirLiaison(appareil)
                adresseBoitier.memoriser(appareil.address)
                Log.i(TAG, "adresse : mémorisée ${appareil.address}")

                tentatives = 0
                publier(EtatLiaison.Prete)
                val instantPret = SystemClock.elapsedRealtime()

                // La liaison tient jusqu'à la prochaine coupure ; on l'attend ici.
                val coupure = attendreDeconnexion()
                fermerGatt()

                if (SystemClock.elapsedRealtime() - instantPret < FENETRE_CYCLE_APPAIRAGE_MS) {
                    // Coupure immédiate après identification : c'est le premier
                    // enregistrement. Le jalon 0 l'a observée, elle est attendue et non
                    // fatale — on reconnecte aussitôt, sans temporisation. Plafonnée pour
                    // ne pas boucler indéfiniment si le boîtier coupait systématiquement.
                    cyclesAppairage++
                    if (cyclesAppairage > CYCLES_APPAIRAGE_MAX) {
                        publier(
                            EtatLiaison.Erreur(
                                "Le boîtier coupe la liaison après chaque identification.",
                                coupure.codeGatt,
                            ),
                        )
                        return
                    }
                    continue
                }

                cyclesAppairage = 0
                tentatives = 1
            } catch (echec: EchecLiaison) {
                fermerGatt()
                tentatives++

                // Pendant une séquence, la reprise n'a pas de plafond : c'est l'exigence
                // NF3, une coupure suspend la séquence, elle ne l'annule pas. Hors séquence
                // le plafond tient — devant un boîtier éteint, mieux vaut un échec affiché
                // qu'une reprise perpétuelle qui vide la batterie sans rien dire.
                if (!sequenceActive.get() && tentatives > TENTATIVES_MAX) {
                    publier(EtatLiaison.Erreur(echec.message.orEmpty(), echec.codeGatt))
                    return
                }
            }

            // Reprise. L'erreur 133 d'Android est courante et souvent transitoire ; le GATT
            // vient d'être fermé, ce qui est la condition pour qu'une reprise aboutisse.
            publier(
                EtatLiaison.Reconnexion(
                    tentative = tentatives,
                    // Pas de total à annoncer quand il n'y a pas de plafond : afficher
                    // « 7 sur 3 » serait absurde, et « 7 sur ∞ » n'apprend rien.
                    total = if (sequenceActive.get()) null else TENTATIVES_MAX,
                ),
            )
            delay(temporisation(tentatives))
        }
    }

    /**
     * Temporisation avant la reprise : doublement à chaque échec, plafonné.
     *
     * Le plafond n'est pas cosmétique. Sans lui, une séquence de nuit qui multiplie les
     * échecs finirait par attendre des heures entre deux tentatives — le décalage exponentiel
     * dépasserait vite la durée de la séquence elle-même.
     */
    private fun temporisation(tentatives: Int): Long {
        val decalage = (tentatives - 1).coerceIn(0, 16)
        return (TEMPORISATION_BASE_MS shl decalage).coerceAtMost(TEMPORISATION_MAX_MS)
    }

    /**
     * Vrai si ce prérequis manquant peut redevenir vrai sans que l'utilisateur ouvre
     * l'application. Voir l'usage dans [boucleDeLiaison].
     */
    private fun reversible(manquant: EtatLiaison): Boolean =
        manquant is EtatLiaison.BluetoothEteint || manquant is EtatLiaison.LocalisationDesactivee

    private fun verifierPrerequis(): EtatLiaison? = when {
        adaptateur == null -> EtatLiaison.BluetoothIndisponible
        !permissionsAccordees() -> EtatLiaison.PermissionsManquantes
        !adaptateur.isEnabled -> EtatLiaison.BluetoothEteint
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !localisationActive() ->
            EtatLiaison.LocalisationDesactivee

        else -> null
    }

    private fun permissionsAccordees(): Boolean = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(contexte, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun localisationActive(): Boolean {
        val gestionnaire = contexte.getSystemService(LocationManager::class.java) ?: return false
        return LocationManagerCompat.isLocationEnabled(gestionnaire)
    }

    // ------------------------------------------------------------ Choix de l'appareil

    /**
     * L'adresse mémorisée si elle existe, un scan sinon.
     *
     * **Aucun repli automatique sur le scan.** Le jalon 1 en avait posé un après deux échecs
     * sur l'adresse connue ; le jalon 4 l'a retiré, parce qu'il est contre-productif : un
     * boîtier endormi ou hors de portée n'émet pas d'advertising, un scan ne peut donc pas
     * aboutir là où une reconnexion sur l'adresse connue aurait fini par réussir. Le repli
     * remplaçait une tentative qui pouvait marcher par une qui échouerait à coup sûr, et
     * coûtait 20 s de délai de scan à chaque tour.
     *
     * Le scan reste accessible, mais sur demande explicite : voir [oublierBoitier].
     */
    private suspend fun appareilCible(): BluetoothDevice {
        val adaptateur = requireNotNull(adaptateur)
        val connue = adresseBoitier.lire()
        Log.i(TAG, "adresse : relue $connue")
        if (connue != null && BluetoothAdapter.checkBluetoothAddress(connue)) {
            return adaptateur.getRemoteDevice(connue)
        }
        return scanner()
    }

    @SuppressLint("MissingPermission")
    private suspend fun scanner(): BluetoothDevice {
        publier(EtatLiaison.Recherche)
        val scanner = adaptateur?.bluetoothLeScanner
            ?: throw EchecLiaison("Scanner Bluetooth indisponible.")

        // Filtre sur l'UUID de service : c'est ce qu'emploient furble et maxmacstn, et le
        // R100 annonce bien ce service dans son advertising (vérifié le 31 août 2026).
        val filtre = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(CanonProtocol.SERVICE))
            .build()
        val reglages = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val trouve = CompletableDeferred<BluetoothDevice>()
        val rappel = object : ScanCallback() {
            override fun onScanResult(typeRappel: Int, resultat: ScanResult) {
                trouve.complete(resultat.device)
            }

            override fun onScanFailed(code: Int) {
                trouve.completeExceptionally(EchecLiaison("Scan impossible.", code))
            }
        }

        try {
            scanner.startScan(listOf(filtre), reglages, rappel)
            return withTimeoutOrNull(DELAI_SCAN_MS) { trouve.await() }
                ?: throw EchecLiaison("Aucun boîtier trouvé. Est-il en mode appairage ?")
        } finally {
            runCatching { scanner.stopScan(rappel) }
        }
    }

    // --------------------------------------------------------- Établissement du lien

    @SuppressLint("MissingPermission")
    private suspend fun etablirLiaison(appareil: BluetoothDevice) {
        viderEvenements()
        publier(EtatLiaison.Connexion)

        val lien = appareil.connectGatt(contexte, false, rappelsGatt, BluetoothDevice.TRANSPORT_LE)
            ?: throw EchecLiaison("connectGatt a échoué.")
        gatt = lien

        attendreEvenement(DELAI_CONNEXION_MS, "connexion") { it is EvenementGatt.Connecte }

        if (!lien.discoverServices()) throw EchecLiaison("Découverte des services refusée.")
        val decouverte = attendreEvenement(DELAI_DECOUVERTE_MS, "découverte des services") {
            it is EvenementGatt.ServicesDecouverts
        } as EvenementGatt.ServicesDecouverts
        if (decouverte.codeGatt != BluetoothGatt.GATT_SUCCESS) {
            throw EchecLiaison("Découverte des services en échec.", decouverte.codeGatt)
        }

        // Aucune écriture avant ce point : les caractéristiques n'existent pas avant.
        val service = lien.getService(CanonProtocol.SERVICE)
            ?: throw EchecLiaison(
                "Service Canon absent : cet appareil n'est pas un boîtier compatible.",
            )
        val identification = service.getCharacteristic(CanonProtocol.CARACTERISTIQUE_IDENTIFICATION)
            ?: throw EchecLiaison("Caractéristique d'identification absente.")
        val controle = service.getCharacteristic(CanonProtocol.CARACTERISTIQUE_CONTROLE)
            ?: throw EchecLiaison("Caractéristique de contrôle absente.")

        assurerBond(appareil)

        publier(EtatLiaison.Identification)
        ecrire(
            lien,
            identification,
            CanonProtocol.trameIdentification(CanonProtocol.NOM_TELECOMMANDE),
        )

        // À partir d'ici seulement le lien est utilisable pour déclencher. Le poser plus tôt
        // ouvrirait une fenêtre où le bouton serait actif avant l'identification.
        lienActif.set(LienActif(lien, controle))
    }

    /**
     * Obtient le bond explicitement et attend son aboutissement réel.
     *
     * Le laisser au système lors de la première écriture chiffrée serait plus court, mais
     * ni le moment ni l'échec ne seraient observables — et « bond persistant » est
     * justement le critère de sortie de ce jalon.
     */
    @SuppressLint("MissingPermission")
    private suspend fun assurerBond(appareil: BluetoothDevice) {
        Log.i(TAG, "bond : état initial ${nomBond(appareil.bondState)} sur ${appareil.address}")
        if (appareil.bondState == BluetoothDevice.BOND_BONDED) return

        publier(EtatLiaison.Appairage)
        val abouti = CompletableDeferred<Boolean>()
        val recepteur = object : BroadcastReceiver() {
            override fun onReceive(contexteRecu: Context?, intention: Intent) {
                val concerne = IntentCompat.getParcelableExtra(
                    intention,
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java,
                )
                val etatBond = intention.getIntExtra(
                    BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.BOND_NONE,
                )
                // Journalisé avant le filtre d'adresse : une diffusion reçue puis écartée
                // et une diffusion jamais reçue sont deux pannes différentes.
                Log.i(
                    TAG,
                    "bond : diffusion ${concerne?.address} → ${nomBond(etatBond)} " +
                        "(attendu ${appareil.address})",
                )
                if (concerne?.address != appareil.address) return
                when (etatBond) {
                    BluetoothDevice.BOND_BONDED -> abouti.complete(true)
                    BluetoothDevice.BOND_NONE -> abouti.complete(false)
                }
            }
        }

        // RECEIVER_EXPORTED et non NOT_EXPORTED : vérifié sur SM-G973U1 (Android 12) le
        // 1er septembre 2026, NOT_EXPORTED empêche purement et simplement la livraison de
        // ACTION_BOND_STATE_CHANGED — le bond aboutissait, l'application ne l'apprenait
        // jamais. La diffusion vient du processus Bluetooth, pas de l'UID système. Aucun
        // risque d'usurpation : c'est une diffusion protégée, seul le système peut l'émettre.
        ContextCompat.registerReceiver(
            contexte,
            recepteur,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        try {
            val demandee = appareil.createBond()
            Log.i(TAG, "bond : createBond() = $demandee")
            if (!demandee) throw EchecLiaison("Demande d'appairage refusée.")
            // Deux sources concurrentes, délibérément. `bondState` est la source de vérité :
            // il ne dépend d'aucune livraison de diffusion. La diffusion reste utile parce
            // qu'elle est immédiate et qu'elle seule distingue l'échec (BOND_NONE) de
            // l'attente. Se fier à la seule diffusion est ce qui a produit le blocage de
            // 30 s du 1er septembre 2026.
            val reussi = withTimeoutOrNull(DELAI_APPAIRAGE_MS) {
                var parDiffusion: Boolean? = null
                while (parDiffusion == null && appareil.bondState != BluetoothDevice.BOND_BONDED) {
                    parDiffusion = withTimeoutOrNull(INTERVALLE_SCRUTIN_BOND_MS) { abouti.await() }
                }
                Log.i(TAG, "bond : abouti par ${if (parDiffusion == null) "scrutin" else "diffusion"}")
                parDiffusion ?: true
            }
            Log.i(
                TAG,
                "bond : issue = $reussi, état final ${nomBond(appareil.bondState)}" +
                    if (reussi == null) " (délai de ${DELAI_APPAIRAGE_MS} ms dépassé)" else "",
            )
            if (reussi != true) throw EchecLiaison("Appairage non abouti.")
        } finally {
            runCatching { contexte.unregisterReceiver(recepteur) }
        }
    }

    private fun nomBond(etat: Int): String = when (etat) {
        BluetoothDevice.BOND_NONE -> "BOND_NONE"
        BluetoothDevice.BOND_BONDING -> "BOND_BONDING"
        BluetoothDevice.BOND_BONDED -> "BOND_BONDED"
        else -> "inconnu($etat)"
    }

    // ----------------------------------------------------------------- Écriture GATT

    @SuppressLint("MissingPermission")
    private suspend fun ecrire(
        lien: BluetoothGatt,
        caracteristique: BluetoothGattCharacteristic,
        valeur: ByteArray,
    ) = verrouGatt.withLock { ecrireSousVerrou(lien, caracteristique, valeur) }

    /**
     * Écriture proprement dite, [verrouGatt] déjà tenu. Existe pour qu'une suite
     * d'écritures indissociables — la paire appui/relâchement d'une vue — puisse tenir
     * sous un seul verrou. Le `Mutex` de kotlinx.coroutines n'est pas réentrant : appeler
     * [ecrire] depuis une section déjà verrouillée bloquerait définitivement.
     */
    @SuppressLint("MissingPermission")
    private suspend fun ecrireSousVerrou(
        lien: BluetoothGatt,
        caracteristique: BluetoothGattCharacteristic,
        valeur: ByteArray,
    ) {
        val code = withTimeoutOrNull(DELAI_ECRITURE_MS) {
            suspendCancellableCoroutine<Int> { continuation ->
                ecritureEnAttente.set(continuation)
                continuation.invokeOnCancellation { ecritureEnAttente.set(null) }

                val lancee = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    lien.writeCharacteristic(
                        caracteristique,
                        valeur,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        caracteristique.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        caracteristique.value = valeur
                        lien.writeCharacteristic(caracteristique)
                    }
                }

                if (!lancee) {
                    ecritureEnAttente.getAndSet(null)
                        ?.takeIf { it.isActive }
                        ?.resumeWithException(EchecLiaison("Écriture refusée par la pile BLE."))
                }
            }
        } ?: run {
            ecritureEnAttente.set(null)
            throw EchecLiaison("Écriture sans acquittement dans le délai imparti.")
        }

        Log.i(
            TAG,
            "écriture : ${valeur.joinToString(" ") { "%02X".format(it) }} sur " +
                "${caracteristique.uuid.toString().take(8)} acquittée, code $code",
        )
        if (code != BluetoothGatt.GATT_SUCCESS) {
            throw EchecLiaison("Écriture refusée par le boîtier.", code)
        }
    }

    // ------------------------------------------------------------------ Rappels GATT

    private val rappelsGatt = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(lien: BluetoothGatt, code: Int, nouvelEtat: Int) {
            when (nouvelEtat) {
                BluetoothProfile.STATE_CONNECTED -> evenements.trySend(EvenementGatt.Connecte)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    ecritureEnAttente.getAndSet(null)
                        ?.takeIf { it.isActive }
                        ?.resumeWithException(
                            EchecLiaison("Liaison coupée pendant l'écriture.", code),
                        )
                    evenements.trySend(EvenementGatt.Deconnecte(code))
                }
            }
        }

        override fun onServicesDiscovered(lien: BluetoothGatt, code: Int) {
            evenements.trySend(EvenementGatt.ServicesDecouverts(code))
        }

        @Deprecated("Signature remplacée en API 33 ; conservée pour minSdk 26.")
        override fun onCharacteristicWrite(
            lien: BluetoothGatt,
            caracteristique: BluetoothGattCharacteristic?,
            code: Int,
        ) {
            ecritureEnAttente.getAndSet(null)?.takeIf { it.isActive }?.resume(code)
        }
    }

    // -------------------------------------------------------------------- Utilitaires

    /**
     * Attend l'événement décrit par [accepte]. Une déconnexion interrompt l'attente : elle
     * rend caduque toute étape en cours.
     */
    private suspend fun attendreEvenement(
        delaiMs: Long,
        etape: String,
        accepte: (EvenementGatt) -> Boolean,
    ): EvenementGatt {
        val evenement = withTimeoutOrNull(delaiMs) {
            var recu = evenements.receive()
            while (!accepte(recu) && recu !is EvenementGatt.Deconnecte) {
                recu = evenements.receive()
            }
            recu
        } ?: throw EchecLiaison("Délai dépassé à l'étape : $etape.")

        if (evenement is EvenementGatt.Deconnecte && !accepte(evenement)) {
            throw EchecLiaison("Liaison coupée à l'étape : $etape.", evenement.codeGatt)
        }
        return evenement
    }

    /** Attend la coupure du lien. Sans délai : une session dure ce qu'elle dure. */
    private suspend fun attendreDeconnexion(): EvenementGatt.Deconnecte {
        var recu = evenements.receive()
        while (recu !is EvenementGatt.Deconnecte) recu = evenements.receive()
        return recu
    }

    private fun viderEvenements() {
        while (evenements.tryReceive().isSuccess) Unit
    }

    /**
     * Ferme le GATT. Indispensable avant toute reprise : ne pas fermer laisse fuir une
     * interface client, et c'est la cause classique d'une erreur 133 qui se répète.
     */
    @SuppressLint("MissingPermission")
    private fun fermerGatt() {
        // Avant toute autre chose : plus aucune commande ne doit partir sur ce lien.
        lienActif.set(null)
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
    }

    companion object {
        private const val TAG = "BleRemote"

        /** Permissions à demander à l'exécution selon le régime d'API. Le manifeste les déclare. */
        val PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        private const val DELAI_SCAN_MS = 20_000L
        private const val DELAI_CONNEXION_MS = 15_000L
        private const val DELAI_DECOUVERTE_MS = 10_000L
        private const val DELAI_APPAIRAGE_MS = 30_000L

        /** Période de relecture de `bondState` pendant l'appairage. Voir [assurerBond]. */
        private const val INTERVALLE_SCRUTIN_BOND_MS = 500L
        private const val DELAI_ECRITURE_MS = 5_000L

        /**
         * Délai de garde après une vue, pendant lequel un nouvel appui est ignoré.
         *
         * Mesuré sur R100 le 1er septembre 2026 : une commande envoyée peu après une vue —
         * typiquement pendant la revue d'image — est acquittée au niveau ATT et ne produit
         * pas de photo. Rien dans le dialogue BLE ne permet de le savoir : refuser d'envoyer
         * est le seul moyen pour que le compteur de vues ne mente pas. Au-delà de 500 ms à
         * 1 s, plus aucune perte observée ; 1 s prend la marge.
         *
         * Ce n'est pas une garantie. Si la durée de revue du boîtier est réglée plus longue,
         * la fenêtre dépasse ce délai et le décalage redevient possible. Le seul moyen de
         * savoir ce que le boîtier a réellement fait serait de lire ses caractéristiques
         * INDICATE (`00050004`, `00050006`, `00050007`, `0005000b`), jamais explorées.
         *
         * Sans effet sur le jalon 3 : une séquence travaille à la seconde ou plus.
         */
        private const val DELAI_GARDE_MS = 1_000L

        /** Plafond de reprise **hors séquence** seulement. Voir [sequenceActive]. */
        private const val TENTATIVES_MAX = 3

        private const val TEMPORISATION_BASE_MS = 1_000L

        /** Plafond de la temporisation de reprise. Voir [temporisation]. */
        private const val TEMPORISATION_MAX_MS = 32_000L

        /**
         * Une coupure survenant dans cette fenêtre après l'identification est celle du
         * premier enregistrement, observée au jalon 0 : attendue, donc non fatale.
         */
        private const val FENETRE_CYCLE_APPAIRAGE_MS = 5_000L
        private const val CYCLES_APPAIRAGE_MAX = 2
    }
}
