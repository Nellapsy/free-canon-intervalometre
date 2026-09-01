package fr.nellapsy.canonintervallometre.ui

import fr.nellapsy.canonintervallometre.interval.ReglagesSequence

/**
 * Intervalle minimum accepté, en secondes.
 *
 * `BleRemote` observe un délai de garde d'une seconde après chaque vue, pendant lequel il
 * refuse d'envoyer quoi que ce soit. Descendre sous deux secondes ferait perdre des
 * créneaux sans que rien ne l'explique à l'écran.
 */
const val INTERVALLE_MINIMUM_S = 2

/**
 * Traduit la saisie de l'écran en [ReglagesSequence], ou rend `null` si elle n'est pas
 * exploitable — le bouton « Démarrer » s'éteint alors.
 *
 * Aucune correction silencieuse : une valeur hors limites est refusée, jamais ramenée dans
 * les clous. Une séquence de nuit lancée avec un intervalle qui n'est pas celui affiché ne
 * se découvrirait qu'au matin.
 *
 * Seule tolérance, parce qu'elle ne peut pas surprendre : un délai avant démarrage laissé
 * vide vaut zéro.
 */
fun reglagesDepuisSaisie(
    delai: String,
    intervalle: String,
    vues: String,
    illimite: Boolean,
): ReglagesSequence? {
    val delaiS = if (delai.isBlank()) 0 else delai.trim().toIntOrNull() ?: return null
    if (delaiS < 0) return null

    val intervalleS = intervalle.trim().toIntOrNull() ?: return null
    if (intervalleS < INTERVALLE_MINIMUM_S) return null

    val vuesDemandees = when {
        illimite -> null
        else -> vues.trim().toIntOrNull()?.takeIf { it >= 1 } ?: return null
    }

    return ReglagesSequence(
        delaiAvantDemarrageMs = delaiS * 1_000L,
        intervalleMs = intervalleS * 1_000L,
        vuesDemandees = vuesDemandees,
    )
}
