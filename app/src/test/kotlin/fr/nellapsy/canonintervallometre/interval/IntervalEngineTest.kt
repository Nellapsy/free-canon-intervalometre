package fr.nellapsy.canonintervallometre.interval

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de l'ordonnancement, sur l'horloge virtuelle de `kotlinx-coroutines-test`.
 *
 * Tout le jalon 3 repose sur eux : la boucle d'intervalle est la seule partie du projet
 * qui se vérifie sans boîtier, et une nuit de quatre heures s'y joue en millisecondes.
 * Les instants attendus sont exprimés en millisecondes depuis le début du test, `runTest`
 * démarrant son horloge à zéro.
 */
class IntervalEngineTest {

    /**
     * Double de [Declencheur] qui journalise l'instant virtuel de chaque vue.
     *
     * @param dureeVueMs temps que met une vue à être acquittée — sert à vérifier qu'une vue
     *   lente ne décale pas les suivantes.
     * @param reussit décide de l'issue à partir du rang de la vue (1 pour la première).
     */
    private class DeclencheurEspion(
        private val horloge: () -> Long,
        private val dureeVueMs: Long = 0,
        private val reussit: (Int) -> Boolean = { true },
    ) : Declencheur {

        val instants = mutableListOf<Long>()

        override suspend fun prendreVue(): Boolean {
            instants += horloge()
            if (dureeVueMs > 0) delay(dureeVueMs)
            return reussit(instants.size)
        }
    }

    @Test
    fun `les vues tombent sur la grille theorique`() = runTest {
        val horloge = { testScheduler.currentTime }
        val espion = DeclencheurEspion(horloge)
        val moteur = IntervalEngine(espion, horloge)

        moteur.executer(
            ReglagesSequence(
                delaiAvantDemarrageMs = 0,
                intervalleMs = 5_000,
                vuesDemandees = 10,
            ),
        )

        assertEquals((0..9).map { it * 5_000L }, espion.instants)
    }

    @Test
    fun `un echec ne compte pas et la sequence se prolonge sur la grille`() = runTest {
        val horloge = { testScheduler.currentTime }
        // La troisième tentative échoue : liaison coupée au moment du créneau.
        val espion = DeclencheurEspion(horloge, reussit = { rang -> rang != 3 })
        val moteur = IntervalEngine(espion, horloge)

        moteur.executer(
            ReglagesSequence(
                delaiAvantDemarrageMs = 0,
                intervalleMs = 5_000,
                vuesDemandees = 10,
            ),
        )

        // Onze tentatives pour dix vues réussies, et pas un instant hors de la grille :
        // l'échec consomme un créneau, il ne décale pas les suivants.
        assertEquals((0..10).map { it * 5_000L }, espion.instants)
    }

    @Test
    fun `une vue lente ne decale pas les suivantes`() = runTest {
        val horloge = { testScheduler.currentTime }
        // Trois secondes pour acquitter, sur un intervalle de cinq : la vue tient dans son
        // créneau, la grille ne doit rien en savoir.
        val espion = DeclencheurEspion(horloge, dureeVueMs = 3_000)
        val moteur = IntervalEngine(espion, horloge)

        moteur.executer(
            ReglagesSequence(
                delaiAvantDemarrageMs = 0,
                intervalleMs = 5_000,
                vuesDemandees = 5,
            ),
        )

        assertEquals((0..4).map { it * 5_000L }, espion.instants)
    }

    @Test
    fun `une vue plus lente que l'intervalle saute des creneaux au lieu de deriver`() = runTest {
        val horloge = { testScheduler.currentTime }
        // Huit secondes pour acquitter, sur un intervalle de cinq : le créneau suivant est
        // déjà passé quand la vue se termine.
        val espion = DeclencheurEspion(horloge, dureeVueMs = 8_000)
        val moteur = IntervalEngine(espion, horloge)

        moteur.executer(
            ReglagesSequence(
                delaiAvantDemarrageMs = 0,
                intervalleMs = 5_000,
                vuesDemandees = 3,
            ),
        )

        // Un créneau sur deux est sauté ; aucune vue ne part entre deux instants de grille.
        // Le contraire — déclencher dès que possible — ferait dériver toute la séquence.
        assertEquals(listOf(0L, 10_000L, 20_000L), espion.instants)
    }

    @Test
    fun `le delai avant demarrage est respecte`() = runTest {
        val horloge = { testScheduler.currentTime }
        val espion = DeclencheurEspion(horloge)
        val moteur = IntervalEngine(espion, horloge)

        moteur.executer(
            ReglagesSequence(
                delaiAvantDemarrageMs = 30_000,
                intervalleMs = 5_000,
                vuesDemandees = 3,
            ),
        )

        // L'origine de la grille est la fin du délai, pas le démarrage.
        assertEquals(listOf(30_000L, 35_000L, 40_000L), espion.instants)
    }

    @Test
    fun `le mode illimite tient quatre heures et ne s'arrete que sur annulation`() = runTest {
        val horloge = { testScheduler.currentTime }
        val espion = DeclencheurEspion(horloge)
        val moteur = IntervalEngine(espion, horloge)

        val sequence = backgroundScope.launch {
            moteur.executer(
                ReglagesSequence(
                    delaiAvantDemarrageMs = 0,
                    intervalleMs = 5_000,
                    vuesDemandees = null,
                ),
            )
        }

        // Quatre heures de séquence, soit la durée de NF2, en quelques millisecondes réelles.
        advanceTimeBy(QUATRE_HEURES_MS + 1)

        assertEquals(2_881, espion.instants.size)
        assertEquals(QUATRE_HEURES_MS, espion.instants.last())
        assertTrue("le mode illimité ne doit pas s'arrêter seul", sequence.isActive)

        sequence.cancel()
        advanceTimeBy(60_000)

        assertEquals("plus aucune vue après l'annulation", 2_881, espion.instants.size)
    }

    @Test
    fun `l'etat expose la progression pendant la sequence`() = runTest {
        val horloge = { testScheduler.currentTime }
        val espion = DeclencheurEspion(horloge)
        val moteur = IntervalEngine(espion, horloge)

        assertEquals(EtatSequence.Inactive, moteur.etat.value)

        backgroundScope.launch {
            moteur.executer(
                ReglagesSequence(
                    delaiAvantDemarrageMs = 0,
                    intervalleMs = 5_000,
                    vuesDemandees = 10,
                ),
            )
        }
        // Vues à 0, 5 000 et 10 000 ; la quatrième est attendue à 15 000.
        advanceTimeBy(12_000)

        assertEquals(
            EtatSequence.EnCours(
                vuesReussies = 3,
                vuesDemandees = 10,
                suspendue = false,
                prochainInstantMs = 15_000,
            ),
            moteur.etat.value,
        )
    }

    @Test
    fun `l'etat signale une sequence suspendue apres un echec`() = runTest {
        val horloge = { testScheduler.currentTime }
        // Les deuxième et troisième tentatives échouent : liaison coupée sur deux créneaux.
        val espion = DeclencheurEspion(horloge, reussit = { rang -> rang != 2 && rang != 3 })
        val moteur = IntervalEngine(espion, horloge)

        backgroundScope.launch {
            moteur.executer(
                ReglagesSequence(
                    delaiAvantDemarrageMs = 0,
                    intervalleMs = 5_000,
                    vuesDemandees = 5,
                ),
            )
        }

        // Vue réussie à 0, échouée à 5 000.
        advanceTimeBy(7_000)
        assertEquals(
            EtatSequence.EnCours(
                vuesReussies = 1,
                vuesDemandees = 5,
                prochainInstantMs = 10_000,
                suspendue = true,
            ),
            moteur.etat.value,
        )

        // Échouée à 10 000, puis réussie à 15 000 : la suspension se lève d'elle-même.
        advanceTimeBy(10_000)
        assertEquals(
            EtatSequence.EnCours(
                vuesReussies = 2,
                vuesDemandees = 5,
                prochainInstantMs = 20_000,
                suspendue = false,
            ),
            moteur.etat.value,
        )
    }

    @Test
    fun `une sequence menee a son terme est signalee complete`() = runTest {
        val horloge = { testScheduler.currentTime }
        val espion = DeclencheurEspion(horloge)
        val moteur = IntervalEngine(espion, horloge)

        moteur.executer(
            ReglagesSequence(
                delaiAvantDemarrageMs = 0,
                intervalleMs = 5_000,
                vuesDemandees = 3,
            ),
        )

        assertEquals(
            EtatSequence.Terminee(vuesReussies = 3, complete = true),
            moteur.etat.value,
        )
    }

    @Test
    fun `une sequence annulee est signalee incomplete`() = runTest {
        val horloge = { testScheduler.currentTime }
        val espion = DeclencheurEspion(horloge)
        val moteur = IntervalEngine(espion, horloge)

        val sequence = backgroundScope.launch {
            moteur.executer(
                ReglagesSequence(
                    delaiAvantDemarrageMs = 0,
                    intervalleMs = 5_000,
                    vuesDemandees = 10,
                ),
            )
        }
        advanceTimeBy(12_000)

        sequence.cancel()
        runCurrent()

        // Un arrêt manuel (F10) laisse une trace exploitable : trois vues, séquence
        // incomplète. L'écran doit pouvoir le dire sans le déduire.
        assertEquals(
            EtatSequence.Terminee(vuesReussies = 3, complete = false),
            moteur.etat.value,
        )
    }

    private companion object {
        const val QUATRE_HEURES_MS = 4L * 60 * 60 * 1_000
    }
}
