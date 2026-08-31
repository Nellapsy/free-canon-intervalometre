# free-canon-intervalometre

Application Android qui déclenche un Canon EOS R100 à intervalle régulier en se faisant
passer pour une télécommande BLE Canon BR-E1.

La spécification fait foi : `SPEC.md`. Exigences (F1..F11, NF1..NF5), jalons et
definition of done y sont définis ; ne pas les réinterpréter ici.

## État

Jalon 0 (reconnaissance nRF Connect) **clos et validé** le 31 août 2026 : déclenchement,
pose longue et persistance du bond vérifiés sur R100. Voir `doc/jalon-0-protocole.md`.
Jalon courant : 1 (scan, connexion, bonding).

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
  ble/CanonProtocol.kt   UUID et octets de commande, regroupés et sourcés
  ble/BleRemote.kt       scan, connexion, bonding, écriture sérialisée
  interval/IntervalEngine.kt  ordonnancement de la séquence
  service/ShutterService.kt   service foreground hébergeant la boucle
  ui/                    écran Compose unique
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
- F5 (pose longue) est acquise mais fonctionne **en bascule**, pas en appui/relâchement
  (SPEC §6) : l'état d'exposition vit dans le boîtier. Une commande perdue l'inverse pour
  toute la suite — suivi d'état explicite exigé. Pas d'UI qui la présuppose avant le jalon 5.
- La précision d'intervalle vise 200 ms (NF1) : planifier sur une horloge absolue
  (`SystemClock.elapsedRealtime` cumulé), jamais par `delay(intervalle)` en boucle, qui dérive.
- Une séquence doit survivre à une coupure BLE (NF3) : une erreur de connexion suspend, elle
  n'annule pas.
- Le français est la langue du projet : commentaires, messages de commit, UI.

## Protocole — vérifié sur R100 le 31 août 2026

Relevés et journal des essais : `doc/jalon-0-protocole.md`. À reporter dans
`ble/CanonProtocol.kt` dès sa création, avec la date et la source de chaque valeur.

| Rôle | Valeur |
|---|---|
| Service | `00050000-0000-1000-0000-d8492fffa821` |
| Identification | car. `00050002` ← `0x03` + nom en ASCII |
| Contrôle | car. `00050003` |
| Déclenchement | `0x8C` |
| Nom annoncé | `EOSR100_001997` |

Séquence d'appairage : connecter, obtenir le bond, écrire l'identification — le boîtier
**coupe alors le lien**, il faut se reconnecter. Ce cycle n'est nécessaire qu'au premier
appairage ; les sessions suivantes se contentent de connecter et d'écrire.

`0x8C` signifie « le bouton a été pressé », pas « appui maintenu » : à vitesse fixe une
écriture produit une photo complète, en BULB elle bascule ouverture / fermeture. Ne pas
nommer les constantes `PRESS` / `RELEASE`. L'octet `0x0C` des dépôts de référence est
sans effet sur R100 : inutile entre deux vues comme pour clore une pose longue.

## Commandes

`JAVA_HOME` n'est pas défini dans le terminal — l'exporter vers un JDK 21 avant tout appel
au wrapper (Android Studio, lui, fournit le sien) :

```
export JAVA_HOME="$HOME/.jdks/ms-21.0.12.1"   # adapter au JDK 21 installé

./gradlew :app:assembleDebug     # APK debug
./gradlew :app:installDebug      # installe sur l'appareil connecté
./gradlew :app:testDebugUnitTest # tests JVM (IntervalEngine notamment)
```

Un appareil physique est nécessaire pour tout test BLE : l'émulateur n'a pas de radio.
