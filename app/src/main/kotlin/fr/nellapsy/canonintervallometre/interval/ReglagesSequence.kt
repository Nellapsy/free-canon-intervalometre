package fr.nellapsy.canonintervallometre.interval

/**
 * Paramètres d'une séquence (F4).
 *
 * @param delaiAvantDemarrageMs attente avant la première vue ; 0 pour démarrer tout de suite.
 * @param intervalleMs écart entre deux instants de la grille.
 * @param vuesDemandees nombre de vues **réussies** attendues, ou `null` pour le mode
 *   illimité, qui ne s'arrête que sur demande.
 */
data class ReglagesSequence(
    val delaiAvantDemarrageMs: Long,
    val intervalleMs: Long,
    val vuesDemandees: Int?,
)
