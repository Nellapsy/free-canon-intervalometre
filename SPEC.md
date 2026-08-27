# Spécification — Intervallomètre BLE pour Canon EOS R100

Version 1.0 — document de cadrage

---

## 1. Contexte

Le Canon EOS R100 ne propose pas d'intervallomètre pour les photos individuelles.
Son mode « Vidéo Time-lapse » capture bien des images à intervalle régulier, mais
les assemble automatiquement en MP4 et ne conserve pas les fichiers sources. Toute
séquence destinée à être retouchée, empilée ou exportée en RAW est donc impossible
en natif.

Les solutions existantes sont soit matérielles (télécommande filaire sur le port
RS-60E3, 15 à 20 €), soit logicielles et payantes. Le boîtier accepte par ailleurs
la télécommande sans fil Canon BR-E1, dont le dialogue Bluetooth a été
rétro-ingénieré et publié par plusieurs projets open source.

Ce projet consiste à faire tenir ce rôle de télécommande à un téléphone Android.

## 2. Objectif

Livrer une application Android autonome capable de déclencher un EOS R100 à
intervalles réguliers, sans matériel additionnel, en se faisant passer pour une
BR-E1.

## 3. Utilisateur cible

Auteur unique du projet. Développeur expérimenté, sans expérience préalable de
Kotlin, du développement Android ni du Bluetooth Low Energy. Le projet a donc
autant une finalité d'apprentissage que d'usage.

Usages visés : timelapses diurnes, filés de nuages, séquences de nuit légères,
autoportraits sans course contre le retardateur.

L'astrophotographie sérieuse reste pilotée par N.I.N.A. sur PC. L'application ne
cherche pas à concurrencer cet outil ; elle existe précisément pour éviter de
mobiliser un ordinateur quand un simple intervallomètre suffit.

## 4. Périmètre

### 4.1 Inclus

Appairage et connexion Bluetooth, déclenchement unique, séquences à intervalle
configurable, pose longue par maintien, exécution en arrière-plan écran éteint,
interface minimale, persistance des réglages.

### 4.2 Exclu

Contrôle de l'exposition, de la sensibilité ou de l'ouverture. Le boîtier reste
maître de ses réglages ; l'application n'appuie que sur le déclencheur.

Également exclus : transfert ou aperçu des images, pilotage de la mise au point,
support iOS, support d'autres modèles Canon, bracketing, bulb ramping.

## 5. Contraintes imposées par le matériel

| Contrainte | Conséquence |
|---|---|
| Le boîtier n'expose qu'une fonction Bluetooth à la fois | Activer la télécommande exclut Camera Connect, donc le géotag |
| Un seul appairage télécommande mémorisé | Associer un nouvel appareil impose d'effacer le précédent |
| Le mode d'acquisition doit être sur télécommande | Sinon le boîtier ignore les trames malgré une liaison valide |
| La pose longue dure tant que le déclencheur est maintenu | Le bulb impose deux commandes distinctes, appui puis relâchement |
| Pas de minuteur bulb intégré, contrairement au R10 | Impossible de déléguer la durée d'exposition au boîtier |
| Bluetooth 4.2 | Débit et portée sans importance ici, une poignée d'octets par déclenchement |

## 6. Exigences fonctionnelles

### F1 — Appairage

L'application découvre le boîtier lorsqu'il est placé en mode appairage, s'y
connecte et établit un bond persistant.

*Recette* : après appairage, couper puis rallumer l'application ; la reconnexion se
fait sans repasser par le mode appairage du boîtier.

### F2 — Connexion et reconnexion

Reconnexion automatique au boîtier appairé au lancement, et en cours de session
après une perte de lien.

*Recette* : éteindre le boîtier pendant une séquence, le rallumer ; la séquence
reprend sans intervention.

### F3 — Déclenchement unique

Un bouton déclenche une photo immédiatement.

*Recette* : une pression, une image sur la carte.

### F4 — Séquence à intervalle

Paramètres : délai avant démarrage, intervalle entre vues, nombre de vues ou mode
illimité.

*Recette* : une séquence de 10 vues à 5 s produit 10 fichiers, horodatés à 5 s
d'écart à 200 ms près.

### F5 — Pose longue

Durée d'exposition configurable, réalisée par envoi d'un appui maintenu puis d'un
relâchement.

**Cette exigence est conditionnelle.** Elle dépend de la capacité du protocole
télécommande du R100 à exposer ces deux états séparément, ce qui n'est pas établi.
Si la validation échoue, F5 est retirée du périmètre et l'application se limite aux
vitesses réglées sur le boîtier, soit 30 s maximum.

*Recette* : boîtier en mode M, vitesse sur BULB, exposition demandée de 60 s ;
le fichier produit affiche une durée d'exposition de 60 s ± 1 s.

### F6 — Exécution en arrière-plan

La séquence se poursuit écran éteint et application en arrière-plan, via un service
foreground.

*Recette* : une séquence de 2 h à 30 s d'intervalle, téléphone verrouillé et posé,
produit 240 fichiers sans trou.

### F7 — Progression

Notification persistante indiquant l'état de connexion, le nombre de vues prises,
le nombre restant et l'heure de fin estimée.

### F8 — Alerte

Signal sonore si la reconnexion échoue au-delà d'un seuil, pour éviter de découvrir
au matin qu'une session s'est arrêtée à la troisième vue.

### F9 — Persistance

Les derniers réglages sont rechargés au lancement.

### F10 — Arrêt

Arrêt manuel de la séquence à tout moment, depuis l'application ou la notification.

### F11 — Aide à la configuration

Écran rappelant les trois réglages boîtier nécessaires, accessible depuis l'écran
principal. Ces réglages expliquent l'essentiel des cas où la liaison est bonne mais
rien ne se déclenche.

## 7. Exigences non fonctionnelles

- **NF1 — Précision** : dérive inférieure à 200 ms par déclenchement. Suffisant
  pour du timelapse ; inutile de viser mieux.
- **NF2 — Endurance** : sessions de 4 heures sans interruption ni fuite mémoire.
- **NF3 — Robustesse** : aucune perte de séquence sur coupure Bluetooth
  temporaire. Aucun crash sur Bluetooth désactivé, permissions refusées, boîtier
  éteint.
- **NF4 — Consommation** : le BLE est négligeable côté boîtier ; le poste de
  consommation est le téléphone, qui doit tenir une nuit complète.
- **NF5 — Confidentialité** : aucune permission réseau, aucune télémétrie,
  fonctionnement entièrement hors ligne.

## 8. Architecture

Le boîtier joue le rôle de périphérique BLE : en mode appairage, c'est lui qui
émet. Le téléphone est central et se connecte à lui. C'est le sens standard, bien
supporté par l'API Android, et il évite le mode périphérique dont le support varie
selon les puces.

Découpage retenu :

- `ble/CanonProtocol` — UUID et octets de commande, regroupés et sourcés
- `ble/BleRemote` — scan, connexion, bonding, écriture sérialisée des commandes
- `interval/IntervalEngine` — ordonnancement de la séquence
- `service/ShutterService` — service foreground hébergeant la boucle
- `ui/` — écran Compose unique

Stack : Kotlin, Jetpack Compose, API `android.bluetooth` native sans wrapper tiers,
coroutines, minSdk 26.

Le choix de l'API native est délibéré : une bibliothèque tierce ajouterait une
couche d'indirection exactement là où le débogage exige de voir les échanges bruts.

## 9. Protocole

Le dialogue de la BR-E1 est documenté par trois projets open source :
`maxmacstn/ESP32-Canon-BLE-Remote`, `ArthurFDLR/BR-M5` et `gkoh/furble`. Le travail
consiste pour l'essentiel à porter cette logique C++ vers Kotlin.

**Inconnue majeure** : ces implémentations ont été validées sur EOS M6, R et RP. Le
jeu de commandes varie d'un modèle à l'autre et aucune n'a été confirmée sur R100.
Le boîtier datant de 2023, la proximité avec les R10 et R50 est probable mais non
garantie.

## 10. Jalons

| # | Contenu | Critère de sortie |
|---|---|---|
| 0 | Reconnaissance avec nRF Connect, sans code : services listés, écriture manuelle de la commande | Une photo part |
| 1 | Scan, connexion, bonding | GATT connecté, bond persistant |
| 2 | Déclenchement unique depuis l'application | F3 |
| 3 | Boucle d'intervalle en avant-plan | F4 |
| 4 | Service foreground | F6, NF2 |
| 5 | Pose longue | F5 ou décision de retrait |
| 6 | Interface, persistance, notification, alerte | F7 à F11 |

Le jalon 0 est bloquant et se traite entièrement à la main. Il lève ou confirme le
risque principal du projet en une demi-heure, avant tout investissement en code.

## 11. Risques

| Risque | Impact | Traitement |
|---|---|---|
| Protocole incompatible avec le R100 | Projet non viable en l'état | Jalon 0 avant toute écriture de code ; en cas d'échec, comparer les variantes de commandes des trois dépôts |
| Bulb non pilotable à distance | F5 abandonnée, plafond à 30 s | Périmètre dégradé accepté, sans remise en cause du reste |
| Doze interrompt les sessions longues | Timelapses tronqués | Service foreground, exclusion de l'optimisation batterie, validation par NF2 |
| Courbe d'apprentissage Kotlin et Android | Dérive du calendrier | Jalons courts, code explicite, pas d'abstraction prématurée |

## 12. Definition of done

Une séquence de 4 heures se déroule sans intervention, téléphone verrouillé,
boîtier sur trépied, et produit le nombre de fichiers attendu à intervalle
régulier. L'application survit à une coupure Bluetooth volontaire pendant cette
séquence.
