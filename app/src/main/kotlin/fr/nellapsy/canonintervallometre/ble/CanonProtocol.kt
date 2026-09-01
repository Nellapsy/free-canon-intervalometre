package fr.nellapsy.canonintervallometre.ble

import java.util.UUID

/**
 * Constantes du dialogue BLE de la télécommande Canon BR-E1.
 *
 * Chaque valeur porte sa source et son statut de vérification sur EOS R100. Les jeux de
 * commandes varient d'un modèle Canon à l'autre : une valeur non vérifiée sur R100 doit
 * être signalée comme telle. Relevés complets : doc/jalon-0-protocole.md.
 *
 * Sources : gkoh/furble (lib/furble/CanonEOSRemote.h),
 * maxmacstn/ESP32-Canon-BLE-Remote (src/CanonBLERemote.h), ArthurFDLR/BR-M5.
 */
object CanonProtocol {

    /**
     * Service principal de la télécommande. C'est aussi l'UUID annoncé dans l'advertising,
     * donc le filtre de scan.
     *
     * Source : furble et maxmacstn. Vérifié sur R100 le 31 août 2026.
     */
    val SERVICE: UUID = UUID.fromString("00050000-0000-1000-0000-d8492fffa821")

    /**
     * Identification : on y écrit [PREFIXE_IDENTIFICATION] suivi du nom de la télécommande.
     * Le boîtier affiche alors ce nom et l'enregistre comme télécommande connue.
     *
     * Source : furble (ID_CHR_UUID), maxmacstn (PAIRING_SERVICE).
     * Vérifié sur R100 le 31 août 2026 — le boîtier a affiché « Intervallo ».
     */
    val CARACTERISTIQUE_IDENTIFICATION: UUID =
        UUID.fromString("00050002-0000-1000-0000-d8492fffa821")

    /**
     * Contrôle : déclencheur et autofocus.
     *
     * Source : furble (CTRL_CHR_UUID), maxmacstn (SHUTTER_CONTROL_SERVICE).
     * Vérifié sur R100 le 31 août 2026.
     */
    val CARACTERISTIQUE_CONTROLE: UUID =
        UUID.fromString("00050003-0000-1000-0000-d8492fffa821")

    /**
     * Premier octet de la trame d'identification.
     *
     * Source : maxmacstn. Vérifié sur R100 le 31 août 2026.
     */
    const val PREFIXE_IDENTIFICATION: Byte = 0x03

    /**
     * « Le bouton du déclencheur a été pressé ». À écrire sur [CARACTERISTIQUE_CONTROLE].
     *
     * Ce n'est **pas** un appui maintenu : le boîtier décide de la suite selon son mode.
     * À vitesse fixe, une écriture produit une photo complète, exposition comprise. En
     * BULB, une écriture ouvre l'obturateur et la suivante le referme — une bascule, dont
     * l'état vit dans le boîtier. D'où l'absence de constantes nommées PRESS / RELEASE :
     * elles décriraient un mécanisme qui n'existe pas sur ce boîtier.
     *
     * Calcul d'origine : SHUTTER (0x80) | CTRL (0x0C), chez furble comme chez maxmacstn.
     * Vérifié sur R100 le 31 août 2026, à vitesse fixe et en BULB.
     */
    const val DECLENCHEMENT: Byte = 0x8C.toByte()

    /**
     * Nom sous lequel l'application se présente au boîtier.
     *
     * Sans accent, volontairement : la trame est en ASCII (voir [trameIdentification]).
     */
    const val NOM_TELECOMMANDE: String = "Intervallo"

    /**
     * Longueur utile d'une écriture GATT sans négociation de MTU : le MTU par défaut vaut
     * 23 octets, dont 3 d'en-tête ATT.
     */
    private const val OCTETS_UTILES_PAR_ECRITURE = 20

    /**
     * Nombre maximal de caractères du nom, une fois le préfixe déduit.
     */
    const val LONGUEUR_NOM_MAX = OCTETS_UTILES_PAR_ECRITURE - 1

    /**
     * Construit la trame à écrire sur [CARACTERISTIQUE_IDENTIFICATION] :
     * [PREFIXE_IDENTIFICATION] suivi de [nom] en ASCII.
     *
     * @throws IllegalArgumentException si le nom est vide, contient un caractère non-ASCII
     *   ou dépasse [LONGUEUR_NOM_MAX].
     */
    fun trameIdentification(nom: String): ByteArray {
        require(nom.isNotEmpty()) { "Le nom de la télécommande ne peut pas être vide." }
        require(nom.length <= LONGUEUR_NOM_MAX) {
            "Le nom « $nom » dépasse $LONGUEUR_NOM_MAX caractères : la trame ne tiendrait " +
                "pas dans une écriture GATT au MTU par défaut."
        }
        require(nom.all { it.code in 0x20..0x7E }) {
            "Le nom « $nom » contient un caractère non-ASCII imprimable ; le boîtier " +
                "l'afficherait de travers."
        }

        return byteArrayOf(PREFIXE_IDENTIFICATION) + nom.toByteArray(Charsets.US_ASCII)
    }

    // Pas de constante pour l'octet 0x0C (« CTRL » seul, le « relâchement » des dépôts de
    // référence). Vérifié sans effet observable sur R100 le 31 août 2026 : il ne referme
    // pas une pose longue et n'est pas nécessaire entre deux vues. L'inscrire ici
    // inviterait à s'en servir. Une seule écriture GATT par vue suffit.
}
