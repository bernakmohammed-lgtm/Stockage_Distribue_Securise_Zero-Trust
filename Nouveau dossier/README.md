# Stockage Distribué Sécurisé

Projet de stockage distribué avec chiffrement de bout en bout, authentification Zero-Trust (JWT + PoP), et réplication sur nœuds de stockage.

## Stack technique

- Java 17
- Maven
- AES-256-GCM (`javax.crypto`)
- JWT (`io.jsonwebtoken`)
- JSON / Sockets TCP (pas de framework lourd)

## Architecture

- **Client** : chiffre les fichiers, découpe en blocs (Merkle DAG), upload/download.
- **Serveur central** : authentification, gestion des droits (ACL), forwarding des blocs vers les nœuds.
- **Nœuds de stockage** : stockage des blocs chiffrés, heartbeat entre pairs, réparation (LWW).

## Build

```bash
mvn package -DskipTests
```

Le fat-jar (avec dépendances) se trouve dans :
- `target/stockage-distribue-1.0.0-SNAPSHOT-all.jar`

## Démarrage

### Option 1 : Via le lanceur global (recommandé)

Le lanceur `Main.java` permet de démarrer tous les composants facilement.

#### Démarrage complet (mode demo)

```bash
java -cp target/stockage-distribue-1.0.0-SNAPSHOT-all.jar com.stockage.Main demo
```

Cela lance automatiquement 3 nœuds de stockage et le serveur central.

#### Démarrage manuel des composants

**Lancer un nœud de stockage :**
```bash
java -cp target/stockage-distribue-1.0.0-SNAPSHOT-all.jar com.stockage.Main storage-node 9101 data/node1 127.0.0.1:9102 127.0.0.1:9103
```

**Lancer le serveur central :**
```bash
java -cp target/stockage-distribue-1.0.0-SNAPSHOT-all.jar com.stockage.Main server 9000 127.0.0.1:9101 127.0.0.1:9102 127.0.0.1:9103
```

**Upload (alice) :**
```bash
java -cp target/stockage-distribue-1.0.0-SNAPSHOT-all.jar com.stockage.Main client upload 127.0.0.1 9000 alice alice monfichier.txt client.keystore password
```

Le client affiche le **CID** (identifiant unique du fichier).

**Download (alice) :**
```bash
java -cp target/stockage-distribue-1.0.0-SNAPSHOT-all.jar com.stockage.Main client download 127.0.0.1 9000 alice alice <CID> monfichier.recupere.txt client.keystore password
```

**Partage avec bob :**
```bash
java -cp target/stockage-distribue-1.0.0-SNAPSHOT-all.jar com.stockage.Main client share 127.0.0.1 9000 alice alice <CID> bob
```

Après partage, `bob` peut télécharger le fichier (s'il reçoit la clé AES hors bande).

### Option 2 : Démarrage manuel (composants individuels)

#### 1. Lancer 3 nœuds de stockage

```bash
java -cp target/stockage-distribue-1.0.0-SNAPSHOT-all.jar com.stockage.storage.StorageNode 9101 data/node1 127.0.0.1:9102 127.0.0.1:9103
java -cp target/stockage-distribue-1.0.0-SNAPSHOT-all.jar com.stockage.storage.StorageNode 9102 data/node2 127.0.0.1:9101 127.0.0.1:9103
java -cp target/stockage-distribue-1.0.0-SNAPSHOT-all.jar com.stockage.storage.StorageNode 9103 data/node3 127.0.0.1:9101 127.0.0.1:9102
```

#### 2. Lancer le serveur central

```bash
java -cp target/stockage-distribue-1.0.0-SNAPSHOT-all.jar com.stockage.server.Server 9000 127.0.0.1:9101 127.0.0.1:9102 127.0.0.1:9103
```

#### 3. Upload (alice)

```bash
java -cp target/stockage-distribue-1.0.0-SNAPSHOT-all.jar com.stockage.client.Client upload 127.0.0.1 9000 alice alice monfichier.txt client.keystore password
```

#### 4. Download (alice)

```bash
java -cp target/stockage-distribue-1.0.0-SNAPSHOT-all.jar com.stockage.client.Client download 127.0.0.1 9000 alice alice <CID> monfichier.recupere.txt client.keystore password
```

#### 5. Partage avec bob

```bash
java -cp target/stockage-distribue-1.0.0-SNAPSHOT-all.jar com.stockage.client.Client share 127.0.0.1 9000 alice alice <CID> bob
```

## Tests

```bash
mvn test
```

Tests inclus :
- Chiffrement/déchiffrement AES-GCM (intégrité + altération)
- Merkle DAG (découpage, CID déterministe)
- Upload / download complet
- Accès non autorisé (doit échouer)
- Réplication sur les nœuds

## Limites documentées

- **Proxy Re-Encryption** : le serveur ne re-chiffre pas réellement la clé AES du fichier. L'ACL autorise simplement l'accès aux blocs chiffrés. La transmission de la clé AES entre owner et reader doit se faire hors bande.
- Les données du serveur (owner, ACL, blockCount par CID) sont stockées en mémoire (pas de persistance sur disque).
- Le heartbeat et la réparation sont simplifiés : les nœuds se connectent directement entre eux tous les 10 secondes.

## Structure du code

```
src/main/java/com/stockage/
├── Main.java                    # Lanceur global (demo + composants individuels)
├── client/
│   ├── Client.java              # Point d'entrée client (upload/download/share)
│   ├── FileEncryptor.java       # AES-GCM encryption/decryption
│   └── MerkleDAG.java           # Découpage + arbre de hashes (Merkle tree)
├── server/
│   ├── Server.java              # Serveur central (gestion droits + forwarding)
│   ├── AuthHandler.java         # JWT + login + proof-of-possession
│   ├── AccessControl.java       # ACL (owner + readers)
│   └── ProxyReEncrypt.java      # Module Proxy Re-Encryption (simulation)
├── storage/
│   ├── StorageNode.java         # Nœud de stockage (démon)
│   ├── BlockManager.java        # Stockage des blocs sur disque
│   └── Replication.java         # Réplication + heartbeat + réparation
└── common/
    ├── Message.java             # Définitions des messages réseau
    ├── StreamUtils.java         # Utilitaires I/O réseau
    └── CryptoUtils.java         # Utilitaires crypto (RSA, SHA-256)
```


## Vidéo démo
https://drive.google.com/file/d/1_LRO7_vi469lq3Wc1CSrXEyHhDleFMk_/view?usp=sharing
