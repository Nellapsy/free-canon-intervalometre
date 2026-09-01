package fr.nellapsy.canonintervallometre.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import fr.nellapsy.canonintervallometre.IntervallometreApp
import fr.nellapsy.canonintervallometre.R
import fr.nellapsy.canonintervallometre.ble.EtatLiaison
import fr.nellapsy.canonintervallometre.interval.EtatSequence
import fr.nellapsy.canonintervallometre.interval.ReglagesSequence
import fr.nellapsy.canonintervallometre.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Héberge la boucle d'intervalle dans un service foreground (F6, NF2).
 *
 * Le service ne calcule rien : `IntervalEngine` et `BleRemote` continuent de vivre dans
 * [IntervallometreApp]. Il n'apporte que ce qu'une `Application` ne sait pas faire — dire au
 * système que ce travail doit survivre à l'écran éteint. C'est précisément le déplacement
 * que le jalon 3 avait préparé en faisant d'`executer()` une fonction suspendue dont le job
 * appartient à l'appelant : le moteur ignore encore d'où il est appelé.
 *
 * Deux contraintes de plateforme dictent la forme du code :
 *
 * - **`startForeground` en première ligne d'[onStartCommand]**, avant tout le reste. Au-delà
 *   de cinq secondes sans notification, le système tue le service et lève une ANR.
 * - **`START_NOT_STICKY`.** Une séquence relancée d'elle-même après un arrêt système aurait
 *   perdu l'origine de sa grille : elle produirait des vues sur une grille neuve, décalée de
 *   la première. Mieux vaut qu'elle s'arrête franchement — et que la notification finale le
 *   dise — qu'une reprise qui se croit dans la continuité.
 */
class ShutterService : LifecycleService() {

    private lateinit var app: IntervallometreApp
    private val notifications by lazy { NotificationManagerCompat.from(this) }

    private var sequence: Job? = null

    /** Suivi des états qui alimente la notification. Lancé une fois, pas à chaque intention. */
    private var suivi: Job? = null

    /**
     * Maintient le processeur éveillé pendant toute la séquence (NF1 sur la durée).
     *
     * C'est la pièce que le service foreground **ne** fournit pas : il empêche d'être tué,
     * pas le CPU de s'endormir. Écran éteint et téléphone posé, le processeur finit en veille
     * profonde, et un `delay()` n'est qu'une minuterie en mémoire — elle ne part pas pendant
     * le sommeil, elle part au réveil suivant. Sans ce verrou, une grille de 30 s se met à
     * sauter des créneaux au bout de quelques dizaines de minutes.
     *
     * `setReferenceCounted(false)` : une acquisition déjà tenue et une libération en trop
     * doivent rester sans effet, le service pouvant recevoir plusieurs intentions.
     */
    private val veille by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName::sequence")
            .apply { setReferenceCounted(false) }
    }

    /** Retenu du démarrage : le moteur ne le publie pas, et l'heure de fin en dépend. */
    private var intervalleMs = 0L

    /** Dernier contenu affiché, pour que la notification détachée de la fin le reprenne. */
    private var dernierContenu: ContenuNotification = ContenuNotification.Demarrage

    override fun onCreate() {
        super.onCreate()
        app = application as IntervallometreApp
        notifications.createNotificationChannel(
            NotificationChannelCompat.Builder(CANAL, NotificationManagerCompat.IMPORTANCE_LOW)
                // IMPORTANCE_LOW : la notification se met à jour à chaque vue. Au-dessus,
                // une séquence de nuit sonnerait 240 fois.
                .setName(getString(R.string.notification_canal_nom))
                .setDescription(getString(R.string.notification_canal_description))
                .build(),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (!passerAuPremierPlan()) return START_NOT_STICKY

        when (intent?.action) {
            ACTION_DEMARRER -> demarrer(reglagesDepuis(intent))
            ACTION_ARRETER -> arreter()
            else -> {
                Log.w(TAG, "service : intention sans action reconnue, arrêt")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Filet : ni le drapeau ni le verrou de veille ne doivent survivre au service. Le
        // premier ferait reprendre la liaison sans plafond pour toujours, le second
        // empêcherait le téléphone de dormir jusqu'au redémarrage.
        app.bleRemote.signalerSequence(false)
        libererVeille()
        super.onDestroy()
    }

    /** Idempotent : [terminer] et [onDestroy] passent tous deux par là, dans cet ordre. */
    private fun libererVeille() {
        if (veille.isHeld) {
            veille.release()
            Log.i(TAG, "service : veille CPU relâchée")
        }
    }

    /**
     * Rend faux si le système refuse le passage au premier plan — le service s'arrête alors
     * plutôt que de tourner en arrière-plan, où il serait tué sans prévenir au milieu de la
     * nuit. Le refus vient du démarrage depuis l'arrière-plan (API 31+) ou de
     * `BLUETOOTH_CONNECT` manquante pour un service de type `connectedDevice` (API 34+).
     */
    private fun passerAuPremierPlan(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION,
            construire(dernierContenu),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        true
    } catch (refus: Exception) {
        Log.e(TAG, "service : passage au premier plan refusé", refus)
        stopSelf()
        false
    }

    /** Sans effet si une séquence tourne déjà : deux grilles concurrentes n'ont aucun sens. */
    private fun demarrer(reglages: ReglagesSequence) {
        if (sequence?.isActive == true) {
            Log.i(TAG, "service : séquence déjà en cours, démarrage ignoré")
            return
        }
        intervalleMs = reglages.intervalleMs
        app.bleRemote.signalerSequence(true)

        // Sans délai d'expiration, volontairement : une séquence illimitée n'a pas de durée
        // connue, et un verrou qui lâcherait en pleine nuit rendrait la panne pire que son
        // absence — elle serait intermittente. La libération est garantie par [terminer] et,
        // en dernier ressort, par [onDestroy], qu'Android appelle toujours.
        @Suppress("WakelockTimeout")
        veille.acquire()
        Log.i(TAG, "service : CPU maintenu éveillé pour la séquence")

        // Les deux états sont suivis ensemble : la notification doit dire à la fois où en
        // est la séquence et si la liaison tient (F7). Un seul suivi pour la vie du service,
        // `lifecycleScope` s'en chargeant à sa destruction.
        if (suivi == null) {
            suivi = lifecycleScope.launch {
                combine(app.moteur.etat, app.bleRemote.etat, ::contenuActuel).collect { contenu ->
                    dernierContenu = contenu
                    notifier(contenu)
                }
            }
        }

        sequence = lifecycleScope.launch { app.moteur.executer(reglages) }
        sequence?.invokeOnCompletion { terminer() }
    }

    /**
     * Arrêt manuel (F10), depuis l'écran comme depuis la notification. L'annulation du job
     * suffit : le `finally` du moteur publie l'état terminé, et [terminer] suit.
     */
    private fun arreter() {
        Log.i(TAG, "service : arrêt demandé")
        sequence?.cancel()
        // Aucune séquence à annuler — jamais démarrée, ou déjà finie : `terminer` ne sera
        // pas appelé, et le service vient d'être promu au premier plan par `onStartCommand`.
        // Sans cet arrêt il y resterait indéfiniment.
        if (sequence?.isActive != true) stopSelf()
    }

    /**
     * Détache la notification avant d'arrêter le service, pour que le bilan reste lisible.
     *
     * Sans ce détachement elle disparaîtrait avec le service, et une séquence finie à 3 h du
     * matin ne laisserait aucune trace au réveil. Détachée, elle devient une notification
     * ordinaire, que l'utilisateur balaie quand il l'a lue.
     */
    private fun terminer() {
        app.bleRemote.signalerSequence(false)
        libererVeille()
        // Le bilan est relu à la source et non repris de [dernierContenu] : le moteur publie
        // son état terminé depuis un `finally`, et rien ne garantit que le suivi l'ait déjà
        // reçu quand la fin du job nous amène ici. Le lire directement supprime la course.
        val bilan = contenuActuel(app.moteur.etat.value, app.bleRemote.etat.value)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        notifier(bilan)
        stopSelf()
    }

    private fun contenuActuel(
        sequence: EtatSequence,
        liaison: EtatLiaison,
    ): ContenuNotification = contenuNotification(
        sequence = sequence,
        liaison = liaison,
        intervalleMs = intervalleMs,
        // Les deux horloges sont relues ensemble à chaque rendu : leur écart change dès
        // qu'Android se remet à l'heure, et le figer afficherait une heure de fin fausse.
        maintenantMonotoneMs = SystemClock.elapsedRealtime(),
        maintenantMurMs = System.currentTimeMillis(),
    )

    /**
     * `POST_NOTIFICATIONS` refusée n'est pas une erreur : le service tourne et la séquence
     * se déroule, seule la notification reste invisible. C'est un choix — refuser de
     * démarrer punirait l'utilisateur pour une permission cosmétique.
     */
    private fun notifier(contenu: ContenuNotification) {
        try {
            notifications.notify(NOTIFICATION, construire(contenu))
        } catch (refus: SecurityException) {
            Log.i(TAG, "notification : non affichée, permission refusée")
        }
    }

    // ------------------------------------------------------------- Mise en forme

    private fun construire(contenu: ContenuNotification): Notification {
        val constructeur = NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(titre(contenu))
            .setContentText(texte(contenu))
            .setContentIntent(ouvrirApplication())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setOngoing(contenu !is ContenuNotification.Terminee)

        if (contenu is ContenuNotification.EnCours) {
            constructeur.addAction(0, getString(R.string.bouton_arreter), arreterDepuisNotification())
            contenu.vuesDemandees?.let { total ->
                constructeur.setProgress(total, contenu.vuesReussies, false)
            }
        }
        return constructeur.build()
    }

    private fun titre(contenu: ContenuNotification): String = when (contenu) {
        ContenuNotification.Demarrage -> getString(R.string.notification_demarrage)

        is ContenuNotification.EnCours -> when (val total = contenu.vuesDemandees) {
            null -> getString(R.string.notification_vue_illimitee, contenu.vuesReussies)
            else -> getString(R.string.notification_vue, contenu.vuesReussies, total)
        }

        is ContenuNotification.Terminee -> when {
            contenu.complete -> getString(R.string.sequence_terminee, contenu.vuesReussies)
            else -> getString(R.string.sequence_arretee, contenu.vuesReussies)
        }
    }

    private fun texte(contenu: ContenuNotification): String? = when (contenu) {
        ContenuNotification.Demarrage -> null
        is ContenuNotification.Terminee -> null

        is ContenuNotification.EnCours -> when {
            contenu.liaisonRompue -> getString(R.string.notification_liaison_perdue)
            contenu.finEstimeeMurMs != null -> getString(
                R.string.notification_fin_estimee,
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(contenu.finEstimeeMurMs)),
            )

            else -> getString(R.string.notification_liaison_ok)
        }
    }

    /** Toucher la notification ramène à l'écran, sans empiler une seconde activité. */
    private fun ouvrirApplication(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUETE_OUVRIR,
        Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        DRAPEAUX_EN_ATTENTE,
    )

    /**
     * Arrêt depuis la notification (F10). `getService` et non `getForegroundService` : le
     * service tourne déjà, l'intention ne fait que lui parler.
     */
    private fun arreterDepuisNotification(): PendingIntent = PendingIntent.getService(
        this,
        REQUETE_ARRETER,
        Intent(this, ShutterService::class.java).setAction(ACTION_ARRETER),
        DRAPEAUX_EN_ATTENTE,
    )

    private fun reglagesDepuis(intent: Intent): ReglagesSequence = ReglagesSequence(
        delaiAvantDemarrageMs = intent.getLongExtra(EXTRA_DELAI, 0),
        intervalleMs = intent.getLongExtra(EXTRA_INTERVALLE, 0),
        // -1 et non 0 pour l'illimité : 0 vue est une saisie concevable, l'absence de
        // valeur ne l'est pas.
        vuesDemandees = intent.getIntExtra(EXTRA_VUES, -1).takeIf { it >= 0 },
    )

    companion object {
        private const val TAG = "ShutterService"

        private const val CANAL = "sequence"
        private const val NOTIFICATION = 1
        private const val REQUETE_OUVRIR = 0
        private const val REQUETE_ARRETER = 1

        /** `FLAG_IMMUTABLE` : rien d'extérieur n'a à réécrire ces intentions. Exigé depuis API 31. */
        private const val DRAPEAUX_EN_ATTENTE =
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        private const val ACTION_DEMARRER = "fr.nellapsy.canonintervallometre.DEMARRER"
        private const val ACTION_ARRETER = "fr.nellapsy.canonintervallometre.ARRETER"

        private const val EXTRA_DELAI = "delai"
        private const val EXTRA_INTERVALLE = "intervalle"
        private const val EXTRA_VUES = "vues"

        /**
         * À appeler depuis l'interface, donc application au premier plan : c'est la condition
         * pour que `startForegroundService` soit accepté à partir d'API 31.
         */
        fun demarrer(contexte: Context, reglages: ReglagesSequence) {
            val intention = Intent(contexte, ShutterService::class.java)
                .setAction(ACTION_DEMARRER)
                .putExtra(EXTRA_DELAI, reglages.delaiAvantDemarrageMs)
                .putExtra(EXTRA_INTERVALLE, reglages.intervalleMs)
                .putExtra(EXTRA_VUES, reglages.vuesDemandees ?: -1)
            contexte.startForegroundService(intention)
        }

        /** Arrêt manuel depuis l'écran (F10). La notification passe par la même action. */
        fun arreter(contexte: Context) {
            val intention = Intent(contexte, ShutterService::class.java).setAction(ACTION_ARRETER)
            contexte.startForegroundService(intention)
        }
    }
}
