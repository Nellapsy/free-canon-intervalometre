# free-canon-intervalometre

Application Android qui déclenche un Canon EOS R100 à intervalle régulier en se faisant
passer pour une télécommande BLE Canon BR-E1.

La spécification fait foi : `SPEC.md`. Exigences (F1..F11, NF1..NF5), jalons et
definition of done y sont définis ; ne pas les réinterpréter ici.

## État

Jalon 0 (reconnaissance nRF Connect) **clos et validé** le 31 août 2026 : déclenchement,
pose longue et persistance du bond vérifiés sur R100. Voir `doc/jalon-0-protocole.md`.

Jalon 1 (scan, connexion, bonding) **clos et validé** le 1er septembre 2026 sur
SM-G973U1 / Android 12 : relance à froid → « Prête » en 1,2 s, sans scan et sans repasser
par le mode appairage du boîtier (F1). Voir `doc/jalon-1-liaison.md`.

Jalon 2 (déclenchement unique) **clos et validé** le 1er septembre 2026 : seize vues
consécutives, seize fichiers (F3). Il a corrigé une conclusion fausse du jalon 0 — voir
`doc/jalon-2-declenchement.md`.

Jalon 3 (boucle d'intervalle) **clos et validé** le 1er septembre 2026 : 10 vues à 5 s →
10 fichiers, grille tenue après coupure volontaire, arrêt manuel et mode illimité vérifiés
(F4). Voir `doc/jalon-3-intervalle.md`.

Jalon 4 (service foreground) : **code écrit, validation sur appareil en attente**. Ses
critères se mesurent en heures — 2 h pour F6, 4 h pour NF2 — et n'ont pas encore été
exécutés. Recettes et relevés à remplir : `doc/jalon-4-service.md` §7.

Jalon courant : 4, en attente de mesure. Plan complet : `doc/plan-developpement.md`.

Le SDK Android est installé sur cette machine et le projet y compile. Les tests BLE
exigent en revanche un appareil physique — l'émulateur n'a pas de radio.

## Stack

- Kotlin, Jetpack Compose (Material 3), coroutines
- `android.bluetooth` natif, **sans wrapper tiers** — choix délibéré (SPEC §8) : le débogage
  BLE exige de voir les échanges bruts. Ne pas introduire Nordic BLE library, RxAndroidBle, etc.
- minSdk 26, targetSdk/compileSdk 36, Java 17, Gradle 8.14.5 / AGP 8.13.2
- Le daemon Gradle tourne sur un JDK 21 (`gradle/gradle-daemon-jvm.properties`) ; un JDK 25
  n'est pas supporté par cette version d'AGP.
- Versions centralisées dans `gradle/libs.versions.toml` (version catalog), jamais en dur
  dans un `build.gradle.kts`.

## Découpage

```
app/src/main/kotlin/fr/nellapsy/canonintervallometre/
  IntervallometreApp.kt  Application : détient BleRemote, IntervalEngine et leur portée
  ble/CanonProtocol.kt   UUID et octets de commande, regroupés et sourcés
  ble/EtatLiaison.kt     état de la liaison exposé à l'interface
  ble/EtatDeclencheur.kt issue du dernier déclenchement (vues, échec)
  ble/BleRemote.kt       scan, connexion, bonding, écriture sérialisée
  ble/AdresseBoitier.kt  adresse du boîtier appairé (DataStore)
  interval/Declencheur.kt     interface à une méthode, sépare le moteur de la radio
  interval/ReglagesSequence.kt  délai, intervalle, nombre de vues (null = illimité)
  interval/EtatSequence.kt    avancement exposé à l'interface
  interval/IntervalEngine.kt  ordonnancement de la séquence
  service/ShutterService.kt   service foreground hébergeant la boucle
  service/ContenuNotification.kt  contenu de la notification (pur, testé)
  ui/SaisieSequence.kt   validation des réglages (pure, testée)
  ui/EcranPrincipal.kt   écran Compose unique
  ui/MainActivity.kt     cycle de vie et thème, rien d'autre
```

Ce découpage vient de SPEC §8. Les paquets vides se remplissent au jalon correspondant ;
pas d'abstraction anticipée (SPEC §11, risque courbe d'apprentissage).

## Règles

- **Aucune permission réseau** (NF5). `android.permission.INTERNET` ne doit jamais apparaître
  dans le manifeste, ni aucune dépendance qui l'exige. Pas de télémétrie, pas de crash
  reporting.
- Toute constante de protocole (UUID, octet de commande) porte en commentaire sa source :
  dépôt d'origine (`maxmacstn/ESP32-Canon-BLE-Remote`, `ArthurFDLR/BR-M5`, `gkoh/furble`) et
  ce qui a été vérifié sur le R100. Les jeux de commandes varient d'un modèle Canon à l'autre :
  une valeur non vérifiée sur R100 est signalée comme telle.
- Les écritures GATT sont sérialisées : Android n'accepte qu'une opération GATT en vol.
- F5 (pose longue) : le mécanisme relevé au jalon 0 — bascule par `8C` répété, `0x0C`
  inopérant — reposait sur une prémisse invalidée au jalon 2. **Il est à revérifier avant
  d'écrire quoi que ce soit de F5.** Ce qui tient : l'état d'exposition vit dans le
  boîtier, une commande perdue l'inverse pour toute la suite, suivi d'état explicite exigé.
  Pas d'UI qui présuppose un mécanisme avant le jalon 5.
- La précision d'intervalle vise 200 ms (NF1) : planifier sur une horloge absolue
  (`SystemClock.elapsedRealtime` cumulé), jamais par `delay(intervalle)` en boucle, qui dérive.
- Une séquence doit survivre à une coupure BLE (NF3) : une erreur de connexion suspend, elle
  n'annule pas.
- Le français est la langue du projet : commentaires, messages de commit, UI.

## Protocole — vérifié sur R100 les 31 août et 1er septembre 2026

Relevés et journal des essais : `doc/jalon-0-protocole.md`, corrigé par
`doc/jalon-2-declenchement.md`. Les constantes vivent dans `ble/CanonProtocol.kt`, chacune
avec sa source et sa date.

| Rôle | Valeur |
|---|---|
| Service | `00050000-0000-1000-0000-d8492fffa821` |
| Identification | car. `00050002` ← `0x03` + nom en ASCII |
| Contrôle | car. `00050003` |
| Déclenchement (appui) | `0x8C` |
| Relâchement | `0x0C` — obligatoire après chaque appui |
| Nom annoncé | `EOSR100_001997` |

Séquence d'appairage : connecter, obtenir le bond, écrire l'identification — le boîtier
**coupe alors le lien**, il faut se reconnecter. Cette coupure n'a lieu qu'au premier
enregistrement ; `BleRemote` la reconnaît à sa fenêtre (moins de 5 s après identification)
et reconnecte sans la traiter comme une erreur.

Décisions du jalon 1, à ne pas réinterpréter :

- **Bond explicite** (`createBond()` + attente de `ACTION_BOND_STATE_CHANGED`), et non
  laissé au système : « bond persistant » est le critère de sortie, il doit être observable.
- **Identification réécrite à chaque connexion**, comme `furble`. Vérifié inoffensif au
  jalon 0. Évite de persister un état « déjà identifié » qui mentirait après une
  réinitialisation du boîtier.
- Le receiver `ACTION_BOND_STATE_CHANGED` s'enregistre en **`RECEIVER_EXPORTED`**. Avec
  `RECEIVER_NOT_EXPORTED` la diffusion n'est jamais livrée (vérifié sur SM-G973U1 /
  Android 12 le 1er septembre 2026) : le bond aboutit, l'application l'ignore et bloque
  30 s sur « appairage en cours ». Diffusion protégée, donc non usurpable — ne pas
  « resserrer » ce flag.
- L'aboutissement du bond se lit **aussi** dans `bondState`, scruté en parallèle de la
  diffusion. `bondState` est la source de vérité ; la diffusion n'est qu'une notification.

Décisions du jalon 2, à ne pas réinterpréter :

- **Une vue = deux écritures**, `0x8C` puis `0x0C`, tenues sous un seul `verrouGatt`.
  `0x8C` est un **appui maintenu** : le boîtier retient le bouton enfoncé et ignore tout
  `0x8C` suivant — acquitté `GATT_SUCCESS`, sans photo — jusqu'à un `0x0C`. Symptôme si on
  l'oublie : une photo par connexion. Vérifié sur R100 le 1er septembre 2026.
- **Délai de garde de 1 s après chaque vue**, pendant lequel un appui n'envoie rien. Une
  commande envoyée dans cette fenêtre est acquittée sans produire de photo, et rien dans le
  dialogue BLE ne permet de le savoir : refuser d'envoyer est le seul moyen de garder le
  compteur honnête. Mitigation mesurée, pas garantie.
- **Le mécanisme de pose longue du jalon 0 est à revérifier**, pas à reprendre : il
  concluait que `0x0C` ne referme pas l'obturateur et que `8C` répété le bascule, sur la
  même prémisse fausse. Rien de F5 ne s'écrit avant un nouvel essai journalisé (jalon 5).
- Piste jamais explorée, à tenter avant le jalon 5 : les caractéristiques `00050004`,
  `00050006`, `00050007`, `0005000b` (INDICATE). Si l'une notifie l'état d'obturateur, le
  compteur devient exact et F5 cesse de déduire au lieu de lire.

Décisions du jalon 3, à ne pas réinterpréter :

- **« N vues » = N vues réussies**, pas N créneaux. Un échec consomme son créneau sans
  compter ; la séquence se prolonge jusqu'au compte, toujours sur la grille. Corollaire
  assumé : la durée d'une séquence n'est pas prévisible si la liaison est mauvaise.
- **Un créneau déjà passé se saute**, jamais ne se rattrape : une rafale produirait des vues
  hors grille, et déclencher « dès que possible » ferait dériver toute la suite.
- **`IntervalEngine` ne connaît que `Declencheur`**, jamais `BleRemote`. C'est ce qui le rend
  testable en JVM, et c'est la seule abstraction anticipée autorisée. `executer()` est une
  fonction suspendue : le job appartient à l'appelant (`IntervallometreApp` au jalon 3,
  `ShutterService` au jalon 4).
- **`prendreVue()` s'exécute sur la portée de la liaison, puis est attendue** : une séquence
  annulée en pleine vue ne doit pas couper entre `0x8C` et `0x0C`.
- **Un relâchement échoué force une reconnexion** (`forcerReconnexion`), seul remède connu au
  boîtier resté bouton enfoncé. Raisonné à partir du jalon 2, **non vérifié sur boîtier** :
  la panne ne se provoque pas à la demande.
- **Intervalle minimum 2 s**, imposé par le délai de garde. Refus explicite dans
  `ui/SaisieSequence.kt` : aucune correction silencieuse d'une saisie hors limites.

Décisions du jalon 4, à ne pas réinterpréter :

- **Le service n'est propriétaire de rien.** `BleRemote` et `IntervalEngine` restent dans
  `IntervallometreApp` — la liaison sert au bouton manuel hors séquence, et l'écran observe
  `moteur.etat`. `ShutterService` n'héberge que le job de la séquence.
- **`START_NOT_STICKY`.** Une séquence relancée seule après un arrêt système aurait perdu
  l'origine de sa grille et produirait une seconde grille décalée. Elle s'arrête franchement.
- **`startForeground` en première ligne d'`onStartCommand`**, avant de lire les réglages :
  au-delà de 5 s sans notification, le système tue le service.
- **Reprise sans plafond pendant une séquence** (NF3), plafond de 3 hors séquence.
  `BleRemote.signalerSequence()` est ce qui bascule. Temporisation doublée, bornée à 32 s —
  sans borne, le doublement dépasserait la durée de la séquence.
- **Aucun repli automatique sur le scan.** Un boîtier endormi n'émet pas d'advertising : le
  scan ne peut pas aboutir là où une reconnexion sur l'adresse connue finirait par réussir.
  Le scan n'a lieu que sur `oublierBoitier()`, déclenché par l'utilisateur.
- **Pas d'heure de fin annoncée quand elle n'est pas connaissable** (illimité, créneau
  manqué, liaison coupée) : « N vues » compte les vues réussies, la durée n'est alors plus
  calculable. Voir `service/ContenuNotification.kt`.
- **`POST_NOTIFICATIONS` refusée ne bloque pas la séquence** : le service tourne, seule la
  notification reste invisible.
- **`PARTIAL_WAKE_LOCK` tenu pendant toute la séquence**, pris dans `demarrer()`, relâché
  dans `terminer()` et `onDestroy()`. Un service foreground empêche d'être tué, **pas** le
  CPU de s'endormir : sans ce verrou, `delay()` ne part qu'au réveil suivant du processeur
  et la grille saute des créneaux écran éteint. Sans délai d'expiration, volontairement —
  une séquence illimitée n'a pas de durée connue, et un verrou qui lâche en pleine nuit
  donne une panne intermittente, pire que son absence.
- **`AlarmManager` n'est pas écrit**, conformément au plan : trancher sur mesure. Il ne
  s'écrira que si des trous subsistent *malgré* le verrou de veille, aux recettes F6/NF2.

## Commandes

`JAVA_HOME` n'est pas défini dans le terminal — l'exporter vers un JDK 21 avant tout appel
au wrapper (Android Studio, lui, fournit le sien) :

```
export JAVA_HOME="$HOME/.jdks/ms-21.0.12.1"   # adapter au JDK 21 installé

./gradlew :app:assembleDebug     # APK debug
./gradlew :app:installDebug      # installe sur l'appareil connecté
./gradlew :app:testDebugUnitTest # tests JVM (IntervalEngine notamment)
./gradlew :app:lintDebug         # lint Android, vert depuis le jalon 4
```

Un appareil physique est nécessaire pour tout test BLE : l'émulateur n'a pas de radio.
