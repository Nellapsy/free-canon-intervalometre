# Jalon 2 — déclenchement unique

Statut : **clos et validé** le 1er septembre 2026 sur SM-G973U1 (Android 12), boîtier
R100 `80:03:0D:1D:E1:11`, mode d'acquisition sur télécommande, vitesse fixe.

Le jalon a tenu en une écriture GATT dans le plan, et en a demandé deux. Il a surtout
**corrigé une conclusion fausse du jalon 0**, ce qui en fait le document le plus important
des trois pour la suite.

## 1. Critère de sortie — atteint

« Une pression, un fichier sur la carte. » Vérifié, et au-delà : seize pressions
consécutives sur une seule connexion, seize fichiers.

## 2. La découverte : `0x8C` est un appui **maintenu**

### Symptôme

Une photo par **connexion**, pas par pression. La première pression déclenchait, toutes
les suivantes ne produisaient rien — alors que chaque écriture était acquittée
`GATT_SUCCESS`. Couper puis rallumer le boîtier réarmait le déclenchement.

### Ce que la trace a éliminé

```
15:14:23.645  écriture : acquittée, code 0     ← photo
15:14:30.616  écriture : acquittée, code 0     ← rien
15:14:33.199  écriture : acquittée, code 0     ← rien
...
15:14:59.717  état : Prete → Reconnexion       ← boîtier éteint
15:15:02.957  état : Identification → Prete
15:15:06.263  écriture : acquittée, code 0     ← photo
```

Toutes les écritures acquittées : la pile BLE, le lien et l'octet envoyé sont hors de
cause. Le boîtier recevait bien `0x8C` et n'en faisait rien. Le motif n'était pas « une
seule fois » mais « une fois par connexion » — ce qui désignait un **état retenu par le
boîtier**, réinitialisé par la reconnexion.

### L'expérience discriminante

Deux hypothèses tenaient : le boîtier retient le bouton enfoncé (H1), ou c'est
l'identification rejouée à chaque connexion qui réarme (H4). Une sonde jetable — deux
boutons temporaires écrivant `0x0C` et rejouant l'identification — les a départagées sur
une connexion continue :

```
15:24:59  8C sur 00050003 acquittée, code 0   → photo
15:25:05  8C sur 00050003 acquittée, code 0   → rien
15:25:10  0C sur 00050003 acquittée, code 0
15:25:14  8C sur 00050003 acquittée, code 0   → photo
15:25:19  8C sur 00050003 acquittée, code 0   → rien
```

Aucune reconnexion entre ces cinq écritures. **H1 confirmée, H4 non nécessaire** : le
seul `0x0C` explique la différence.

### Conclusion

`0x8C` **verrouille le bouton comme maintenu enfoncé**. `0x0C` le relâche. Sans
relâchement, les `0x8C` suivants ne sont pas de nouveaux appuis : le boîtier les acquitte
au niveau ATT et les ignore. La reconnexion réinitialise le verrou.

**Une vue = deux écritures.** `maxmacstn` envoie la paire systématiquement ; c'était le
bon comportement, et le jalon 0 avait eu tort de l'écarter.

### Ce que cela invalide

Le jalon 0 concluait « `0x0C` est sans effet observable sur R100, dans tous les cas
testés : ni pour clore une pose longue, ni pour réarmer entre deux vues ». La seconde
moitié est fausse. L'essai qui l'avait établie est le seul du jalon 0 à n'avoir **aucun
journal** — les autres en ont tous un. La leçon vaut au-delà de cet octet : une conclusion
non journalisée n'est pas un relevé.

La première moitié — `0x0C` ne referme pas une pose longue — reposait sur la même prémisse
et devient **suspecte**. Elle est à revérifier au jalon 5, pas à reprendre telle quelle.

## 3. Le boîtier laisse tomber les commandes rapprochées

Une commande envoyée peu après une vue — typiquement pendant la revue d'image — est
acquittée au niveau ATT et **ne produit pas de photo**. Au-delà de 500 ms à 1 s d'écart,
plus aucune perte observée.

Rien dans le dialogue BLE ne permet de distinguer ce cas d'un succès. L'application
comptait donc des vues qui n'existaient pas.

**Décision** : un délai de garde de 1 s après chaque vue, pendant lequel le bouton est
éteint et un appui n'envoie rien. Refuser d'envoyer est le seul moyen de garder le
compteur honnête, faute de retour du boîtier.

**Ce n'est pas une garantie**, et c'est assumé : si la durée de revue du boîtier est réglée
plus longue, la fenêtre déborde le délai et le décalage redevient possible.

**Piste non explorée** : le service Canon expose quatre caractéristiques en INDICATE —
`00050004`, `00050006`, `00050007`, `0005000b` — jamais examinées. Si l'une d'elles notifie
les événements d'obturateur, le compteur deviendrait exact et le jalon 5 cesserait de
déduire l'état de pose longue au lieu de le lire. À tenter en reconnaissance nRF Connect
avant le jalon 5.

## 4. Décisions d'implémentation

- **La paire tient sous un seul `verrouGatt`.** Rien ne doit s'intercaler entre l'appui et
  son relâchement. Le `Mutex` de kotlinx.coroutines n'étant pas réentrant, `ecrire` a été
  scindée : `ecrireSousVerrou` fait l'écriture, `ecrire` prend le verrou autour.
- **Échec du relâchement seul** : la photo est prise, mais le bouton reste enfoncé côté
  boîtier et la vue suivante sera ignorée. L'écran l'annonce en toutes lettres plutôt que
  d'afficher un succès — c'est exactement le défaut corrigé ici, il ne doit pas pouvoir
  revenir en silence.
- **`declencher()` est lancée sur la portée applicative**, pas sur celle de l'appelant :
  une commande partie ne doit pas être annulée parce que l'écran se ferme. En BULB, une
  écriture interrompue entre l'appui et le relâchement laisserait l'obturateur dans un état
  que rien ne permet de relire.
- **La garde se prend par `compareAndSet`** avant de lancer quoi que ce soit : deux appuis
  simultanés ne peuvent pas passer tous les deux.
- **Le journal d'écriture porte l'octet et la caractéristique.** C'est ce qui a rendu
  l'expérience discriminante lisible ; sans lui, cinq lignes « acquittée, code 0 »
  identiques n'auraient rien prouvé.

## 5. Ce qui reste ouvert

| Question | Jalon |
|---|---|
| `0x0C` referme-t-il une pose longue ? La conclusion du jalon 0 repose sur une prémisse fausse | 5 |
| Les caractéristiques INDICATE renvoient-elles l'état de l'obturateur ? | reconnaissance, avant 5 |
| Le délai de garde tient-il si la revue d'image du boîtier est réglée longue ? | 5 ou 6 |
