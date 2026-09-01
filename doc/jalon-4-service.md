# Jalon 4 — service foreground

Statut : **code écrit, validation sur appareil en attente** (1er septembre 2026).

Les critères de ce jalon se mesurent en heures, pas en secondes : deux heures pour F6,
quatre pour NF2. Ils ne peuvent pas être atteints dans la session qui a produit le code.
Le §7 donne les recettes à exécuter et la place où consigner les relevés.

Ce jalon n'ajoute presque aucune logique. Il déplace la séquence dans le seul endroit
qu'Android accepte de faire vivre écran éteint, et il solde deux dettes que les jalons 1 et
3 lui avaient explicitement reportées.

## 1. Ce qui bouge, et surtout ce qui ne bouge pas

`IntervalEngine` n'a pas été touché. C'est la validation rétrospective de la seule
abstraction anticipée que le plan autorisait : le moteur a changé d'hôte sans s'en
apercevoir, parce qu'`executer()` est une fonction suspendue dont le job appartient à
l'appelant.

`BleRemote` et `IntervalEngine` restent dans `IntervallometreApp`. Le service n'est
**pas** leur propriétaire, seulement l'hôte de la coroutine de séquence :

| Objet | Où il vit | Pourquoi |
|---|---|---|
| `BleRemote` | `IntervallometreApp` | sert aussi au bouton de déclenchement manuel, hors séquence |
| `IntervalEngine` | `IntervallometreApp` | l'écran observe son `etat`, qui doit survivre au service |
| Job de la séquence | `ShutterService` | seul un service foreground survit à l'écran éteint |

Conséquence pratique : `EcranPrincipal` n'a pas changé de forme. Il observe les mêmes flux
et appelle les mêmes rappels ; seule l'implémentation de `demarrerSequence` a changé, de
`portee.launch` à `startForegroundService`.

## 2. Deux contraintes de plateforme dictent la forme du service

**`startForeground` en première ligne d'`onStartCommand`.** Au-delà de cinq secondes sans
notification, le système tue le service et lève une ANR. C'est pourquoi le service affiche
un contenu « Démarrage de la séquence… » avant même de lire les réglages de l'intention.

**`START_NOT_STICKY`.** Une séquence relancée d'elle-même après un arrêt système aurait
perdu l'origine de sa grille : elle produirait des vues sur une grille neuve, décalée de la
première. Sur une nuit, deux grilles entrelacées sont pires qu'une séquence tronquée — au
moins la seconde se voit. Décision assumée : la séquence s'arrête franchement.

Le passage au premier plan peut être refusé — démarrage depuis l'arrière-plan (API 31+),
`BLUETOOTH_CONNECT` manquante pour un service `connectedDevice` (API 34+). Le service
s'arrête alors plutôt que de tourner en arrière-plan, où il serait tué sans prévenir au
milieu de la nuit.

## 3. La notification dit F7 et fait F10

Contenu, recalculé à chaque changement de l'un des deux états suivis — celui de la séquence
et celui de la liaison :

```
Vue 12 sur 240
Fin vers 23:41
[ Arrêter ]
```

Trois décisions y sont prises :

- **Pas de battement à la seconde.** La notification se met à jour quand un état change,
  pas sur une horloge. Sur quatre heures, un rafraîchissement par seconde coûterait plus
  que la séquence elle-même. Le compte à rebours reste à l'écran, qui n'est allumé que
  quand on le regarde.
- **Aucune heure de fin quand elle n'est pas connaissable** : mode illimité, créneau déjà
  manqué, liaison coupée. Le jalon 3 a décidé que « N vues » compte les vues réussies ; dès
  qu'un créneau est perdu, la durée restante n'est plus calculable. Annoncer une heure
  serait inventer.
- **La notification finale est détachée, pas supprimée.** `STOP_FOREGROUND_DETACH` avant
  `stopSelf` : une séquence finie à 3 h du matin laisse son bilan lisible au réveil.

Le calcul vit dans `service/ContenuNotification.kt`, séparé de sa mise en forme. C'est la
seule partie du jalon qui se vérifie sans appareil, et elle est couverte par huit tests
JVM. Le point délicat qu'ils protègent est la conversion d'horloge : le moteur planifie sur
`elapsedRealtime`, l'utilisateur lit une heure murale. L'écart entre les deux se recalcule
à chaque rendu — le mémoriser afficherait une heure fausse pour le reste de la nuit après
une remise à l'heure réseau.

`POST_NOTIFICATIONS` refusée n'est pas traitée comme une erreur : le service tourne, la
séquence se déroule, seule la notification reste invisible. Refuser de démarrer punirait
l'utilisateur pour une permission cosmétique. La permission est demandée au premier
« Démarrer » et non au lancement — c'est le seul moment où elle s'explique.

## 4. Dette du jalon 3 soldée — la reprise sans plafond (NF3)

`BleRemote` plafonnait la reprise à trois tentatives, ce que NF3 interdit pendant une
séquence. Le plafond est désormais conditionnel :

| Situation | Comportement |
|---|---|
| Séquence en cours | reprise **sans plafond**, temporisation doublée à chaque échec, bornée à 32 s |
| Hors séquence | plafond de 3, puis `EtatLiaison.Erreur` affichée |

Garder le plafond hors séquence est délibéré : une application ouverte devant un boîtier
éteint doit finir par afficher un échec plutôt que de vider la batterie en silence.

Le plafond de temporisation n'est pas cosmétique. Sans lui, le doublement dépasserait vite
la durée de la séquence elle-même — après quinze échecs, plus de neuf heures d'attente.

`EtatLiaison.Reconnexion.total` devient nullable. Afficher « tentative 7 sur 3 » serait
absurde, et inventer un total promettrait un abandon qui ne viendra pas.

**Ajout au-delà de la dette énoncée** : pendant une séquence, un prérequis manquant qui
peut revenir de lui-même — Bluetooth coupé, localisation désactivée sous API 31 — ne met
plus fin au cycle de liaison. Un mode avion effleuré à 2 h du matin condamnait jusqu'ici la
nuit entière. Les prérequis qui exigent l'utilisateur (pas de radio BLE, permissions
refusées) arrêtent toujours le cycle : boucler n'y changerait rien.

## 5. Dette du jalon 1 soldée — le repli sur le scan est retiré

Le jalon 1 repassait par un scan après deux échecs sur l'adresse mémorisée. C'est
contre-productif, et le jalon 1 l'avait lui-même noté : un boîtier endormi ou hors de
portée **n'émet pas d'advertising**. Le scan ne peut donc pas aboutir là où une
reconnexion sur l'adresse connue aurait fini par réussir. Le repli remplaçait une tentative
qui pouvait marcher par une qui échouerait à coup sûr, et coûtait 20 s de délai de scan à
chaque tour.

L'adresse mémorisée reste donc la cible, indéfiniment. Le scan reste accessible, mais sur
demande explicite : un bouton « Oublier ce boîtier et rechercher » efface l'adresse du
DataStore et relance un cycle. Il sert au boîtier remplacé ou réinitialisé, cas où
l'adresse mémorisée ne désigne plus rien et où aucune reprise ne peut aboutir.

## 6. Doze — trois couches, dont deux posées

La confusion à éviter : **un service foreground empêche d'être tué, pas le CPU de
s'endormir.** Ce sont deux protections distinctes, et seule la seconde explique les trous
sur intervalle long.

| Couche | Protège de | État |
|---|---|---|
| Service foreground | l'arrêt du processus par le système | posée |
| `PARTIAL_WAKE_LOCK` pendant la séquence | la mise en veille du processeur | posée |
| Exclusion d'optimisation batterie | les restrictions Doze (réseau, report d'alarmes) | proposée à l'utilisateur, jamais imposée |

Le verrou de veille est la pièce qui vise le mécanisme réel. Écran éteint et téléphone
posé, le processeur finit en veille profonde ; un `delay()` n'est qu'une minuterie en
mémoire, elle ne part pas pendant le sommeil mais au réveil suivant. Sans le verrou, une
grille de 30 s se met à sauter des créneaux au bout de quelques dizaines de minutes — et
mesurer sans lui reviendrait à tester une configuration qu'on ne voudrait pas livrer.

Il est pris **sans délai d'expiration** : une séquence illimitée n'a pas de durée connue, et
un verrou qui lâcherait en pleine nuit donnerait une panne intermittente, pire que son
absence. La libération est garantie par `terminer()` et, en dernier ressort, par
`onDestroy()`.

Coût assumé : le processeur reste allumé toute la séquence. C'est le prix du service rendu
— un intervallomètre qui rate des vues ne sert à rien.

**`AlarmManager` n'est toujours pas écrit**, et c'est conforme au plan : trancher sur mesure,
pas par précaution. Si des trous subsistent malgré le verrou de veille, la boucle passera à
un réveil par `setExactAndAllowWhileIdle`, seule minuterie que Doze respecte. Le §7 le dira.

## 7. Recettes à exécuter, et relevés

À remplir sur appareil. Sans ces deux lignes, le jalon n'est pas clos.

| Recette | Attendu | Relevé |
|---|---|---|
| **F6** — 2 h à 30 s, téléphone verrouillé et posé | 240 fichiers sans trou | _à faire_ |
| **NF2** — 4 h de séquence | aucune interruption, pas de fuite mémoire | _à faire_ |

Points à observer pendant la mesure, au-delà du compte de fichiers :

- la notification reste-t-elle à jour, ou le système la gèle-t-il ?
- **l'écart entre deux fichiers reste-t-il régulier après la 40ᵉ minute ?** C'est la mesure
  qui décide d'`AlarmManager`. Le téléphone doit être **débranché** (en charge, Doze ne
  s'active pas et la mesure ne vaut rien) et vraiment immobile (le moindre mouvement réarme
  le compteur avant Doze).
- l'arrêt depuis la notification fonctionne-t-il, écran verrouillé ?
- la mémoire du processus dérive-t-elle sur quatre heures (`adb shell dumpsys meminfo`) ?

Trace utile : `adb logcat -s BleRemote ShutterService`.

## 8. Ce qui reste ouvert

- **Les deux recettes ci-dessus.** C'est tout le critère de sortie du jalon.
- **La décision Doze**, suspendue à leur résultat.
- **La reprise sans plafond n'est pas vérifiée sur longue durée.** Elle est raisonnée, pas
  mesurée : une coupure de plusieurs minutes en pleine séquence n'a pas encore été
  provoquée. Le jalon 3 avait vérifié deux créneaux, pas davantage.
- **F8 (alerte sonore) reste au jalon 6.** Rien ne prévient encore qu'une séquence tourne à
  vide, sinon la notification — qu'on ne regarde pas la nuit.

Corrigé au passage : `lintDebug` échouait sur un `MissingPermission` dans
`forcerReconnexion` (`BleRemote.kt`), écrit au jalon 3 — l'annotation
`@SuppressLint("MissingPermission")` que porte le reste du fichier y manquait. `lintDebug`
passe désormais, et mérite d'être ajouté aux commandes habituelles du projet.
