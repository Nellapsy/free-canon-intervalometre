package fr.nellapsy.canonintervallometre

import android.app.Application
import android.os.SystemClock
import fr.nellapsy.canonintervallometre.ble.BleRemote
import fr.nellapsy.canonintervallometre.interval.IntervalEngine
import fr.nellapsy.canonintervallometre.interval.ReglagesSequence
import fr.nellapsy.canonintervallometre.service.ShutterService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Détient l'unique liaison BLE, l'unique moteur de séquence, et la portée qui les porte.
 *
 * Tout cela vit au niveau de l'application, et non dans un `ViewModel` : une séquence doit
 * survivre à la rotation et à la fermeture de l'activité.
 *
 * Depuis le jalon 4, la séquence ne tourne plus sur la portée de l'application mais dans
 * [ShutterService], qui seul peut promettre au système de survivre à l'écran éteint. Ce qui
 * reste ici est ce qui doit vivre **hors** séquence : la liaison, qui sert aussi au bouton
 * de déclenchement manuel, et le moteur, dont l'écran observe l'état.
 */
class IntervallometreApp : Application() {

    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val bleRemote: BleRemote by lazy { BleRemote(this, portee) }

    /**
     * `elapsedRealtime` et non `currentTimeMillis` : la grille doit rester régulière même
     * si l'horloge murale saute (fuseau, synchronisation réseau, passage à l'heure d'hiver).
     */
    val moteur: IntervalEngine by lazy { IntervalEngine(bleRemote, SystemClock::elapsedRealtime) }

    /**
     * À appeler depuis l'interface, donc application au premier plan : à partir d'API 31,
     * c'est la condition pour qu'un service foreground puisse démarrer.
     */
    fun demarrerSequence(reglages: ReglagesSequence) {
        ShutterService.demarrer(this, reglages)
    }

    /** Arrêt manuel (F10). Le moteur publie l'état terminé depuis son `finally`. */
    fun arreterSequence() {
        ShutterService.arreter(this)
    }
}
