package fr.nellapsy.canonintervallometre

import android.app.Application
import android.os.SystemClock
import fr.nellapsy.canonintervallometre.ble.BleRemote
import fr.nellapsy.canonintervallometre.interval.IntervalEngine
import fr.nellapsy.canonintervallometre.interval.ReglagesSequence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Détient l'unique liaison BLE, l'unique moteur de séquence, et la portée qui les porte.
 *
 * Tout cela vit au niveau de l'application, et non dans un `ViewModel` : une séquence doit
 * survivre à la rotation et à la fermeture de l'activité. Le jalon 4 déplacera le
 * lancement dans `ShutterService` — le moteur, lui, ne changera pas : il ignore d'où il est
 * appelé.
 */
class IntervallometreApp : Application() {

    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val bleRemote: BleRemote by lazy { BleRemote(this, portee) }

    /**
     * `elapsedRealtime` et non `currentTimeMillis` : la grille doit rester régulière même
     * si l'horloge murale saute (fuseau, synchronisation réseau, passage à l'heure d'hiver).
     */
    val moteur: IntervalEngine by lazy { IntervalEngine(bleRemote, SystemClock::elapsedRealtime) }

    private var sequence: Job? = null

    /** Sans effet si une séquence tourne déjà : deux grilles concurrentes n'auraient aucun sens. */
    fun demarrerSequence(reglages: ReglagesSequence) {
        if (sequence?.isActive == true) return
        sequence = portee.launch { moteur.executer(reglages) }
    }

    /** Arrêt manuel (F10). Le moteur publie l'état terminé depuis son `finally`. */
    fun arreterSequence() {
        sequence?.cancel()
        sequence = null
    }
}
