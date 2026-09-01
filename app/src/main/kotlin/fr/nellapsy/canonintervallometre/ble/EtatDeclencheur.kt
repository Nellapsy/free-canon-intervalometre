package fr.nellapsy.canonintervallometre.ble

/**
 * Issue du dernier déclenchement demandé.
 *
 * Séparée de [EtatLiaison] parce que les deux vivent indépendamment : la liaison peut être
 * [EtatLiaison.Prete] et la dernière écriture avoir échoué, ou l'inverse — une reprise en
 * cours n'efface pas la vue prise il y a dix secondes.
 *
 * Elle existe pour que l'écran ne mente pas. Un déclenchement qui n'est pas parti doit se
 * voir : c'est le point de vigilance du jalon 2.
 */
sealed interface EtatDeclencheur {

    /** Aucun déclenchement demandé depuis le lancement. */
    data object Repos : EtatDeclencheur

    /** Écriture en vol. Le bouton reste inerte tant qu'elle dure. */
    data object EnCours : EtatDeclencheur

    /**
     * Écriture acquittée par le boîtier.
     *
     * @param vues nombre de commandes de déclenchement acquittées depuis le lancement.
     * @param instant horodatage sur l'horloge murale ([System.currentTimeMillis]), destiné
     *   à l'affichage — surtout pas à de l'ordonnancement, qui exige une horloge monotone.
     *
     * « Acquittée » ne veut pas dire « photo prise » : le boîtier acquitte aussi quand son
     * mode d'acquisition n'est pas sur télécommande. C'est le symptôme relevé au jalon 0,
     * et l'écran d'aide du jalon 6 devra le rappeler.
     */
    data class Reussi(val vues: Int, val instant: Long) : EtatDeclencheur

    /** L'écriture n'est pas partie, ou le boîtier l'a refusée. */
    data class Echec(val message: String, val codeGatt: Int? = null) : EtatDeclencheur
}
