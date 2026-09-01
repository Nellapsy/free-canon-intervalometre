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
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
) {

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

    private var travail: Job? = null
    private var gatt: BluetoothGatt? = null

    /** Sérialise les opérations GATT. Le `Mutex` de kotlinx.coroutines sert les demandes en FIFO. */
    private val verrouGatt = Mutex()

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

    /** Interrompt le cycle de liaison et ferme le GATT. */
    fun deconnecter() {
        travail?.cancel()
        travail = null
        fermerGatt()
        publier(EtatLiaison.Inactif)
    }

    // ------------------------------------------------------------- Boucle de liaison

    private suspend fun boucleDeLiaison() {
        var tentatives = 0
        var cyclesAppairage = 0

        while (currentCoroutineContext().isActive) {
            verifierPrerequis()?.let { manquant ->
                publier(manquant)
                return
            }

            try {
                val appareil = appareilCible(
                    ignorerAdresseConnue = tentatives >= TENTATIVES_SUR_ADRESSE_CONNUE,
                )
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
                if (tentatives > TENTATIVES_MAX) {
                    publier(EtatLiaison.Erreur(echec.message.orEmpty(), echec.codeGatt))
                    return
                }
            }

            // Reprise. L'erreur 133 d'Android est courante et souvent transitoire ; le GATT
            // vient d'être fermé, ce qui est la condition pour qu'une reprise aboutisse.
            // TODO(jalon 4) : NF3 exige une reprise sans plafond pendant une séquence. Le
            //  plafond de ce jalon suffit tant que la liaison n'héberge pas de séquence.
            publier(EtatLiaison.Reconnexion(tentatives, TENTATIVES_MAX))
            delay(TEMPORISATION_BASE_MS shl (tentatives - 1))
        }
    }

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

    private suspend fun appareilCible(ignorerAdresseConnue: Boolean): BluetoothDevice {
        val adaptateur = requireNotNull(adaptateur)
        if (!ignorerAdresseConnue) {
            val connue = adresseBoitier.lire()
            Log.i(TAG, "adresse : relue $connue")
            if (connue != null && BluetoothAdapter.checkBluetoothAddress(connue)) {
                return adaptateur.getRemoteDevice(connue)
            }
        } else {
            Log.i(TAG, "adresse : ignorée volontairement, retour au scan")
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
        service.getCharacteristic(CanonProtocol.CARACTERISTIQUE_CONTROLE)
            ?: throw EchecLiaison("Caractéristique de contrôle absente.")

        assurerBond(appareil)

        publier(EtatLiaison.Identification)
        ecrire(
            lien,
            identification,
            CanonProtocol.trameIdentification(CanonProtocol.NOM_TELECOMMANDE),
        )
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
    ) = verrouGatt.withLock {
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

        Log.i(TAG, "écriture : acquittée, code $code")
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

        private const val TENTATIVES_MAX = 3
        private const val TEMPORISATION_BASE_MS = 1_000L

        /** Au-delà, l'adresse mémorisée est ignorée et on repasse par un scan. */
        private const val TENTATIVES_SUR_ADRESSE_CONNUE = 2

        /**
         * Une coupure survenant dans cette fenêtre après l'identification est celle du
         * premier enregistrement, observée au jalon 0 : attendue, donc non fatale.
         */
        private const val FENETRE_CYCLE_APPAIRAGE_MS = 5_000L
        private const val CYCLES_APPAIRAGE_MAX = 2
    }
}
