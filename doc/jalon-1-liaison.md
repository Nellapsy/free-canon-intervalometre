# Jalon 1 — scan, connexion, bonding

Statut : **clos et validé** le 1er septembre 2026 sur SM-G973U1 (Android 12), boîtier
R100 `80:03:0D:1D:E1:11`. Le critère de sortie F1 est démontré par la trace ci-dessous.

## 1. Critère de sortie — atteint

« GATT connecté, bond persistant, reconnexion après redémarrage de l'application sans
repasser par le mode appairage du boîtier. »

Relance à froid, 12:45:29, `adb logcat -s BleRemote` :

```
12:45:29.864  adresse : relue 80:03:0D:1D:E1:11
12:45:29.865  état : Inactif → Connexion          ← aucun passage par Recherche
12:45:31.015  bond : état initial BOND_BONDED     ← bond persistant
12:45:31.015  état : Connexion → Identification
12:45:31.046  écriture : acquittée, code 0
12:45:31.054  adresse : mémorisée 80:03:0D:1D:E1:11
12:45:31.054  état : Identification → Prete
```

**1,2 s** de l'ouverture à « Prête », sans scan et sans mode appairage sur le boîtier.

## 2. Deux pièges de plateforme, tous deux coûteux

### `RECEIVER_NOT_EXPORTED` empêche la livraison de `ACTION_BOND_STATE_CHANGED`

Symptôme : l'appairage aboutit côté Android (le boîtier apparaît dans les appareils
appairés), l'application reste 30 s sur « appairage en cours » puis échoue sur
« Appairage non abouti ». Le receiver ne recevait **aucune** diffusion — pas une
diffusion filtrée à tort, zéro diffusion.

La trace qui l'a établi, avant correctif :

```
12:03:14.323  bond : état initial BOND_NONE sur 80:03:0D:1D:E1:11
12:03:14.346  bond : createBond() = true
12:03:44.359  bond : issue = null, état final BOND_BONDED (délai de 30000 ms dépassé)
```

`bondState` valait `BOND_BONDED` à l'expiration : l'information était disponible depuis
30 s et l'application la jetait. Correctif : `RECEIVER_EXPORTED`. Sans risque
d'usurpation, `ACTION_BOND_STATE_CHANGED` étant une diffusion protégée.

### La diffusion reste capricieuse même en `RECEIVER_EXPORTED`

Observé à 12:45:01 : `BOND_BONDING` arrive, `BOND_BONDED` **jamais**.

```
12:45:01.516  bond : diffusion … → BOND_BONDING
12:45:05.018  bond : abouti par scrutin
```

D'où le scrutin de `bondState` toutes les 500 ms en parallèle de la diffusion. Ce n'est
pas une ceinture décorative : sans lui, cette session repartait pour 30 s de blocage.
**`bondState` est la source de vérité, la diffusion n'est qu'une notification.**

## 3. Hypothèse écartée, à ne pas ré-instruire

La mémorisation de l'adresse avait été soupçonnée de courir après l'acquittement de
l'écriture d'identification — le jalon 0 ayant documenté que le boîtier coupe le lien
juste après. **C'est faux** : `écriture : acquittée, code 0` précède systématiquement
`adresse : mémorisée`. Ne pas déplacer `memoriser()` avant l'identification, il n'y a
rien à y gagner.

## 4. Défaut connu, reporté au jalon 4

Le repli sur le scan après `TENTATIVES_SUR_ADRESSE_CONNUE` échecs est
**contre-productif** en fonctionnement normal. Un boîtier endormi ou hors de portée
n'émet aucun advertising : le scan ne peut pas aboutir, par construction.

```
12:46:19.966  état : Prete → Reconnexion(1/3)
12:46:26.034  état : Connexion → Reconnexion(2/3)   ← deux erreurs 133
12:46:28.039  adresse : ignorée volontairement, retour au scan
12:47:12.101  état : Recherche → Erreur(Aucun boîtier trouvé…)
```

40 s de scan pour rien, là où retenter l'adresse connue avait une chance. NF3 exige une
reprise sans plafond pendant une séquence ; le `TODO(jalon 4)` de `BleRemote` le signale
déjà. À traiter avec le service foreground, pas avant : c'est là que la reprise compte.

## 5. Instrumentation

`BleRemote` journalise sous le tag `BleRemote` : chaque transition d'état, l'issue du
bond, les diffusions reçues (avant filtre d'adresse), l'acquittement des écritures et la
lecture / écriture de l'adresse mémorisée. Conservée volontairement — deux diagnostics de
ce jalon n'ont été possibles que grâce à elle. Dans Android Studio : onglet Logcat,
filtre `tag:BleRemote`.
