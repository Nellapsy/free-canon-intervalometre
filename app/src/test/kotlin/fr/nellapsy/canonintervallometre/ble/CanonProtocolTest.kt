package fr.nellapsy.canonintervallometre.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Seule partie du protocole vérifiable sans radio ni boîtier : la construction de la
 * trame d'identification. Les octets attendus ici sont ceux réellement écrits sur le
 * R100 le 31 août 2026 (voir doc/jalon-0-protocole.md).
 */
class CanonProtocolTest {

    @Test
    fun `la trame est le préfixe 0x03 suivi du nom en ASCII`() {
        // Valeur relevée telle quelle dans nRF Connect le 31 août 2026 :
        // 03 49 6E 74 65 72 76 61 6C 6C 6F
        val attendu = byteArrayOf(
            0x03,
            0x49, 0x6E, 0x74, 0x65, 0x72, 0x76, 0x61, 0x6C, 0x6C, 0x6F,
        )

        assertArrayEquals(attendu, CanonProtocol.trameIdentification("Intervallo"))
    }

    @Test
    fun `le nom par défaut produit une trame valide`() {
        val trame = CanonProtocol.trameIdentification(CanonProtocol.NOM_TELECOMMANDE)

        assertEquals(CanonProtocol.PREFIXE_IDENTIFICATION, trame[0])
        assertEquals(CanonProtocol.NOM_TELECOMMANDE.length + 1, trame.size)
    }

    @Test
    fun `un nom non-ASCII est refusé`() {
        // Un accent produirait deux octets UTF-8 que le boîtier afficherait de travers.
        assertThrows(IllegalArgumentException::class.java) {
            CanonProtocol.trameIdentification("Intervallomètre")
        }
    }

    @Test
    fun `un nom vide est refusé`() {
        assertThrows(IllegalArgumentException::class.java) {
            CanonProtocol.trameIdentification("")
        }
    }

    @Test
    fun `un nom trop long pour une écriture GATT par défaut est refusé`() {
        // MTU par défaut 23 octets : 20 octets utiles, dont 1 pour le préfixe.
        val nomDe20Caracteres = "A".repeat(20)

        assertThrows(IllegalArgumentException::class.java) {
            CanonProtocol.trameIdentification(nomDe20Caracteres)
        }
    }

    @Test
    fun `un nom de 19 caractères passe`() {
        val nomDe19Caracteres = "A".repeat(19)

        assertEquals(20, CanonProtocol.trameIdentification(nomDe19Caracteres).size)
    }
}
