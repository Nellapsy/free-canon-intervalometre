package fr.nellapsy.canonintervallometre.interval

/**
 * Avancement de la séquence, tel que l'interface le voit.
 *
 * Comme `EtatLiaison`, il existe pour que l'écran ne mente pas : il rapporte des vues
 * **acquittées par le boîtier**, pas des commandes envoyées.
 */
sealed interface EtatSequence {

    /** Aucune séquence en cours. */
    data object Inactive : EtatSequence

    /**
     * Séquence en cours.
     *
     * @param vuesReussies vues acquittées depuis le début de la séquence.
     * @param vuesDemandees objectif, ou `null` en mode illimité.
     * @param prochainInstantMs instant de la prochaine vue, sur l'horloge monotone du
     *   moteur — à convertir en compte à rebours pour l'affichage, jamais en heure murale.
     * @param suspendue la dernière tentative a échoué : la liaison est probablement coupée,
     *   la séquence continue de viser ses créneaux sans rien produire. C'est le seul moyen
     *   qu'a l'écran de distinguer « ça avance » de « ça tourne à vide ».
     */
    data class EnCours(
        val vuesReussies: Int,
        val vuesDemandees: Int?,
        val prochainInstantMs: Long,
        val suspendue: Boolean,
    ) : EtatSequence

    /**
     * Séquence finie.
     *
     * @param complete vrai si le nombre de vues demandé a été atteint ; faux si la séquence
     *   a été arrêtée avant (F10), ce qui est aussi le cas normal du mode illimité.
     */
    data class Terminee(val vuesReussies: Int, val complete: Boolean) : EtatSequence
}
