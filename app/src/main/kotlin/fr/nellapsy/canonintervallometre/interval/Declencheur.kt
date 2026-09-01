package fr.nellapsy.canonintervallometre.interval

/**
 * Ce dont [IntervalEngine] a besoin : quelque chose qui sait prendre une vue.
 *
 * Seule abstraction anticipée que le plan de développement autorise, et elle l'est pour une
 * raison précise : elle sépare l'ordonnancement de la radio, ce qui rend le moteur
 * vérifiable en JVM avec un double. `BleRemote` en est la seule implémentation réelle.
 *
 * Le booléen suffit : le motif d'un échec vit déjà dans `BleRemote.etatDeclencheur`, et le
 * moteur n'en fait rien — il retente au créneau suivant, quelle qu'en soit la cause.
 */
interface Declencheur {

    /** Prend une vue et attend son acquittement. `true` si le boîtier l'a acquittée. */
    suspend fun prendreVue(): Boolean
}
