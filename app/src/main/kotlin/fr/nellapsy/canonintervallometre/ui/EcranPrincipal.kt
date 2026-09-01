package fr.nellapsy.canonintervallometre.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.nellapsy.canonintervallometre.R
import fr.nellapsy.canonintervallometre.ble.BleRemote
import fr.nellapsy.canonintervallometre.ble.EtatDeclencheur
import fr.nellapsy.canonintervallometre.ble.EtatLiaison
import fr.nellapsy.canonintervallometre.interval.EtatSequence
import fr.nellapsy.canonintervallometre.interval.ReglagesSequence
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.text.DateFormat
import java.util.Date

/**
 * Écran unique : liaison, déclenchement manuel, séquence.
 *
 * Il ne détient rien. La liaison et la séquence vivent au niveau de l'application, et cet
 * écran ne fait que les regarder et les commander — c'est ce qui permettra au jalon 4 de
 * déplacer la séquence dans un service sans y toucher.
 */
@Composable
fun EcranPrincipal(
    liaison: BleRemote,
    etatSequence: StateFlow<EtatSequence>,
    onDemarrer: (ReglagesSequence) -> Unit,
    onArreter: () -> Unit,
) {
    val contexte = LocalContext.current
    val etat by liaison.etat.collectAsStateWithLifecycle()
    val declencheur by liaison.etatDeclencheur.collectAsStateWithLifecycle()
    val pretADeclencher by liaison.pretADeclencher.collectAsStateWithLifecycle()
    val sequence by etatSequence.collectAsStateWithLifecycle()

    val demandePermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { resultats ->
        if (resultats.values.all { it }) liaison.connecter()
    }

    // Réglages retenus le temps de la demande de permission : la séquence démarre après la
    // réponse, quelle qu'elle soit. Voir [demandeNotifications].
    var enAttenteDeNotifications by remember { mutableStateOf<ReglagesSequence?>(null) }
    val demandeNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Le résultat est délibérément ignoré : sans la permission le service tourne quand
        // même, seule la notification reste invisible. Refuser de démarrer punirait
        // l'utilisateur pour une permission cosmétique.
        enAttenteDeNotifications?.let(onDemarrer)
        enAttenteDeNotifications = null
    }

    // Reconnexion automatique au lancement : c'est ce que vérifie la recette F1, relancer
    // l'application ne doit rien demander. Inutile de tester les permissions ici, la
    // liaison le fait elle-même et publie l'état correspondant.
    LaunchedEffect(Unit) { liaison.connecter() }

    // Battement d'une demi-seconde, le temps d'une séquence seulement : le compte à rebours
    // est la seule chose de cet écran qui change sans qu'un état ne change.
    var maintenant by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val enSequence = sequence is EtatSequence.EnCours
    LaunchedEffect(enSequence) {
        while (enSequence) {
            maintenant = SystemClock.elapsedRealtime()
            delay(500)
        }
    }

    // Relu après chaque retour des réglages système : Android ne notifie pas ce changement,
    // et un bouton qui resterait affiché après coup laisserait croire à un échec.
    var relectureBatterie by remember { mutableIntStateOf(0) }
    val batterieLibre = remember(relectureBatterie) { batterieLibre(contexte) }
    val demandeBatterie = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { relectureBatterie++ }

    var delai by rememberSaveable { mutableStateOf("0") }
    var intervalle by rememberSaveable { mutableStateOf("5") }
    var vues by rememberSaveable { mutableStateOf("10") }
    var illimite by rememberSaveable { mutableStateOf(false) }
    val reglages = reglagesDepuisSaisie(delai, intervalle, vues, illimite)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
        TextButton(
            // Seule porte vers le scan une fois un boîtier connu : depuis le jalon 4, plus
            // rien n'y revient tout seul. Elle sert au boîtier remplacé ou réinitialisé,
            // dont l'adresse mémorisée ne désigne plus rien.
            onClick = { liaison.oublierBoitier() },
            enabled = !enSequence,
        ) {
            Text(stringResource(R.string.bouton_oublier_boitier))
        }

        Button(
            // Actif seulement sur une liaison réellement utilisable, et jamais pendant la
            // vue ni son délai de garde : le boîtier ignorerait la commande sans le dire,
            // et le compteur afficherait une vue qui n'existe pas. Inerte aussi pendant une
            // séquence, qui tient sa propre grille.
            onClick = { liaison.declencher() },
            enabled = etat is EtatLiaison.Prete && pretADeclencher && !enSequence,
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

        HorizontalDivider()

        Text(
            text = stringResource(R.string.sequence_titre),
            style = MaterialTheme.typography.titleMedium,
        )
        OutlinedTextField(
            value = delai,
            onValueChange = { delai = it },
            label = { Text(stringResource(R.string.sequence_champ_delai)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            enabled = !enSequence,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = intervalle,
            onValueChange = { intervalle = it },
            label = { Text(stringResource(R.string.sequence_champ_intervalle)) },
            supportingText = {
                Text(stringResource(R.string.sequence_intervalle_minimum, INTERVALLE_MINIMUM_S))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            enabled = !enSequence,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = vues,
            onValueChange = { vues = it },
            label = { Text(stringResource(R.string.sequence_champ_vues)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            enabled = !enSequence && !illimite,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Switch(checked = illimite, onCheckedChange = { illimite = it }, enabled = !enSequence)
            Text(stringResource(R.string.sequence_illimite))
        }

        if (enSequence) {
            Button(onClick = onArreter) {
                Text(stringResource(R.string.bouton_arreter))
            }
        } else {
            Button(
                // Éteint tant que la saisie n'est pas exploitable ou que la liaison n'est
                // pas prête : une séquence lancée dans le vide ne se découvrirait qu'au
                // matin.
                onClick = {
                    reglages?.let { valides ->
                        // La permission de notification se demande ici, et non au lancement :
                        // c'est le seul moment où l'utilisateur peut comprendre à quoi elle
                        // sert. Une fois accordée ou refusée, Android ne la redemande plus.
                        if (notificationsADemander(contexte)) {
                            enAttenteDeNotifications = valides
                            demandeNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            onDemarrer(valides)
                        }
                    }
                },
                enabled = reglages != null && etat is EtatLiaison.Prete,
            ) {
                Text(stringResource(R.string.bouton_demarrer))
            }
        }

        if (!batterieLibre) {
            // Proposé et non imposé : Android refuse cette exclusion tant que l'utilisateur
            // n'y consent pas explicitement. Le bouton disparaît une fois l'exclusion
            // acquise, plutôt que de rester là sans rien faire.
            Text(
                text = stringResource(R.string.batterie_explication),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = { demandeBatterie.launch(intentionBatterie(contexte)) }) {
                Text(stringResource(R.string.bouton_batterie))
            }
        }
        libelleSequence(sequence, maintenant)?.let { libelle ->
            Text(
                text = libelle,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Vrai si `POST_NOTIFICATIONS` doit encore être demandée. Toujours faux avant API 33, où la
 * permission n'existe pas et où les notifications sont accordées d'office.
 */
private fun notificationsADemander(contexte: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(contexte, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

/** Vrai si l'application est déjà exclue de l'optimisation batterie. */
private fun batterieLibre(contexte: Context): Boolean =
    contexte.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(contexte.packageName) ?: false

/**
 * Ouvre la demande d'exclusion pour **cette** application, et non la liste générale : sans
 * l'URI `package:`, Android affiche les réglages système et l'utilisateur doit y retrouver
 * l'application lui-même.
 */
private fun intentionBatterie(contexte: Context): Intent = Intent(
    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
    Uri.fromParts("package", contexte.packageName, null),
)

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
    is EtatLiaison.Reconnexion -> when (val total = etat.total) {
        // Pas de plafond pendant une séquence : annoncer « 7 sur 3 » serait absurde, et un
        // total inventé promettrait un abandon qui ne viendra pas.
        null -> stringResource(R.string.etat_reconnexion_sans_plafond, etat.tentative)
        else -> stringResource(R.string.etat_reconnexion, etat.tentative, total)
    }

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

/**
 * Avancement de la séquence. Le compte à rebours est arrondi au-dessus : afficher « dans
 * 0 s » pendant une seconde entière donnerait l'impression d'une séquence bloquée.
 */
@Composable
private fun libelleSequence(sequence: EtatSequence, maintenantMs: Long): String? =
    when (sequence) {
        EtatSequence.Inactive -> null

        is EtatSequence.EnCours -> {
            val restant = (sequence.prochainInstantMs - maintenantMs).coerceAtLeast(0)
            val restantS = (restant + 999) / 1_000
            when {
                // La suspension prime sur le compte à rebours : pendant une coupure, un
                // rebours qui continue de tourner laisserait croire que ça avance.
                sequence.suspendue ->
                    stringResource(R.string.sequence_suspendue, sequence.vuesReussies)

                sequence.vuesDemandees == null -> stringResource(
                    R.string.sequence_attente_illimitee,
                    sequence.vuesReussies,
                    restantS,
                )

                else -> stringResource(
                    R.string.sequence_attente,
                    sequence.vuesReussies,
                    sequence.vuesDemandees,
                    restantS,
                )
            }
        }

        is EtatSequence.Terminee -> when {
            sequence.complete -> stringResource(R.string.sequence_terminee, sequence.vuesReussies)
            else -> stringResource(R.string.sequence_arretee, sequence.vuesReussies)
        }
    }
