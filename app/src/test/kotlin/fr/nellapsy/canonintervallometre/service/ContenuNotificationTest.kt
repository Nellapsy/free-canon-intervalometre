package fr.nellapsy.canonintervallometre.service

import fr.nellapsy.canonintervallometre.ble.EtatLiaison
import fr.nellapsy.canonintervallometre.interval.EtatSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * La notification est la seule chose que l'utilisateur regarde pendant une séquence de
 * nuit : ce qu'elle annonce doit être vrai. Le calcul qui la remplit est donc extrait du
 * service et vérifié ici, où l'horloge est un simple entier.
 *
 * Le point délicat est la conversion : le moteur planifie sur une horloge **monotone**,
 * l'heure de fin s'affiche sur l'horloge **murale**. Les deux ne coïncident pas, et le
 * décalage se recalcule à chaque rendu — le mémoriser reviendrait à afficher une heure
 * fausse après une mise à l'heure réseau.
 */
class ContenuNotificationTest {

    /** Horloge monotone arbitraire ; seul l'écart avec les instants du moteur compte. */
    private val monotone = 500_000L

    /** 1er septembre 2026, 22:00:00 UTC, en millisecondes depuis l'époque. */
    private val murale = 1_788_386_400_000L

    private fun contenu(
        sequence: EtatSequence,
        liaison: EtatLiaison = EtatLiaison.Prete,
        intervalleMs: Long = 30_000,
    ) = contenuNotification(sequence, liaison, intervalleMs, monotone, murale)

    @Test
    fun `avant la premiere vue la notification annonce le demarrage`() {
        // Le service doit afficher une notification dès `onStartCommand`, bien avant que le
        // moteur n'ait publié quoi que ce soit.
        assertEquals(ContenuNotification.Demarrage, contenu(EtatSequence.Inactive))
    }

    @Test
    fun `l'heure de fin est celle de la derniere vue, sur l'horloge murale`() {
        // 3 vues faites sur 5, prochaine dans 10 s : il reste 2 vues, la dernière tombe
        // 30 s après la prochaine. Fin dans 40 s.
        val resultat = contenu(
            EtatSequence.EnCours(
                vuesReussies = 3,
                vuesDemandees = 5,
                prochainInstantMs = monotone + 10_000,
                suspendue = false,
            ),
        )

        assertEquals(
            ContenuNotification.EnCours(
                vuesReussies = 3,
                vuesDemandees = 5,
                liaisonRompue = false,
                finEstimeeMurMs = murale + 40_000,
            ),
            resultat,
        )
    }

    @Test
    fun `la derniere vue restante fixe la fin a l'instant de la prochaine`() {
        val resultat = contenu(
            EtatSequence.EnCours(
                vuesReussies = 9,
                vuesDemandees = 10,
                prochainInstantMs = monotone + 7_000,
                suspendue = false,
            ),
        )

        assertEquals(murale + 7_000, (resultat as ContenuNotification.EnCours).finEstimeeMurMs)
    }

    @Test
    fun `le mode illimite n'annonce aucune heure de fin`() {
        val resultat = contenu(
            EtatSequence.EnCours(
                vuesReussies = 42,
                vuesDemandees = null,
                prochainInstantMs = monotone + 10_000,
                suspendue = false,
            ),
        )

        assertNull((resultat as ContenuNotification.EnCours).finEstimeeMurMs)
    }

    @Test
    fun `une sequence suspendue n'annonce plus d'heure de fin`() {
        // Un créneau manqué prolonge la séquence d'autant, et on ne sait pas de combien :
        // une heure de fin serait une invention. Mieux vaut ne rien annoncer.
        val resultat = contenu(
            EtatSequence.EnCours(
                vuesReussies = 3,
                vuesDemandees = 5,
                prochainInstantMs = monotone + 10_000,
                suspendue = true,
            ),
        )

        assertNull((resultat as ContenuNotification.EnCours).finEstimeeMurMs)
    }

    @Test
    fun `une liaison perdue est signalee et efface l'heure de fin`() {
        val resultat = contenu(
            EtatSequence.EnCours(
                vuesReussies = 3,
                vuesDemandees = 5,
                prochainInstantMs = monotone + 10_000,
                suspendue = false,
            ),
            liaison = EtatLiaison.Reconnexion(tentative = 2, total = 3),
        )

        assertEquals(
            ContenuNotification.EnCours(
                vuesReussies = 3,
                vuesDemandees = 5,
                liaisonRompue = true,
                finEstimeeMurMs = null,
            ),
            resultat,
        )
    }

    @Test
    fun `une sequence suspendue dont la liaison est revenue ne dit plus la liaison rompue`() {
        // Les deux champs disent deux choses différentes : `suspendue` porte sur la dernière
        // vue, la liaison sur maintenant. Annoncer « liaison perdue » alors qu'elle est
        // revenue serait une information périmée.
        val resultat = contenu(
            EtatSequence.EnCours(
                vuesReussies = 3,
                vuesDemandees = 5,
                prochainInstantMs = monotone + 10_000,
                suspendue = true,
            ),
            liaison = EtatLiaison.Prete,
        )

        assertEquals(false, (resultat as ContenuNotification.EnCours).liaisonRompue)
    }

    @Test
    fun `une sequence finie reprend son compte et son issue`() {
        assertEquals(
            ContenuNotification.Terminee(vuesReussies = 240, complete = true),
            contenu(EtatSequence.Terminee(vuesReussies = 240, complete = true)),
        )
    }
}
