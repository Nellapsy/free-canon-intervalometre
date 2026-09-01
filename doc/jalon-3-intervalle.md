# Jalon 3 — boucle d'intervalle

Statut : **clos et validé** le 1er septembre 2026 sur SM-G973U1 (Android 12), boîtier R100
`80:03:0D:1D:E1:11`, mode d'acquisition sur télécommande.

C'est le premier jalon dont l'essentiel se vérifie sans boîtier : l'ordonnancement est de
la logique pure, et il est couvert par dix tests JVM. Le boîtier n'a eu qu'à confirmer.

## 1. Critère de sortie — atteint

« 10 vues à 5 s produisent 10 fichiers à 5 s ± 200 ms. » Vérifié sur le boîtier : dix vues
demandées, dix fichiers, écart régulier, aucun manquant. La vérification est visuelle —
les horodatages EXIF n'ont pas été extraits pour mesurer la dérive au millième.

Trois vérifications au-delà du critère, toutes conformes :

| Vérification | Résultat |
|---|---|
| Arrêt manuel en cours de séquence (F10 partiel) | arrêt immédiat, compteur juste |
| Boîtier éteint deux créneaux puis rallumé (NF3) | la séquence reprend **sur la grille**, sans décalage |
| Mode illimité, arrêté à la main | ne s'arrête pas seul, s'arrête sur demande |

## 2. La décision structurante : compter les vues, pas les créneaux

Quand une vue échoue, « 10 vues » peut vouloir dire deux choses. Le choix a été fait
explicitement, avant d'écrire le premier test :

**Dix vues réussies.** La séquence consomme autant de créneaux qu'il faut pour produire dix
fichiers. Une coupure sur le troisième créneau donne onze créneaux et dix fichiers.

```
intervalle 5 s, 10 vues, coupure au créneau 3

créneau  1  2  3  4  5  6  7  8  9  10 11
instant  0  5  10 15 20 25 30 35 40 45 50
résultat ✓  ✓  ✗  ✓  ✓  ✓  ✓  ✓  ✓  ✓  ✓     fin à t0+50 s, 10 fichiers
```

L'autre lecture — dix créneaux, quitte à rendre neuf fichiers — garantissait l'heure de fin
au prix du compte. Ce projet sert à produire des séquences ; c'est le compte qui prime.

Conséquence assumée : la durée d'une séquence n'est pas prévisible quand la liaison est
mauvaise. Une coupure de vingt minutes prolonge la séquence d'autant.

## 3. La grille est absolue, et les créneaux passés se sautent

Chaque instant vaut `origine + k × intervalle`, calculé depuis l'origine de la séquence sur
`SystemClock.elapsedRealtime`. Jamais `delay(intervalle)` en boucle : l'erreur de chaque
vue s'y ajouterait à la précédente, ce qui est invisible sur dix vues et vaut plusieurs
secondes sur une nuit. C'est exactement NF1.

`elapsedRealtime` et non `currentTimeMillis` : la grille doit tenir même si l'horloge murale
saute (synchronisation réseau, changement d'heure).

Un créneau déjà passé — vue plus lente que l'intervalle, ou liaison coupée pendant
plusieurs créneaux — est **sauté**, pas rattrapé. Deux raisons : une rafale de rattrapage
produirait des vues hors grille, et déclencher « dès que possible » ferait dériver tout le
reste de la séquence.

## 4. Ce que les tests couvrent, et comment on sait qu'ils tiennent

Dix tests sur `IntervalEngine`, sur l'horloge virtuelle de `kotlinx-coroutines-test` : une
séquence de quatre heures s'y vérifie en millisecondes. Six autres sur la saisie de
l'écran, qui est la seule autre logique pure du projet.

Trois de ces tests passaient dès leur écriture, faute d'avoir pu être rouges — la
non-dérive, le délai avant démarrage et le mode illimité décrivent des propriétés que
l'implémentation avait déjà. Ils ont été validés par **mutation** : le moteur a été cassé
une fois par test, et chacun est tombé sous sa propre mutation, et seulement sous la sienne.

| Mutation | Test tombé |
|---|---|
| délai avant démarrage ignoré | `le delai avant demarrage est respecte` |
| `delay(intervalle)` relatif au lieu de la grille | `une vue lente ne decale pas les suivantes` (et deux autres) |
| mode illimité arrêté à dix vues | `le mode illimite tient quatre heures…` |

Un test qui n'a jamais échoué ne prouve rien ; c'était le moyen de le faire échouer.

## 5. La correction du relâchement échoué

Le jalon 2 a établi que `0x8C` est un appui maintenu, et que sans `0x0C` le boîtier ignore
toutes les vues suivantes **tout en les acquittant**. Restait un trou : si `0x8C` passe et
que `0x0C` échoue, la photo est prise et le boîtier reste bloqué. Rien dans le dialogue BLE
ne permet de le voir — une séquence de nuit continuerait à vide jusqu'au matin.

`BleRemote.forcerReconnexion()` coupe donc le lien dans ce cas précis, pour que la boucle de
liaison le rétablisse : le jalon 2 avait observé qu'une reconnexion réarme le déclencheur.
La vue est par ailleurs comptée comme **échouée** bien que la photo existe — le compteur
préfère sous-estimer que mentir.

**Non vérifié sur le boîtier**, et ça ne peut pas l'être à la demande : il faudrait couper la
liaison entre deux écritures espacées de quelques millisecondes. C'est une mitigation
raisonnée à partir d'un fait observé, pas un mécanisme mesuré. À surveiller si le symptôme
« la séquence tourne, plus rien ne sort » réapparaît.

## 6. Découpage

`IntervalEngine` ne connaît pas le Bluetooth : il ne voit qu'une interface `Declencheur` à
une seule méthode, `suspend fun prendreVue(): Boolean`. C'est la seule abstraction anticipée
du projet, et elle existe pour une raison unique : sans elle, rien de tout ce qui précède ne
serait testable.

`BleRemote.prendreVue()` s'exécute sur la portée de la liaison, puis est attendue. Si la
séquence est annulée en pleine vue, l'attente est rompue mais la paire appui / relâchement
va à son terme — sinon l'arrêt manuel rouvrirait le défaut du jalon 2 par une autre porte.

Le moteur ne lance rien lui-même : `executer()` est une fonction suspendue, et c'est
l'appelant qui détient le job. À ce jalon c'est `IntervallometreApp` ; au jalon 4, ce sera
`ShutterService`, sans que le moteur change.

## 7. Ce qui reste ouvert

- **Reprise sans plafond.** La boucle de liaison abandonne après trois tentatives. NF3
  l'interdit pendant une séquence. Un `TODO(jalon 4)` le marque dans `BleRemote`.
- **Écran éteint.** Rien ici ne garantit F6 : la séquence vit tant que le processus vit.
  C'est l'objet du jalon 4.
- **Intervalle minimum de 2 s**, imposé par le délai de garde d'une seconde de `BleRemote`.
  Suffisant pour du timelapse ; à revoir seulement si un usage réel le demande.
