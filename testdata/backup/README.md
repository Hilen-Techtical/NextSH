# Fixtures de sauvegarde `.nextsh`

Fichiers de test partagés par `:shared`, `:app` (Android) et `:desktop`. Les `.json` sont les documents
en clair ; les `.nextsh` du même nom (chiffrés) sont produits à part par un générateur neutre et lus par
les tests de chaque plateforme : mêmes fichiers, mêmes compteurs attendus des deux côtés.

**Tous les secrets sont factices** (mots de passe `FIXTURE-...`, clés privées `FIXTURE-NOT-A-REAL-...`).
Les clés publiques des known hosts sont de vraies clés publiques SSH (blobs valides), générées pour
l'occasion, sans clé privée conservée. Seule exception : `v2-cles-incoherentes`, dont les clés privées sont
de vraies clés de test jetables (voir sa section), générées pour ce fichier et sans aucune valeur.

## Passphrase de test

`Fixture-vault-é-2026` (non ASCII exprès : PBKDF2 passe par BouncyCastle sur Android et par SunJCE sur
Desktop). Publique, elle ne protège rien. Le `é` est le point de code U+00E9 (forme composée, pas
`e` suivi de U+0301). PBKDF2 encode la passphrase en UTF-8.

Chiffrement des `.nextsh` : PBKDF2WithHmacSHA256, 210 000 itérations, clé de 256 bits ; AES-256-GCM ;
cadre `[SALT_LEN(4 BE)][SALT(32)][IV_LEN(1)][IV(12)][CIPHERTEXT||TAG]`.

Les `.nextsh` sont produits par `EncryptFixture.java` (ce dossier), qui n'utilise que le JDK (SunJCE) et
aucun code de NextSH : ainsi Android (BouncyCastle) et Desktop (SunJCE) lisent un fichier qu'aucun des
deux n'a écrit. Il relit chaque fichier produit avant de rendre la main. Régénérer après tout changement
d'un `.json` (JDK 11 ou plus, depuis ce dossier) :

```bash
java EncryptFixture.java v2-complet.json v2-complet.nextsh
```

Le sel et l'IV sont tirés au hasard : régénérer change les octets, pas le contenu déchiffré.
`.gitattributes` marque les `.nextsh` comme binaires (aucune conversion de fin de ligne).

## Fichiers et valeurs attendues

### `v1-sans-version.json`

Format 1.0.1 : aucune clé `version`, aucune section nouvelle. Lecture : `version` = 1.

| Section | Nombre |
|---|---|
| hôtes | 2 |
| clés SSH | 1 |
| tunnels | 1 |
| mots de passe (`credentials`) | 1 |
| clés privées | 1 |
| passphrases de clés | 0 |
| certificats, snippets, thèmes, known hosts | 0 |
| réglages | aucun |

Le deuxième hôte référence le préréglage `DRACULA`.

### `v2-complet.json`

Toutes les sections, `"version": 2`, producteur `DESKTOP`.

| Section | Nombre |
|---|---|
| hôtes | 3 (mot de passe, clé SSH, certificat ; le troisième utilise le thème personnalisé) |
| clés SSH | 1 |
| tunnels | 2 |
| mots de passe | 2 |
| clés privées | 1 |
| passphrases de clés | 1 |
| certificats | 1 |
| snippets | 3 (l'un avec `\n` et `\t` dans la commande, l'un avec un accent, l'un avec `createdAt` à 0) |
| thèmes personnalisés | 1 (id `7d1f0c2a-5b3e-4a6d-9c8b-1e2f3a4b5c6d`, 16 couleurs ANSI, sélection translucide) |
| known hosts | 5, un par type : `ssh-ed25519`, `ssh-rsa` (3072), `ecdsa-sha2-nistp256`, `-nistp384`, `-nistp521` |
| entrées invalides | 0 |
| réglages applicables | Android : 5 (1 commun + 4), Desktop : 3 (1 commun + 2) |
| clés de premier niveau inconnues | aucune |

Known hosts (`hôte:port`) : `ed25519.fixture.test:22`, `rsa.fixture.test:22`, `ecdsa256.fixture.test:22`,
`ecdsa384.fixture.test:22`, `ecdsa521.fixture.test:2222`.

Réglages : commun `connectionTimeoutSeconds` 15 ; Android `terminalFontSize` 28, `scrollbackLines` 20000,
`keepAliveIntervalSeconds` 45, `autoReconnect` false ; Desktop `terminalFontSizePx` 16, `language` `FR`.
La zone de notification (`minimizeToTrayOnClose`) n'est ni exportée ni importée.

### `v2-sections-inconnues.json`

`"version": 2` avec deux clés de premier niveau inconnues (`futureSection`, `anotherFutureSection`),
comme le ferait une évolution additive future. Attendu : import du connu (1 hôte, 1 mot de passe,
1 snippet) et avertissement « données d'une version plus récente non restaurées ».

### `v1-coeur-invalide.json`

Format 1.0.1 (aucune clé `version`) dont le cœur contient un cas de chaque règle de validation. Les
caractères spéciaux sont écrits en échappement `\uXXXX` dans le JSON. Les valeurs géantes sont des `x` ou
des `a` répétés (aucun secret). Lecture : `version` = 1 ; tout se décide dans `BackupValidator.validate`.

| Section | Dans le fichier | Gardés | Invalides |
|---|---|---|---|
| hôtes | 11 | 5 : `h-ok-1`, `h-ok-2` (IPv6, port 65535, utilisateur vide), `h-ok-3` (label avec U+202E, permis), `h-label-cleaned` (saut de ligne dans le label, remplacé par une espace : un label est assaini, jamais refusé), `h-user-cleaned` (tabulation dans le nom d'utilisateur, remplacée par une espace : `ro ot`, l'hôte et ses tunnels restent) | 6 : id vide, id en doublon (le premier gagne), port 0, espace dans le hostname, U+200B dans le hostname, thème de 129 caractères |
| clés SSH | 5 | 2 : `key-ok`, `key-multiline` (clé publique sur deux lignes, ramenée à une seule : `ssh-ed25519 AAAA first ssh-ed25519 BBBB second`, la deuxième clé devient du commentaire) | 3 : alias Keystore de 257 caractères, id en doublon, `fido2CredentialId` avec un caractère de contrôle (U+0007) |
| tunnels | 7 | 3 : `t-ok`, `t-socks` (SOCKS5, hôte distant vide, port distant 0), `t-orphan` (hôte absent du fichier : gardé, l'importeur décide) | 4 : port local 0, port distant 0 (hors SOCKS5), espace dans l'hôte distant, `t-cascade` (son hôte `h-bad-port` est écarté) |
| mots de passe | 5 | 2 : `cred-ok`, `cred-empty` (valeur vide permise) | 2 : id vide, caractère de contrôle dans l'id ; `lan_sync_secret_fixture` est retiré sans être compté |
| clés privées | 3 | 1 : `key-ok` | 2 : valeur vide, valeur de 64 Kio + 1 |
| passphrases de clés | 2 | 1 : `key-ok` | 1 : id de 129 caractères |
| certificats, snippets, thèmes, known hosts | 0 | | |

Compteurs de `ValidatedBackup` : `invalidHosts` 6, `invalidSshKeys` 3, `invalidTunnels` 4, `invalidSecrets` 5
(2 + 2 + 1), tous les autres à 0, `invalidTotal` 18. Le secret LAN n'est compté nulle part. Les deux
plateformes, qui restaurent `validated.document`, doivent importer 5 hôtes, 2 clés, 3 tunnels, 2 mots de passe,
1 clé privée et 1 passphrase, avec `invalidIgnored` à 18 (les tunnels sautés pour hôte introuvable
dépendent de l'état local : `t-orphan` est le seul concerné).

Depuis les règles partagées des clés SSH (`SshKeyRules`) : une clé publique sur plusieurs lignes et un nom d'utilisateur
avec un caractère de contrôle sont nettoyés au lieu d'être refusés (`key-multiline` et `h-user-cleaned`), et
`key-fido2-id-control` garde le compte des clés invalides à 3.

### `v2-cles-incoherentes.json`

`"version": 2`, producteur `DESKTOP`, cinq clés SSH et leurs clés privées, pour les règles d'ajout seul et
de dérivation des clés (`BackupKeyRules`, `SshPublicKeyDeriver`). Toute autre section est vide.

**Clés de test jetables** : ce sont de vraies clés SSH, générées par ssh-keygen 9.5 pour ce fichier
uniquement (clé A et clé B en Ed25519 sans passphrase, une RSA 2048 sans passphrase, une Ed25519 chiffrée
avec la passphrase `FIXTURE-pass`). Elles ne protègent rien, ne sont installées nulle part et ne doivent
jamais servir ailleurs, comme les clés publiques des known hosts.

| Id | Ligne publique du fichier | Clé privée du fichier | Ce que la restauration en fait (appareil vierge) |
|---|---|---|---|
| `key-a-line-b` | celle de B | A | ligne remplacée par la clé A dérivée et prouvée, avec le commentaire de B (`fixture-b`) : **recalculée** |
| `key-enc-line-b` | celle de B | Ed25519 chiffrée, passphrase `FIXTURE-pass` dans `keyPassphrases` | rien n'est prouvé sans passphrase (aucune KDF à l'import) : ligne de B gardée, non comptée. Ouverte avec sa passphrase (copie de la clé publique), la ligne est corrigée |
| `key-rsa` | celle de la RSA | RSA | cohérente, inchangée |
| `key-two-lines` | celle de A, puis celle de B sur une deuxième ligne | A | ramenée à une ligne (la clé B devient du commentaire), cohérente avec A, inchangée |
| `key-kept` | celle de B | B | cohérente, inchangée |

Compteurs de `ValidatedBackup` : tout à 0 (`invalidTotal` 0). Cinq clés SSH, cinq clés privées, une passphrase.

Restauration sur un appareil vierge (aucune clé, vault vide) : 5 clés et 5 clés privées écrites, 1 passphrase,
`publicKeysRecomputed` 1 (`key-a-line-b`, avertissement `PUBLIC_KEYS_RECOMPUTED`), `localKeysKept` 0. Avec
le dériveur mémorisé (`memoizedWithoutPassphrase`), 4 dérivations : la clé A, présente deux fois, n'est dérivée
qu'une fois.

Restauration sur un appareil qui a déjà `key-kept` (sa ligne : celle de A ; dans le vault : la clé privée A,
différente de celle du fichier) : la clé privée du fichier n'est pas écrite, la ligne locale reste, le libellé
du fichier est repris, `localKeysKept` 1 (avertissement `LOCAL_KEYS_KEPT`), `publicKeysRecomputed` 1, 4 clés
privées écrites.

### `v3.json`

`"version": 3` avec une section `hosts` de structure différente. Attendu : refus
`UNSUPPORTED_VERSION` (« mettez l'application à jour »), rien d'écrit. La version est lue avant le
décodage des sections.

## Règles

- Ne jamais y mettre de vrai secret ni de vraie clé privée, hors les clés de test jetables de
  `v2-cles-incoherentes`, générées pour ce fichier.
- Un changement de ces fichiers change les compteurs attendus des tests Android, Desktop et `:shared` :
  mettre ce tableau à jour dans le même commit.
