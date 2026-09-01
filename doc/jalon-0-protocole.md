# Jalon 0 — reconnaissance nRF Connect

Statut : **clos et validé** le 31 août 2026. Déclenchement, pose longue et persistance du
bond vérifiés sur R100. Le risque principal du projet (SPEC §11) est levé.

> **Deux conclusions de ce document ont été corrigées depuis.** Le rôle de `0x0C` et, par
> ricochet, le mécanisme de pose longue : voir §6, « Correction du 1er septembre 2026 ».

## 0. Journal des essais

### 31 août 2026 — identification : succès

Bond établi, puis écriture sur `00050002` de `03 49 6E 74 65 72 76 61 6C 6C 6F`
(`0x03` + « Intervallo » en ASCII).

**Résultat** : le boîtier affiche « Intervallo » et le note comme déjà synchronisé. Le
préfixe `0x03` et le rôle d'identification de `00050002` sont donc **confirmés sur R100**.

**Effet de bord** : le boîtier ferme la connexion immédiatement après l'écriture. Ce
comportement est attendu — `maxmacstn` le contourne par un cycle `disconnect()` /
abaissement du chiffrement de `MITM` à `NO_MITM` / `connect()`. La séquence d'appairage
de `BleRemote` devra reproduire ce cycle : **la connexion qui commande n'est pas celle
qui appaire.**

Reconnexion non aboutie lors de cet essai — cause à déterminer : boîtier endormi,
réglage de connexion Bluetooth du boîtier, ou niveau de chiffrement.


### 31 août 2026 — déclenchement : sans effet, couches BLE hors de cause

Boîtier reconnecté, état `CONNECTED / BONDED`. Nom complet relevé : **`EOSR100_001997`**
(l'advertising tronquait à `EOSR100_`) — c'est ce nom que le scan Kotlin verra.

Journal nRF Connect, retranscrit :

```
18:59:43.740  Services discovered
18:59:57.853  Data written to 00050003…, value: (0x) 8C      → "(0x) 8C" sent
19:00:12.382  Data written to 00050003…, value: (0x) 0C      → "(0x) 0C" sent
19:00:42.439  Connection parameters updated (interval 30.0ms, latency 0, timeout 720ms)
19:01:20.713  Data written to 00050002…, value: (0x) 03-49-6E-74-65-72-76-61-6C-6C-6F
19:01:39.286  Data written to 00050003…, value: (0x) 8C      → "(0x) 8C" sent
19:03:56.778  Data written to 00050003…, value: (0x) 8C      → "(0x) 8C" sent
19:04:03.918  Data written to 00050003…, value: (0x) 0C      → "(0x) 0C" sent
```

**Ce que ce journal établit :**

- Aucune erreur GATT sur aucune écriture : le boîtier acquitte au niveau ATT. Les
  couches lien et écriture sont hors de cause.
- Les octets envoyés sont exacts (`8C`, `0C`, et l'identification complète). La saisie
  était bien en BYTE ARRAY — l'hypothèse d'un envoi en UTF-8 est éliminée.
- L'identification réécrite sur une connexion déjà établie **ne provoque plus de
  déconnexion**. La coupure observée plus tôt était propre au premier enregistrement.
  Conséquence pour `BleRemote` : le cycle déconnexion/reconnexion n'est nécessaire qu'à
  l'appairage initial, pas à chaque session.

**Aucune photo n'est partie.** Le boîtier reçoit les bons octets et les ignore : la cause
restante est côté configuration boîtier (mode d'acquisition, état de connexion Bluetooth,
ou mise au point qui bloque l'obturateur), pas côté protocole.

Test discriminant à mener : écrire `4C` (`FOCUS | CTRL`) sur `00050003`. Si l'objectif
réagit, le boîtier obéit et seul le déclenchement est bloqué ; s'il ne se passe rien, le
boîtier ignore toutes les commandes.

À noter : deux onglets nRF Connect étaient ouverts simultanément sur la même adresse
pendant l'essai. Le R100 n'accepte qu'une liaison télécommande — à écarter lors du
prochain essai.


### 31 août 2026 — déclenchement : **succès, jalon 0 validé**

Après correction de la configuration du boîtier, l'écriture de `8C` sur `00050003`
**déclenche une photo**. L'exposition se déroule à la vitesse réglée sur le boîtier
(ouverture puis fermeture automatiques).

`0C` envoyé ensuite reste sans effet visible — attendu : à vitesse fixe l'exposition est
déjà terminée, il n'y a plus rien à relâcher. Ce résultat **ne tranche donc pas F5** ;
seul un essai en pose longue le peut.

**Constantes désormais vérifiées sur R100 :**

| Élément | Valeur | Statut |
|---|---|---|
| Service | `00050000-0000-1000-0000-d8492fffa821` | vérifié |
| Identification | `00050002`, préfixe `0x03` + nom ASCII | vérifié |
| Contrôle | `00050003` | vérifié |
| Déclenchement | `0x8C` (`SHUTTER 0x80 \| CTRL 0x0C`) | vérifié |
| Relâchement | `0x0C` (`CTRL`) | envoyé sans erreur, effet non observé à vitesse fixe |
| Autofocus | `0x4C` (`FOCUS 0x40 \| CTRL 0x0C`) | non concluant (essayé en configuration boîtier incorrecte) |

Le risque principal du projet (SPEC §11, « protocole incompatible avec le R100 ») est levé.

**Reste à faire au jalon 0 :** essai en pose longue (F5) et vérification de la persistance
du bond (F1).


### 31 août 2026 — pose longue : **F5 réalisable, mais en bascule**

Boîtier en mode M, vitesse sur BULB.

- **Variante A** (`8C` … `0C`) : **échec**. Le relâchement ne ferme pas l'obturateur.
- **Variante B** (`8C` … `8C`) : **succès**. Une première écriture de `0x8C` ouvre
  l'obturateur, une seconde le ferme.

**F5 est donc réalisable**, et sort de son statut conditionnel (SPEC §6). Mais le
mécanisme n'est pas celui décrit par la SPEC ni par les dépôts de référence : ce n'est
pas un appui maintenu suivi d'un relâchement, c'est une **bascule à état**. `furble`
expose `shutterPress()` / `shutterRelease()` comme deux commandes symétriques ; sur R100
en pose longue, seule `shutterPress()` a un effet et elle alterne ouverture/fermeture.

Sémantique réelle de `0x8C` : « le bouton a été pressé ». Le boîtier décide de la suite
selon son propre mode — une photo complète à vitesse fixe, une bascule en BULB.

**Conséquences pour l'implémentation :**

- `CanonProtocol` ne doit pas nommer ces octets `PRESS` / `RELEASE` : c'est trompeur.
- En pose longue, `BleRemote` doit tenir l'état ouvert/fermé, et non émettre une paire
  symétrique. Une commande perdue inverse l'état pour toute la suite de la séquence —
  point de vigilance pour NF3 (robustesse sur coupure BLE).
- Question ouverte, à trancher avant le jalon 3 : à vitesse fixe, faut-il intercaler
  `0C` entre deux déclenchements successifs, ou `8C` répété suffit-il ? `maxmacstn`
  envoie systématiquement la paire. À vérifier par trois déclenchements consécutifs
  n'envoyant que `8C`.

### 31 août 2026 — cause racine du « rien ne se passe »

**Le mode d'acquisition.** Le boîtier était sur un mode sans télécommande ; il acquittait
les écritures GATT sans erreur et les ignorait. Passer sur **« Retardateur /
télécommande »** suffit à tout débloquer.

Confirme la contrainte annoncée en SPEC §5. C'est le contenu principal de l'écran d'aide
F11 : liaison valide + écritures acquittées + rien qui se déclenche = mode d'acquisition.


### 31 août 2026 — persistance du bond : succès, F1 validée dans son principe

Déconnexion, sortie du mode appairage du boîtier, puis reconnexion depuis l'onglet BONDED
de nRF Connect : `8C` déclenche toujours, **sans repasser par le mode appairage**.

Le bond Android survit donc à la déconnexion, et le boîtier reconnaît la télécommande
enregistrée sur une nouvelle connexion. F1 est réalisable telle que la SPEC la décrit.

**Jalon 0 clos.** Reste une question ouverte, sans effet sur le jalon 1 : à vitesse fixe,
`8C` répété sans `0C` intercalé produit-il bien une photo par écriture ? À trancher avant
le jalon 3.


### 31 août 2026 — `0C` inutile entre deux vues

> **⚠ CONCLUSION FAUSSE, corrigée le 1er septembre 2026.** Voir §6 en fin de document.
> Conservée telle quelle : la manière dont elle a été obtenue — sans journal — est la
> leçon.

À vitesse fixe, `8C` répété sans `0C` intercalé produit bien une photo par écriture.

`0x0C` est donc sans effet observable sur R100, dans tous les cas testés : ni pour clore
une pose longue, ni pour réarmer entre deux vues. `maxmacstn` l'envoie systématiquement ;
c'est inutile ici. La boucle du jalon 3 se contentera d'**une écriture GATT par vue**.

Toutes les questions ouvertes du jalon 0 sont tranchées.

## 1. Relevés sur le boîtier — 31 août 2026

Source : exploration nRF Connect du 31 août 2026, relevé écran par écran.

Boîtier annoncé : nom `EOSR100_001997` (tronqué à `EOSR100_` dans l'advertising),
adresse `80:03:0D:1D:E1:11`.

Services standard : `0x1800` (Generic Access), `0x1801` (vide), `0x180A` (Device
Information).

Service Canon `00050000-0000-1000-0000-d8492fffa821` :

| Caractéristique | Propriétés | Rôle présumé |
|---|---|---|
| `00050001` | READ | — |
| `00050002` | WRITE, WRITE NO RESPONSE | **identification / appairage** |
| `00050003` | WRITE, WRITE NO RESPONSE | **contrôle (déclencheur, autofocus)** |
| `00050004` | INDICATE, READ | — |
| `00050005` | WRITE, WRITE NO RESPONSE | — |
| `00050006` | INDICATE, READ | — |
| `00050007` | INDICATE, READ | — |
| `0005000a` | WRITE, WRITE NO RESPONSE | — |
| `0005000b` | INDICATE, READ | — |
| `0005000c` | WRITE, WRITE NO RESPONSE | — |

État au moment des captures : DISCONNECTED / NOT BONDED — l'appairage n'a pas abouti
ou n'a pas persisté.

## 2. Correspondance avec les dépôts de référence

Les rôles présumés ci-dessus ne sont pas des suppositions : deux implémentations
indépendantes emploient exactement ces trois UUID, et le R100 les expose avec les
bonnes propriétés.

`gkoh/furble`, `lib/furble/CanonEOSRemote.h` :

```cpp
static const NimBLEUUID PRI_SVC_UUID {0x00050000, 0x0000, 0x1000, 0x0000d8492fffa821};
const NimBLEUUID ID_CHR_UUID         {0x00050002, 0x0000, 0x1000, 0x0000d8492fffa821};
const NimBLEUUID CTRL_CHR_UUID       {0x00050003, 0x0000, 0x1000, 0x0000d8492fffa821};

static constexpr uint8_t SHUTTER = 0x80;
static constexpr uint8_t FOCUS   = 0x40;
static constexpr uint8_t CTRL    = 0x0c;
```

`maxmacstn/ESP32-Canon-BLE-Remote`, `src/CanonBLERemote.h` et `.cpp` — mêmes valeurs,
exprimées en binaire :

```cpp
const byte BUTTON_RELEASE = 0b10000000;  // 0x80
const byte BUTTON_FOCUS   = 0b01000000;  // 0x40
const byte MODE_IMMEDIATE = 0b00001100;  // 0x0C

CanonBLERemote::CanonBLERemote(String name)
  : SERVICE_UUID          ("00050000-0000-1000-0000-d8492fffa821"),
    PAIRING_SERVICE       ("00050002-0000-1000-0000-d8492fffa821"),
    SHUTTER_CONTROL_SERVICE("00050003-0000-1000-0000-d8492fffa821")
```

Commandes qui en découlent, à écrire sur `00050003` :

| Octet | Calcul | Effet |
|---|---|---|
| `0x8C` | `SHUTTER \| CTRL` | appui déclencheur |
| `0x0C` | `CTRL` | relâchement |
| `0x4C` | `FOCUS \| CTRL` | appui autofocus |

Ces valeurs sont désormais **vérifiées sur R100** — voir le journal des essais en tête de
document. Les octets `0x8C` et le préfixe d'identification `0x03` se comportent comme
décrit ; `0x0C` fait exception : accepté sans erreur, il reste sans effet.

### Conséquence pour F5 (pose longue)

`furble` expose `shutterPress()` et `shutterRelease()` comme deux écritures distinctes.
**Ce modèle ne tient pas sur R100** : `0x0C` ne referme pas l'obturateur. La pose longue y
fonctionne en bascule, `0x8C` ouvrant puis refermant. F5 est réalisable, mais avec un
suivi d'état côté application.

## 3. Séquence exécutée dans nRF Connect

*Conservée telle qu'elle a été suivie, pour rejouer la manip après une réinitialisation
du boîtier ou sur un second appareil.*

Préalable boîtier : Bluetooth activé, fonction sur **Télécommande**, écran d'**appairage**
ouvert, **mode d'acquisition sur retardateur/télécommande** — ce dernier point est la
cause racine des essais infructueux du 31 août.

**1 — Scanner.** Onglet SCANNER. Avant de se connecter, ouvrir le détail de `EOSR100_` et
noter les **Manufacturer Data** de l'advertising (utile pour filtrer le scan côté Kotlin).
Vérifier que l'UUID de service `00050000-...` figure bien dans l'advertising : c'est ce
sur quoi les deux dépôts filtrent.

**2 — Se connecter et appairer.** CONNECT. Accepter la demande d'appairage Android si
elle apparaît. L'en-tête doit passer à **CONNECTED / BONDED**. Si elle n'apparaît pas,
forcer via le menu ⋮ → *Bond*. Les deux dépôts sécurisent la liaison **avant** toute
écriture ; sans bond, l'étape suivante échouera probablement.

**3 — S'identifier.** Écrire sur `00050002` : préfixe `0x03` suivi du nom de la
télécommande en ASCII. Pour le nom `Intervallo` :

```
03 49 6E 74 65 72 76 61 6C 6C 6F
```

Coller cette valeur dans le champ hexadécimal (flèche ↑ sur la caractéristique), en
**Write Request**. Noter si le boîtier réagit à l'écran — c'est à ce moment qu'il est
censé afficher l'appairage réussi et le nom de la télécommande.

**4 — Déclencher.** Écrire sur `00050003`, en deux temps :

```
8C      <- appui
0C      <- relâchement, environ une seconde plus tard
```

**Une photo doit partir.** C'est le critère de sortie du jalon.

**5 — Tester la pose longue.** Boîtier en mode M, vitesse sur BULB. Écrire `8C`, attendre
une dizaine de secondes, écrire `0C`. Vérifier la durée d'exposition du fichier produit.
Ce test tranche F5.

**6 — Vérifier la persistance du bond.** Se déconnecter, quitter le mode appairage du
boîtier, puis se reconnecter depuis l'onglet BONDED. Si le déclenchement fonctionne
encore sans repasser par le mode appairage, F1 est validée dans son principe.

## 4. À noter pendant la manip

- Manufacturer Data de l'advertising (étape 1)
- L'appairage se fait-il spontanément, ou faut-il le forcer ?
- L'écriture d'identification est-elle acceptée avant le bond, ou seulement après ?
- Y a-t-il des erreurs GATT (code et numéro) sur une écriture refusée ?
- Faut-il réécrire l'identification (`0x03` + nom) à chaque reconnexion, ou une seule fois
  à l'appairage ? `furble` la réécrit à chaque connexion, `maxmacstn` non — la réponse
  détermine la logique de `BleRemote`.
- Write Request ou Write Command : les deux fonctionnent-ils ?

## 5. Si le déclenchement échoue

Essayer, dans l'ordre : `0x8C` seul sans relâchement ; `0x0C` seul ; l'autofocus `0x4C`
puis `0x8C` ; enfin les autres caractéristiques en écriture (`00050005`, `0005000a`,
`0005000c`) avec les mêmes octets. Consigner chaque essai et son résultat ici.

---

## 6. Correction du 1er septembre 2026 — `0C` est indispensable entre deux vues

**La conclusion « `0C` inutile entre deux vues », en tête de ce document, est fausse.**

Le jalon 2 a établi sur une connexion continue que `0x8C` **verrouille le bouton comme
maintenu enfoncé**, et que `0x0C` seul le relâche :

```
15:24:59  8C sur 00050003 acquittée, code 0   → photo
15:25:05  8C sur 00050003 acquittée, code 0   → rien
15:25:10  0C sur 00050003 acquittée, code 0
15:25:14  8C sur 00050003 acquittée, code 0   → photo
15:25:19  8C sur 00050003 acquittée, code 0   → rien
```

Sans relâchement, les `0x8C` suivants ne sont pas de nouveaux appuis : le boîtier les
acquitte au niveau ATT et les ignore. Le symptôme se lisait « une photo par connexion »,
la reconnexion réinitialisant le verrou. **Une vue vaut deux écritures** ; `maxmacstn`
avait raison d'envoyer la paire systématiquement.

L'essai qui avait conclu l'inverse est le seul de ce document à n'être accompagné
**d'aucun journal**. C'est la leçon à retenir autant que l'octet : une conclusion non
journalisée n'est pas un relevé.

### Ce que cela met en doute pour F5

L'essai de pose longue du 31 août concluait que `0x0C` ne referme pas l'obturateur et que
`8C` répété le bascule. Il reposait sur la même prémisse fausse et n'est **plus fiable** :
à revérifier au jalon 5, avec journal cette fois, avant d'écrire quoi que ce soit de F5.

### Tableau des constantes, corrigé

| Élément | Valeur | Statut |
|---|---|---|
| Déclenchement (appui) | `0x8C` | vérifié — appui **maintenu**, verrouille le bouton |
| Relâchement | `0x0C` | **vérifié le 1er sept. 2026** — réarme le bouton, obligatoire entre deux vues |
| Pose longue en bascule | `8C` … `8C` | **à revérifier** — conclusion fondée sur une prémisse fausse |

Détail complet : [`doc/jalon-2-declenchement.md`](jalon-2-declenchement.md).
