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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.nellapsy.canonintervallometre.IntervallometreApp
import fr.nellapsy.canonintervallometre.R
import fr.nellapsy.canonintervallometre.ble.BleRemote
import fr.nellapsy.canonintervallometre.ble.EtatDeclencheur
import fr.nellapsy.canonintervallometre.ble.EtatLiaison
import java.text.DateFormat
import java.util.Date

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
    val declencheur by liaison.etatDeclencheur.collectAsStateWithLifecycle()
    val pretADeclencher by liaison.pretADeclencher.collectAsStateWithLifecycle()

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

        Button(
            // Actif seulement sur une liaison réellement utilisable, et jamais pendant la
            // vue ni son délai de garde : le boîtier ignorerait la commande sans le dire,
            // et le compteur afficherait une vue qui n'existe pas.
            onClick = { liaison.declencher() },
            enabled = etat is EtatLiaison.Prete && pretADeclencher,
        ) {
            Text(stringResource(R.string.bouton_declencher))
        }
        libelleDeclencheur(declencheur)?.let { libelle ->
            Text(
                text = libelle,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
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

/**
 * Libellé de la dernière commande, ou `null` tant qu'aucune n'a été demandée : au repos la
 * ligne n'existe pas, plutôt que d'afficher un « 0 vue » qui ressemblerait à un échec.
 *
 * Le résultat reste affiché jusqu'à la commande suivante. Un message fugace serait un
 * message manqué, et c'est précisément ce qu'il ne faut pas ici.
 */
@Composable
private fun libelleDeclencheur(declencheur: EtatDeclencheur): String? = when (declencheur) {
    EtatDeclencheur.Repos -> null
    EtatDeclencheur.EnCours -> stringResource(R.string.declencheur_en_cours)
    is EtatDeclencheur.Reussi -> pluralStringResource(
        R.plurals.declencheur_reussi,
        declencheur.vues,
        declencheur.vues,
        DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(declencheur.instant)),
    )

    is EtatDeclencheur.Echec -> when (val code = declencheur.codeGatt) {
        null -> stringResource(R.string.etat_erreur, declencheur.message)
        else -> stringResource(R.string.etat_erreur_code, declencheur.message, code)
    }
}
