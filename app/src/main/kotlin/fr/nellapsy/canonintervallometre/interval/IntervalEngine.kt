package fr.nellapsy.canonintervallometre.interval

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ordonnance une séquence de vues sur une grille d'instants régulière (F4).
 *
 * Le moteur ne connaît pas le Bluetooth : il ne voit qu'un [Declencheur]. C'est ce qui le
 * rend vérifiable en JVM, et c'est la seule raison pour laquelle cette interface existe.
 *
 * @param horloge horloge **monotone** en millisecondes : `SystemClock.elapsedRealtime` en
 *   production, l'horloge virtuelle de `runTest` en test. Elle est injectée pour cette
 *   seule raison — une nuit de quatre heures se vérifie alors en millisecondes.
 */
class IntervalEngine(
    private val declencheur: Declencheur,
    private val horloge: () -> Long,
) {

    private val etatInterne = MutableStateFlow<EtatSequence>(EtatSequence.Inactive)

    /** Avancement de la séquence. Voir [EtatSequence]. */
    val etat: StateFlow<EtatSequence> = etatInterne.asStateFlow()

    /**
     * Déroule la séquence et rend la main quand elle est finie. L'arrêt (F10) est
     * l'annulation de la coroutine appelante.
     *
     * Les instants sont calculés depuis l'origine de la séquence, jamais par addition de
     * `delay(intervalle)` : c'est ce qui tient NF1 sur une nuit entière.
     *
     * Une vue échouée consomme son créneau sans compter : la séquence se prolonge d'autant
     * de créneaux qu'il faut pour atteindre le nombre de vues **réussies** demandé, sans
     * jamais quitter la grille.
     */
    suspend fun executer(reglages: ReglagesSequence) {
        val origine = horloge() + reglages.delaiAvantDemarrageMs
        val vuesVoulues = reglages.vuesDemandees ?: Int.MAX_VALUE

        var creneau = 0
        var reussies = 0
        var suspendue = false
        var complete = false

        try {
            while (reussies < vuesVoulues) {
                val cible = origine + creneau * reglages.intervalleMs
                creneau++

                // Créneau déjà passé — vue plus lente que l'intervalle, ou liaison coupée
                // pendant plusieurs créneaux. On le saute au lieu de déclencher en retard :
                // une rafale de rattrapage produirait des vues hors grille, et déclencher
                // « dès que possible » ferait dériver tout le reste de la séquence.
                if (cible < horloge()) continue

                // Publié avant l'attente, et non après la vue : c'est pendant l'attente que
                // l'écran a quelque chose à montrer.
                etatInterne.value = EtatSequence.EnCours(
                    vuesReussies = reussies,
                    vuesDemandees = reglages.vuesDemandees,
                    prochainInstantMs = cible,
                    suspendue = suspendue,
                )

                delay(cible - horloge())
                val acquittee = declencheur.prendreVue()
                if (acquittee) reussies++
                suspendue = !acquittee
            }
            complete = true
        } finally {
            // Dans un `finally` : une séquence arrêtée à la main (F10) doit laisser le même
            // état exploitable qu'une séquence menée à son terme, et l'annulation passe
            // par là.
            etatInterne.value = EtatSequence.Terminee(reussies, complete)
        }
    }
}
