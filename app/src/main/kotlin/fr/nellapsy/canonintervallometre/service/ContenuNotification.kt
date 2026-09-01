package fr.nellapsy.canonintervallometre.service

import fr.nellapsy.canonintervallometre.ble.EtatLiaison
import fr.nellapsy.canonintervallometre.interval.EtatSequence

/**
 * Ce que la notification doit dire, indépendamment de sa mise en forme (F7).
 *
 * Le calcul vit ici et non dans [ShutterService] pour une seule raison : c'est la seule
 * partie du jalon 4 qui se vérifie sans appareil ni boîtier. La mise en forme, elle, reste
 * au service, qui seul a accès aux ressources de chaînes.
 */
sealed interface ContenuNotification {

    /**
     * Séquence pas encore commencée. Le service doit afficher sa notification dès
     * `onStartCommand`, avant que le moteur n'ait publié le moindre état.
     */
    data object Demarrage : ContenuNotification

    /**
     * @param finEstimeeMurMs instant de la dernière vue sur l'**horloge murale**, ou `null`
     *   quand il n'est pas connaissable : mode illimité, créneau déjà manqué, liaison
     *   coupée. Une heure de fin annoncée est une promesse ; mieux vaut n'en faire aucune
     *   que d'en faire une fausse.
     * @param liaisonRompue état de la liaison **maintenant**, à ne pas confondre avec
     *   `EtatSequence.EnCours.suspendue`, qui porte sur la dernière vue tentée.
     */
    data class EnCours(
        val vuesReussies: Int,
        val vuesDemandees: Int?,
        val liaisonRompue: Boolean,
        val finEstimeeMurMs: Long?,
    ) : ContenuNotification

    data class Terminee(val vuesReussies: Int, val complete: Boolean) : ContenuNotification
}

/**
 * Assemble le contenu de la notification à partir des deux états observés par le service.
 *
 * Les deux horloges sont passées ensemble et relues à chaque appel : le moteur planifie sur
 * une horloge monotone, l'utilisateur lit une heure murale, et l'écart entre les deux
 * change dès qu'Android se remet à l'heure. Le mémoriser afficherait une heure fausse le
 * reste de la nuit.
 */
fun contenuNotification(
    sequence: EtatSequence,
    liaison: EtatLiaison,
    intervalleMs: Long,
    maintenantMonotoneMs: Long,
    maintenantMurMs: Long,
): ContenuNotification = when (sequence) {
    EtatSequence.Inactive -> ContenuNotification.Demarrage

    is EtatSequence.Terminee ->
        ContenuNotification.Terminee(sequence.vuesReussies, sequence.complete)

    is EtatSequence.EnCours -> {
        val rompue = liaison !is EtatLiaison.Prete

        // Un créneau manqué prolonge la séquence d'un nombre de créneaux qu'on ne connaît
        // pas d'avance — « N vues » compte les vues réussies, décision du jalon 3. Toute
        // heure de fin calculée dans ces conditions serait inventée.
        val fin = if (sequence.vuesDemandees == null || sequence.suspendue || rompue) {
            null
        } else {
            val restantes = (sequence.vuesDemandees - sequence.vuesReussies).coerceAtLeast(1)
            val derniereMonotone =
                sequence.prochainInstantMs + (restantes - 1) * intervalleMs
            maintenantMurMs + (derniereMonotone - maintenantMonotoneMs)
        }

        ContenuNotification.EnCours(
            vuesReussies = sequence.vuesReussies,
            vuesDemandees = sequence.vuesDemandees,
            liaisonRompue = rompue,
            finEstimeeMurMs = fin,
        )
    }
}
