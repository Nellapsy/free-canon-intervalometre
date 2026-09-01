package fr.nellapsy.canonintervallometre.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import fr.nellapsy.canonintervallometre.IntervallometreApp

/**
 * L'activité ne tient que le cycle de vie et le thème. Ni la liaison ni la séquence ne lui
 * appartiennent : elles vivent dans [IntervallometreApp] et survivent à sa disparition.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as IntervallometreApp
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EcranPrincipal(
                        liaison = app.bleRemote,
                        etatSequence = app.moteur.etat,
                        onDemarrer = app::demarrerSequence,
                        onArreter = app::arreterSequence,
                    )
                }
            }
        }
    }
}
