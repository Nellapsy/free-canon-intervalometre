package fr.nellapsy.canonintervallometre.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.nellapsy.canonintervallometre.IntervallometreApp
import fr.nellapsy.canonintervallometre.R
import fr.nellapsy.canonintervallometre.ble.BleRemote
import fr.nellapsy.canonintervallometre.ble.EtatLiaison

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val liaison = (application as IntervallometreApp).bleRemote
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EcranPrincipal(liaison)
                }
            }
        }
    }
}

@Composable
fun EcranPrincipal(liaison: BleRemote) {
    val etat by liaison.etat.collectAsStateWithLifecycle()

    val demandePermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { resultats ->
        if (resultats.values.all { it }) liaison.connecter()
    }

    // Reconnexion automatique au lancement : c'est ce que vérifie la recette F1, relancer
    // l'application ne doit rien demander. Inutile de tester les permissions ici, la
    // liaison le fait elle-même et publie l'état correspondant.
    LaunchedEffect(Unit) { liaison.connecter() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = libelleEtat(etat),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.rappel_mode_appairage),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = { demandePermissions.launch(BleRemote.PERMISSIONS) },
            enabled = !enCours(etat),
        ) {
            Text(stringResource(R.string.bouton_rechercher))
        }
    }
}

/**
 * Vrai tant qu'un cycle de liaison est en cours. Le bouton reste alors inerte : proposer
 * de relancer une recherche déjà en cours laisserait croire à une action sans effet.
 */
private fun enCours(etat: EtatLiaison): Boolean = when (etat) {
    is EtatLiaison.Recherche,
    is EtatLiaison.Connexion,
    is EtatLiaison.Appairage,
    is EtatLiaison.Identification,
    is EtatLiaison.Prete,
    is EtatLiaison.Reconnexion,
    -> true

    else -> false
}

@Composable
private fun libelleEtat(etat: EtatLiaison): String = when (etat) {
    EtatLiaison.BluetoothIndisponible -> stringResource(R.string.etat_bluetooth_indisponible)
    EtatLiaison.PermissionsManquantes -> stringResource(R.string.etat_permissions_manquantes)
    EtatLiaison.BluetoothEteint -> stringResource(R.string.etat_bluetooth_eteint)
    EtatLiaison.LocalisationDesactivee -> stringResource(R.string.etat_localisation_desactivee)
    EtatLiaison.Inactif -> stringResource(R.string.etat_inactif)
    EtatLiaison.Recherche -> stringResource(R.string.etat_recherche)
    EtatLiaison.Connexion -> stringResource(R.string.etat_connexion)
    EtatLiaison.Appairage -> stringResource(R.string.etat_appairage)
    EtatLiaison.Identification -> stringResource(R.string.etat_identification)
    EtatLiaison.Prete -> stringResource(R.string.etat_prete)
    is EtatLiaison.Reconnexion ->
        stringResource(R.string.etat_reconnexion, etat.tentative, etat.total)

    is EtatLiaison.Erreur -> when (val code = etat.codeGatt) {
        null -> stringResource(R.string.etat_erreur, etat.message)
        else -> stringResource(R.string.etat_erreur_code, etat.message, code)
    }
}
