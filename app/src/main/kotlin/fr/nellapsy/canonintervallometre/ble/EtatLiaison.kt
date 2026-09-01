package fr.nellapsy.canonintervallometre.ble

/**
 * État de la liaison avec le boîtier, tel que l'interface le voit.
 *
 * C'est le contrat que consommeront les jalons suivants : l'interface ne doit jamais
 * laisser croire à une liaison utilisable quand elle ne l'est pas.
 */
sealed interface EtatLiaison {

    /** L'appareil n'a pas de Bluetooth Low Energy. Rien n'est possible. */
    data object BluetoothIndisponible : EtatLiaison

    /** Les permissions Bluetooth n'ont pas été accordées. */
    data object PermissionsManquantes : EtatLiaison

    /** Le Bluetooth est éteint. */
    data object BluetoothEteint : EtatLiaison

    /**
     * Les services de localisation sont coupés, sous API 31 uniquement.
     *
     * Cas à part parce qu'il est silencieux : avant Android 12, un scan lancé sans
     * localisation active ne renvoie **rien et aucune erreur**. Sans cet état, l'écran
     * afficherait « recherche en cours » indéfiniment sans rien chercher.
     */
    data object LocalisationDesactivee : EtatLiaison

    /** Prêt à démarrer, rien en cours. */
    data object Inactif : EtatLiaison

    /** Scan en cours : le boîtier doit être en mode appairage pour être vu. */
    data object Recherche : EtatLiaison

    /** Connexion GATT et découverte des services en cours. */
    data object Connexion : EtatLiaison

    /** Appairage en cours ; Android peut demander confirmation à l'utilisateur. */
    data object Appairage : EtatLiaison

    /** Écriture de la trame d'identification sur le boîtier. */
    data object Identification : EtatLiaison

    /** Liaison établie, appairée et identifiée : les commandes peuvent partir. */
    data object Prete : EtatLiaison

    /** Liaison perdue, reprise en cours. */
    data class Reconnexion(val tentative: Int, val total: Int) : EtatLiaison

    /** Échec définitif de ce cycle de liaison. [codeGatt] est renseigné s'il vient de la pile BLE. */
    data class Erreur(val message: String, val codeGatt: Int? = null) : EtatLiaison
}
