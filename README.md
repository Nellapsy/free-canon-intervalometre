# free-canon-intervalometre

Intervallomètre Android pour Canon EOS R100. L'application se fait passer pour la
télécommande sans fil Canon BR-E1 et déclenche le boîtier à intervalle régulier, en
Bluetooth Low Energy, sans matériel additionnel.

Le R100 ne propose pas d'intervallomètre pour les photos individuelles : son mode
« Vidéo Time-lapse » assemble les images en MP4 et ne conserve pas les fichiers
sources. Ce projet comble ce manque pour les séquences destinées à être retouchées,
empilées ou exportées en RAW.

La spécification complète (exigences, jalons, definition of done) est dans
[`SPEC.md`](SPEC.md) — elle fait foi.

## Périmètre

**Inclus** : appairage et connexion Bluetooth, déclenchement unique, séquences à
intervalle configurable, pose longue par bascule, exécution en arrière-plan écran
éteint, interface minimale, persistance des réglages.

**Exclu** : contrôle de l'exposition / sensibilité / ouverture, transfert d'images,
mise au point, iOS, autres modèles Canon, bracketing, bulb ramping.

## Confidentialité

Aucune permission réseau : `android.permission.INTERNET` est volontairement absent du
manifeste. Aucune télémétrie, aucun crash reporting, fonctionnement entièrement hors
ligne.

## État

| Jalon | Contenu | État |
|---|---|---|
| 0 | Reconnaissance nRF Connect | **validé** — déclenchement, pose longue et bond vérifiés sur R100 |
| 1 | Scan, connexion, bonding | **validé** — reconnexion à froid en 1,2 s, sans mode appairage (F1) |
| 2 | Déclenchement unique | **validé** — seize pressions, seize fichiers (F3) |
| 3 | Boucle d'intervalle | en cours |
| 4 | Service foreground | à faire |
| 5 | Pose longue | à faire |
| 6 | UI, persistance, notification, alerte | à faire |

Plan de développement des jalons 1 à 6 : [`doc/plan-developpement.md`](doc/plan-developpement.md).

## Stack

- Kotlin 2.2.20, Jetpack Compose (Material 3), coroutines
- API `android.bluetooth` native, **sans wrapper tiers** (choix délibéré, SPEC §8 :
  le débogage BLE exige de voir les échanges bruts)
- minSdk 26 (Android 8.0), targetSdk / compileSdk 36, Java 17
- Gradle 8.14.5, AGP 8.13.2
- Versions centralisées dans [`gradle/libs.versions.toml`](gradle/libs.versions.toml)

## Structure

```
app/src/main/kotlin/fr/nellapsy/canonintervallometre/
  ble/CanonProtocol.kt        UUID et octets de commande, regroupés et sourcés
  ble/BleRemote.kt            scan, connexion, bonding, écriture sérialisée
  interval/IntervalEngine.kt  ordonnancement de la séquence
  service/ShutterService.kt   service foreground hébergeant la boucle
  ui/                         écran Compose unique
```

Les paquets vides se remplissent au jalon correspondant.

## Prérequis

| Outil | Version | Note |
|---|---|---|
| JDK | 21 | imposé par [`gradle/gradle-daemon-jvm.properties`](gradle/gradle-daemon-jvm.properties) ; un JDK 25 n'est pas supporté par AGP 8.13 |
| Android SDK | Platform 36 + Build-Tools 36 | installables depuis le SDK Manager d'Android Studio |
| Android Studio | Otter (2025.2) ou plus récent | requis pour AGP 8.13 |
| Appareil | Android 8.0+ avec Bluetooth LE | **un appareil physique est nécessaire** : l'émulateur n'a pas de radio BLE |

Le wrapper Gradle est versionné, aucune installation de Gradle n'est requise.

## Installation

```bash
git clone <url-du-dépôt> free-canon-intervalometre
cd free-canon-intervalometre
```

Le SDK Android doit être localisé. Deux possibilités, au choix :

- définir la variable d'environnement `ANDROID_HOME` (Android Studio la configure
  généralement seul) ;
- ou créer un fichier `local.properties` à la racine :

  ```properties
  # Linux / macOS
  sdk.dir=/home/moi/Android/Sdk
  # Windows (double antislash)
  sdk.dir=C\:\Users\moi\AppData\Local\Android\Sdk
  ```

`local.properties` est ignoré par git : il ne doit jamais être commité.

Première synchronisation et vérification :

```bash
./gradlew :app:assembleDebug
```

## Développement avec Android Studio

C'est l'environnement recommandé : l'exécution sur appareil, le débogage et l'aperçu
Compose y fonctionnent sans configuration supplémentaire.

1. **File → Open**, sélectionner le dossier racine du dépôt (celui qui contient
   `settings.gradle.kts`), puis « Trust Project ».
2. Attendre la fin du *Gradle Sync*. Si un composant SDK manque, Android Studio
   propose de l'installer ; accepter.
3. Vérifier le JDK du build : **Settings → Build, Execution, Deployment → Build
   Tools → Gradle → Gradle JDK** doit pointer sur un JDK 21.
4. Préparer le téléphone : options développeur activées, débogage USB activé,
   câble branché et autorisation accordée sur le téléphone.
5. Sélectionner la configuration **app** et l'appareil dans la barre d'outils, puis
   `Shift+F10` (Run) ou `Shift+F9` (Debug).

Pour tester sans câble : **Device Manager → Pair using Wi-Fi**, ou
`adb pair <ip>:<port>` puis `adb connect <ip>:<port>`.

### Voir les échanges BLE

Le journal GATT brut est la principale source de diagnostic sur ce projet. Filtrer
le Logcat sur le tag de l'application, et activer si besoin le journal HCI complet :
**Options développeur → Activer le journal de trace Bluetooth HCI**, le fichier
étant ensuite récupérable par `adb pull` et lisible dans Wireshark.

## Commandes

```bash
./gradlew :app:assembleDebug        # APK debug -> app/build/outputs/apk/debug/
./gradlew :app:installDebug         # installe sur l'appareil connecté
./gradlew :app:testDebugUnitTest    # tests unitaires JVM (IntervalEngine notamment)
./gradlew :app:lintDebug            # analyse Android Lint
./gradlew clean                     # nettoyage
```

Sur Windows, remplacer `./gradlew` par `gradlew.bat`.

## Réglages boîtier

Sans ces trois réglages, la liaison peut être établie sans qu'aucune photo ne parte :

1. **Bluetooth activé** et fonction réglée sur **Télécommande** (le boîtier n'expose
   qu'une fonction Bluetooth à la fois : activer la télécommande exclut Camera
   Connect, donc le géotag).
2. **Appairage** : un seul appareil télécommande est mémorisé — en associer un
   nouveau impose d'effacer le précédent.
3. **Mode d'acquisition sur télécommande**, sinon le boîtier ignore les trames.

## Protocole

Le dialogue de la BR-E1 est documenté par trois projets open source :
[`maxmacstn/ESP32-Canon-BLE-Remote`](https://github.com/maxmacstn/ESP32-Canon-BLE-Remote),
[`ArthurFDLR/BR-M5`](https://github.com/ArthurFDLR/BR-M5) et
[`gkoh/furble`](https://github.com/gkoh/furble).

Ces implémentations ont été validées sur EOS M6, R et RP ; le jeu de commandes varie d'un
modèle Canon à l'autre. **Les jalons 0 et 2 les ont vérifiées sur R100** les 31 août et
1er septembre 2026 :

| Rôle | Valeur |
|---|---|
| Service | `00050000-0000-1000-0000-d8492fffa821` |
| Identification | caractéristique `00050002` ← `0x03` + nom en ASCII |
| Contrôle | caractéristique `00050003` |
| Déclenchement (appui) | `0x8C` |
| Relâchement | `0x0C`, obligatoire après chaque appui |
| Nom annoncé par le boîtier | `EOSR100_001997` |

`0x8C` est un **appui maintenu** : le boîtier retient le bouton enfoncé jusqu'à recevoir
`0x0C`. Sans ce relâchement, les appuis suivants sont acquittés au niveau ATT et ignorés —
symptôme : une photo par connexion. **Une vue vaut donc deux écritures.** Une commande
envoyée moins d'une seconde après une vue est également ignorée, sans que rien ne le
signale ; l'application refuse de l'envoyer plutôt que de compter une vue qui n'existe pas.

Le mécanisme de pose longue relevé au jalon 0 repose sur une prémisse invalidée depuis :
il est à revérifier avant d'implémenter F5.

Relevés et journal des essais : [`doc/jalon-0-protocole.md`](doc/jalon-0-protocole.md),
corrigé par [`doc/jalon-2-declenchement.md`](doc/jalon-2-declenchement.md).
Toute constante de protocole doit porter en commentaire sa source et l'état de sa
vérification sur R100.
