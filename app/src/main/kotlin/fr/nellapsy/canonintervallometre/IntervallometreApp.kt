package fr.nellapsy.canonintervallometre

import android.app.Application
import fr.nellapsy.canonintervallometre.ble.BleRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Détient l'unique liaison BLE et la portée de coroutines qui la porte.
 *
 * La liaison vit au niveau de l'application, et non dans un `ViewModel` : elle doit
 * survivre à la rotation et à la fermeture de l'activité. Le jalon 4 y branchera
 * `ShutterService`, qui prendra la même instance sans que l'interface change.
 */
class IntervallometreApp : Application() {

    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val bleRemote: BleRemote by lazy { BleRemote(this, portee) }
}
