package fr.nellapsy.canonintervallometre.ui

import fr.nellapsy.canonintervallometre.interval.ReglagesSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * La saisie est le seul endroit de l'interface qui soit de la logique pure, donc le seul
 * qui se vérifie sans appareil. Une saisie invalide doit rendre `null` — le bouton
 * « Démarrer » s'éteint — plutôt que d'être corrigée en douce : une séquence lancée avec un
 * intervalle qui n'est pas celui affiché ne se découvre qu'au matin.
 */
class SaisieSequenceTest {

    @Test
    fun `une saisie valide donne des reglages en millisecondes`() {
        assertEquals(
            ReglagesSequence(
                delaiAvantDemarrageMs = 30_000,
                intervalleMs = 5_000,
                vuesDemandees = 120,
            ),
            reglagesDepuisSaisie(delai = "30", intervalle = "5", vues = "120", illimite = false),
        )
    }

    @Test
    fun `un intervalle sous le minimum est refuse`() {
        // Le délai de garde de BleRemote dure une seconde : sous deux secondes, une vue sur
        // deux serait refusée sans que rien ne le dise.
        assertNull(reglagesDepuisSaisie(delai = "0", intervalle = "1", vues = "10", illimite = false))
    }

    @Test
    fun `le mode illimite ignore le nombre de vues`() {
        assertEquals(
            ReglagesSequence(
                delaiAvantDemarrageMs = 0,
                intervalleMs = 5_000,
                vuesDemandees = null,
            ),
            reglagesDepuisSaisie(delai = "0", intervalle = "5", vues = "", illimite = true),
        )
    }

    @Test
    fun `un champ non numerique est refuse`() {
        assertNull(reglagesDepuisSaisie(delai = "0", intervalle = "cinq", vues = "10", illimite = false))
    }

    @Test
    fun `un delai vide vaut zero`() {
        assertEquals(
            ReglagesSequence(
                delaiAvantDemarrageMs = 0,
                intervalleMs = 5_000,
                vuesDemandees = 10,
            ),
            reglagesDepuisSaisie(delai = "", intervalle = "5", vues = "10", illimite = false),
        )
    }

    @Test
    fun `zero vue est refuse hors mode illimite`() {
        assertNull(reglagesDepuisSaisie(delai = "0", intervalle = "5", vues = "0", illimite = false))
    }
}
