# free-canon-intervalometre

Application Android qui déclenche un Canon EOS R100 à intervalle régulier en se faisant
passer pour une télécommande BLE Canon BR-E1.

La spécification fait foi : `SPEC.md`. Exigences (F1..F11, NF1..NF5), jalons et
definition of done y sont définis ; ne pas les réinterpréter ici.

## État

Jalon 0 (reconnaissance nRF Connect) validé — le protocole BR-E1 déclenche bien le R100.
Jalon courant : 1 (scan, connexion, bonding).

Aucun SDK Android sur cette machine : le projet s'y édite, il se compile et se teste
ailleurs. Ne pas tenter de lancer `./gradlew assembleDebug` ici, et ne pas installer de SDK.

## Stack

- Kotlin, Jetpack Compose (Material 3), coroutines
- `android.bluetooth` natif, **sans wrapper tiers** — choix délibéré (SPEC §8) : le débogage
  BLE exige de voir les échanges bruts. Ne pas introduire Nordic BLE library, RxAndroidBle, etc.
- minSdk 26, targetSdk/compileSdk 36, Java 17, Gradle 8.14.3 / AGP 8.13.2
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
- F5 (pose longue) est conditionnelle — voir SPEC §6. Ne pas construire d'UI ou de réglage
  qui la présuppose avant le jalon 5.
- La précision d'intervalle vise 200 ms (NF1) : planifier sur une horloge absolue
  (`SystemClock.elapsedRealtime` cumulé), jamais par `delay(intervalle)` en boucle, qui dérive.
- Une séquence doit survivre à une coupure BLE (NF3) : une erreur de connexion suspend, elle
  n'annule pas.
- Le français est la langue du projet : commentaires, messages de commit, UI.

## Protocole — à consigner

Les valeurs relevées au jalon 0 avec nRF Connect (UUID du service, caractéristique
d'écriture, octets envoyés, ordre des étapes d'appairage) doivent être reportées dans
`ble/CanonProtocol.kt` dès sa création, avec la date du relevé.

## Commandes

```
./gradlew :app:assembleDebug     # sur la machine équipée du SDK
./gradlew :app:testDebugUnitTest # tests JVM (IntervalEngine notamment)
```
