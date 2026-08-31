# Plan de développement — jalons 1 à 6

Niveau architecture et séquencement. Le détail du code se décide au moment d'écrire
chaque jalon ; ce document fixe l'ordre, les frontières et les critères.

**Référence** : `SPEC.md` fait foi pour les exigences. Relevés du protocole :
`doc/jalon-0-protocole.md`.

---

## Contraintes globales

Elles s'appliquent à tous les jalons, sans rappel.

| Contrainte | Origine |
|---|---|
| Aucune permission réseau, `android.permission.INTERNET` interdit | NF5 |
| API `android.bluetooth` native, aucun wrapper tiers | SPEC §8 |
| Une seule opération GATT en vol : toute écriture est sérialisée | Android |
| Planification sur horloge absolue, jamais `delay(intervalle)` en boucle | NF1, 200 ms |
| Une coupure BLE suspend la séquence, elle ne l'annule pas | NF3 |
| Versions dans `gradle/libs.versions.toml`, jamais en dur | CLAUDE.md |
| Constantes de protocole sourcées et datées en commentaire | CLAUDE.md |
| Français : commentaires, commits, interface | CLAUDE.md |
| minSdk 26 : deux régimes de permissions BLE selon la version | manifeste |

---

## Ce qui est testable, et comment

C'est la décision structurante du projet, et elle conditionne le découpage.

**Testable automatiquement en JVM** — `./gradlew :app:testDebugUnitTest` :

- `IntervalEngine` : ordonnancement pur, aucune dépendance Android. C'est là que va
  l'essentiel des tests. `kotlinx-coroutines-test` est déjà en dépendance et fournit une
  horloge virtuelle : une séquence de 4 heures se vérifie en millisecondes.
- `CanonProtocol` : construction de la trame d'identification (`0x03` + nom).

**Non testable automatiquement** — `BleRemote`, `ShutterService`, l'interface Compose.
Ils dépendent de la radio et du boîtier. Ils se valident **à la main sur appareil**, avec
les recettes déjà écrites dans SPEC §6. L'émulateur ne sert à rien ici : pas de radio BLE.

**Conséquence sur le découpage** : `IntervalEngine` ne doit pas connaître `BleRemote`. Une
interface minimale — « quelque chose qui sait déclencher » — les sépare, ce qui rend le
moteur testable avec un double. C'est la **seule** abstraction anticipée que ce plan
autorise ; elle est justifiée par la testabilité, pas par l'esthétique. Elle apparaît au
jalon 3, quand `IntervalEngine` existe, et pas avant.

---

## Jalon 1 — Scan, connexion, bonding

**Objectif** : établir et rétablir une liaison utilisable avec le boîtier.

**Fichiers** : `ble/CanonProtocol.kt` (création), `ble/BleRemote.kt` (création),
`ui/MainActivity.kt` (permissions et affichage d'état).

**Contenu**

1. `CanonProtocol` d'abord : reporter les constantes vérifiées au jalon 0, chacune avec sa
   source et sa date. Aucune logique. C'est du transfert, pas de la conception.
2. Permissions à l'exécution, en deux régimes : `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` à
   partir d'API 31, `ACCESS_FINE_LOCATION` de 26 à 30. Le manifeste les déclare déjà.
3. Vérifier que le Bluetooth est actif avant de scanner, et le dire à l'utilisateur.
4. Scan filtré sur l'UUID de service `00050000-…`. C'est le filtre qu'emploient les deux
   dépôts de référence, et le boîtier l'annonce.
5. Connexion GATT, puis découverte des services. **Aucune écriture avant
   `onServicesDiscovered`.**
6. Bonding, et attente effective de son aboutissement.
7. Écriture d'identification, puis gestion de la déconnexion qui suit — elle est
   **attendue**, pas une erreur — et reconnexion.
8. Mémoriser l'adresse du boîtier (DataStore, déjà en dépendance) pour les sessions
   suivantes, qui se contentent de connecter.
9. Exposer l'état de la liaison à l'interface sous forme observable.

**Critère de sortie** : GATT connecté, bond persistant, reconnexion après redémarrage de
l'application sans repasser par le mode appairage du boîtier (F1).

**Points de vigilance**

- Les callbacks GATT arrivent sur un thread système : ne rien faire de bloquant dedans,
  republier vers une coroutine.
- La file d'attente des opérations GATT se pose **dès maintenant**, pas au jalon 3. La
  rajouter après coup impose de reprendre tout ce qui l'utilise.
- L'erreur 133 d'Android sur connexion est courante et souvent transitoire : prévoir une
  reprise, ne pas la traiter comme fatale.

**Décision à trancher** : le bond est-il obtenu explicitement, ou laissé au système lors
de la première écriture chiffrée ? Le comportement observé au jalon 0 suggère la seconde
voie ; à vérifier sur appareil avant de figer.

---

## Jalon 2 — Déclenchement unique

**Objectif** : F3. Un bouton, une photo.

**Fichiers** : `ble/BleRemote.kt` (ajout), `ui/` (bouton et état).

**Contenu** : une écriture de `0x8C` sur la caractéristique de contrôle. Le jalon 0 a
établi qu'aucune écriture de relâchement n'est nécessaire — une écriture GATT par vue.

**Critère de sortie** : une pression, un fichier sur la carte.

**Points de vigilance** : le bouton doit refléter l'état réel de la liaison, et non
laisser croire à un déclenchement quand rien n'est connecté. C'est le premier endroit où
l'application ment facilement à son utilisateur.

Ce jalon est court. Il ne vaut que par ce qu'il prouve : la chaîne complète
interface → BLE → boîtier fonctionne depuis l'application, et plus depuis nRF Connect.

---

## Jalon 3 — Boucle d'intervalle en avant-plan

**Objectif** : F4. C'est le cœur du projet et la partie la plus testable.

**Fichiers** : `interval/IntervalEngine.kt` (création), interface de déclenchement,
`app/src/test/kotlin/…/IntervalEngineTest.kt` (création), `ui/` (réglages).

**Contenu**

Paramètres : délai avant démarrage, intervalle entre vues, nombre de vues ou mode
illimité.

L'ordonnancement se fait sur une **horloge absolue** : chaque instant de déclenchement est
calculé depuis l'origine de la séquence (`SystemClock.elapsedRealtime` cumulé), jamais par
addition de `delay(intervalle)`. La différence est invisible sur dix vues et vaut
plusieurs secondes sur une nuit — c'est exactement NF1.

Une erreur de déclenchement **suspend** la séquence et n'en décale pas la grille : quand
la liaison revient, on reprend à l'instant théorique suivant, pas à « maintenant ».

**Critère de sortie** : 10 vues à 5 s produisent 10 fichiers à 5 s ± 200 ms, et la suite
de tests JVM passe.

**Tests à écrire** — ce jalon est le seul où les tests précèdent réellement le code :

- les instants de déclenchement suivent la grille théorique ;
- un déclenchement lent ne décale pas les suivants (non-dérive) ;
- une erreur suspend, puis la reprise retombe sur la grille ;
- le compte de vues s'arrête au nombre demandé, et le mode illimité ne s'arrête pas seul ;
- le délai avant démarrage est respecté.

Ils s'exécutent sur l'horloge virtuelle de `kotlinx-coroutines-test` : une séquence de
4 heures se vérifie instantanément.

**Points de vigilance** : la tentation sera d'appeler `BleRemote` directement depuis le
moteur. C'est ce qui rendrait le moteur intestable. L'interface de déclenchement se pose
ici.

---

## Jalon 4 — Service foreground

**Objectif** : F6 et NF2. La séquence survit à l'écran éteint.

**Fichiers** : `service/ShutterService.kt` (création), `AndroidManifest.xml` (déclaration
du service), `ui/` (démarrage et liaison).

**Contenu** : héberger la boucle du jalon 3 dans un service foreground de type
`connectedDevice`, avec sa notification obligatoire. Demander `POST_NOTIFICATIONS` à
l'exécution à partir d'API 33. Proposer l'exclusion de l'optimisation batterie — la
permission est déjà au manifeste.

**Critère de sortie** : 2 heures à 30 s d'intervalle, téléphone verrouillé, 240 fichiers
sans trou. Puis 4 heures pour NF2, sans fuite mémoire.

**Points de vigilance**

- Doze est le risque identifié en SPEC §11. Un service foreground avec une connexion BLE
  active suffit en général, mais ce n'est pas garanti par le système.
- **Décision à trancher** : si un intervalle long (plusieurs minutes) se révèle tronqué
  par Doze, il faudra passer à un réveil par `AlarmManager` plutôt qu'une boucle en
  attente. À décider sur mesure, pas par précaution — la mesure vient avec ce jalon.
- La rotation de l'écran ou la fermeture de l'activité ne doit rien interrompre : le
  service détient la séquence, pas l'interface.

Ce jalon est le premier qui demande de la patience : ses critères se mesurent en heures,
pas en secondes.

---

## Jalon 5 — Pose longue

**Objectif** : F5, acquise, mais au mécanisme différent de ce que la SPEC supposait
initialement.

**Fichiers** : `ble/BleRemote.kt` (état d'obturateur), `interval/IntervalEngine.kt`
(durée d'exposition), `ui/`.

**Contenu** : en mode BULB le déclenchement fonctionne en **bascule** — `0x8C` ouvre,
`0x8C` referme. L'état d'exposition vit donc dans le boîtier, pas dans l'application.

**Critère de sortie** : mode M, vitesse BULB, exposition demandée de 60 s, fichier produit
à 60 s ± 1 s.

**Points de vigilance — c'est le jalon le plus délicat du projet**

Une commande perdue n'annule pas une vue : elle **inverse l'état pour tout le reste de la
séquence**. Une nuit entière peut être ruinée par une seule écriture manquée. C'est le
point où NF3 cesse d'être une formalité.

**Décision à trancher** : que faire à la reprise après une coupure survenue obturateur
ouvert ? Envoyer une fermeture de sécurité au retour de la liaison, ou considérer la vue
perdue et resynchroniser sur la grille ? Les deux se défendent ; le choix se fait ici,
explicitement, et se documente.

Le suivi d'état doit être explicite et vérifiable, pas déduit d'un compteur de parité.

---

## Jalon 6 — Interface, persistance, notification, alerte

**Objectif** : F7 à F11.

**Fichiers** : `ui/` (écran principal, écran d'aide), persistance DataStore, notification,
alerte sonore.

**Contenu**

- **F7** — notification persistante : état de connexion, vues prises, vues restantes,
  heure de fin estimée.
- **F8** — signal sonore si la reconnexion échoue au-delà d'un seuil. Son objet est
  d'éviter de découvrir au matin qu'une séquence s'est arrêtée à la troisième vue.
- **F9** — rechargement des derniers réglages au lancement.
- **F10** — arrêt manuel depuis l'application **et** depuis la notification.
- **F11** — écran d'aide rappelant les trois réglages boîtier.

**Sur F11, contenu déjà acquis** : le jalon 0 a produit son texte. Liaison valide +
écritures acquittées + rien qui ne se déclenche = **mode d'acquisition**. C'est le symptôme
le plus déroutant du projet, il a coûté une session entière, et il mérite d'être en
première ligne de cet écran, pas en note de bas de page.

**Critère de sortie** : F7 à F11 vérifiées.

---

## Definition of done du projet

Reprise de SPEC §12, sans réinterprétation : une séquence de 4 heures se déroule sans
intervention, téléphone verrouillé, boîtier sur trépied, et produit le nombre de fichiers
attendu à intervalle régulier. L'application survit à une coupure Bluetooth volontaire
pendant cette séquence.

---

## Risques restants

Le risque principal — protocole incompatible avec le R100 — est levé. Restent :

| Risque | Jalon exposé | Traitement |
|---|---|---|
| Doze tronque les sessions longues | 4 | Mesure réelle avant tout contournement ; `AlarmManager` en repli |
| État d'obturateur désynchronisé en pose longue | 5 | Suivi d'état explicite, politique de reprise décidée et documentée |
| Reconnexion Android capricieuse (erreur 133) | 1, puis partout | Reprise sur échec, jamais d'abandon de séquence |
| Aucun test automatisé sur la couche BLE | 1, 2, 4, 5 | Recettes manuelles de SPEC §6, exécutées à chaque jalon |
| Courbe d'apprentissage Kotlin et Android | tous | Jalons courts, code explicite, pas d'abstraction anticipée |

---

## Conduite du développement

- **Un jalon, au moins un commit.** Les tests accompagnent le code qu'ils couvrent, dans
  le même commit.
- **Aucun jalon ne se déclare fait sans sa recette exécutée sur appareil.** Un build qui
  compile ne prouve rien sur ce projet ; c'est le boîtier qui juge.
- **Consigner les découvertes de protocole** dans `doc/jalon-0-protocole.md` au fil de
  l'eau. Le jalon 0 a montré que les surprises viennent du boîtier, pas du code.
- **Mettre à jour l'état des jalons** dans `README.md` et `CLAUDE.md` à chaque passage.
