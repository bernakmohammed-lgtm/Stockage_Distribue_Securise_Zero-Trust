# Rapport de Projet : Stockage Distribué Sécurisé

## Résumé

Ce projet présente un système de stockage distribué sécurisé implémentant un chiffrement de bout en bout, une authentification Zero-Trust basée sur JWT et Proof-of-Possession, ainsi qu'une réplication automatique des données sur des nœuds de stockage distribués. Le système garantit la confidentialité, l'intégrité et la disponibilité des données stockées tout en offrant un mécanisme de partage sécurisé entre utilisateurs.

---

## 1. Introduction

### 1.1 Contexte

Dans un monde où les données sont de plus en plus volumineuses et sensibles, le stockage distribué sécurisé représente un défi majeur. Les solutions existantes nécessitent souvent de faire confiance à un tiers centralisé, ce qui introduit des risques de sécurité et de confidentialité.

### 1.2 Objectifs

Ce projet vise à concevoir et implémenter un système de stockage distribué qui :
- Garantit la confidentialité des données via un chiffrement de bout en bout
- Assure l'intégrité des données grâce à des arbres de Merkle
- Permet une authentification Zero-Trust sans confiance implicite en un serveur central
- Assure la disponibilité des données via réplication sur plusieurs nœuds
- Offre un mécanisme de partage sécurisé entre utilisateurs

### 1.3 Approche

L'approche adoptée combine plusieurs concepts avancés de sécurité et de systèmes distribués :
- Chiffrement AES-256-GCM pour la confidentialité et l'intégrité
- Arbres de Merkle (Merkle DAG) pour l'intégrité structurelle
- JWT (JSON Web Tokens) pour l'authentification
- Proof-of-Possession (PoP) pour la validation des requêtes
- Réplication Last-Writer-Wins (LWW) pour la cohérence distribuée

---

## 2. Architecture du Système

### 2.1 Vue d'ensemble

Le système est composé de trois types de composants :

```
┌─────────────┐         ┌──────────────┐         ┌─────────────┐
│   Client    │◄────────►│  Serveur     │◄────────►│  Nœud 1     │
│             │  TCP/IP  │  Central     │  TCP/IP  │  Stockage   │
└─────────────┘         └──────────────┘         └─────────────┘
                                                      │
                                                      ▼
                                              ┌─────────────┐
                                              │  Nœud 2     │
                                              │  Stockage   │
                                              └─────────────┘
                                                      │
                                                      ▼
                                              ┌─────────────┐
                                              │  Nœud 3     │
                                              │  Stockage   │
                                              └─────────────┘
```

### 2.2 Composant Client

Le client est responsable de :
- **Chiffrement des fichiers** : Utilisation d'AES-256-GCM avec des clés générées aléatoirement
- **Découpage en blocs** : Division du fichier chiffré en blocs de 256 Ko
- **Construction du Merkle DAG** : Génération d'un arbre de hachages pour l'intégrité
- **Communication avec le serveur** : Envoi des blocs et gestion des opérations upload/download/share
- **Gestion des clés** : Stockage sécurisé des clés AES dans un keystore JCEKS

### 2.3 Serveur Central

Le serveur central assure :
- **Authentification** : Vérification des identités via JWT
- **Contrôle d'accès** : Gestion des ACL (Access Control Lists) owner/readers
- **Forwarding** : Acheminement des blocs vers les nœuds de stockage
- **Proxy Re-Encryption** : Module (simulé) pour le partage de fichiers
- **Validation PoP** : Vérification de la possession de la clé privée

### 2.4 Nœuds de Stockage

Les nœuds de stockage sont responsables de :
- **Stockage des blocs** : Persistance des blocs chiffrés sur disque
- **Réplication** : Synchronisation automatique entre pairs
- **Heartbeat** : Échange périodique de métadonnées (toutes les 10 secondes)
- **Réparation** : Récupération automatique des blocs manquants ou obsolètes
- **Résolution de conflits** : Stratégie Last-Writer-Wins basée sur les timestamps

---

## 3. Mécanismes de Sécurité

### 3.1 Chiffrement de Bout en Bout

#### 3.1.1 Algorithme

Le système utilise **AES-256-GCM** (Galois/Counter Mode) qui offre :
- **Confidentialité** : Chiffrement symétrique avec une clé de 256 bits
- **Intégrité** : Tag d'authentification de 128 bits
- **Nonce unique** : 12 octets générés aléatoirement pour chaque chiffrement

#### 3.1.2 Implémentation

```java
// Génération de clé AES-256
SecretKey fileKey = KeyGenerator.getInstance("AES").generateKey();

// Chiffrement
Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
byte[] nonce = new byte[12]; // Nonce aléatoire
cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
byte[] ciphertext = cipher.doFinal(plaintext);
```

#### 3.1.3 Gestion des Clés

Les clés AES sont stockées localement dans un keystore JCEKS protégé par mot de passe. Le serveur n'a jamais accès aux clés de chiffrement, garantissant un véritable chiffrement de bout en bout.

### 3.2 Arbres de Merkle (Merkle DAG)

#### 3.2.1 Principe

Les fichiers sont découpés en blocs de 256 Ko, et chaque bloc est haché avec SHA-256. Ces hachés sont organisés en arbre binaire pour produire une racine unique (CID - Content Identifier).

#### 3.2.2 Avantages

- **Intégrité vérifiable** : Toute altération d'un bloc est détectable
- **Identification déterministe** : Le même fichier produit toujours le même CID
- **Vérification partielle** : Possibilité de vérifier des sous-parties du fichier
- **Déduplication** : Les blocs identiques partagent le même haché

#### 3.2.3 Construction

```
Bloc 1 ──┐
         ├── H(H1 + H2) ──┐
Bloc 2 ──┘                 ├── H(H12 + H34) = CID
                           │
Bloc 3 ──┐                 │
         ├── H(H3 + H4) ──┘
Bloc 4 ──┘
```

### 3.3 Authentification Zero-Trust

#### 3.3.1 JWT (JSON Web Tokens)

Le système utilise des JWT signés avec HMAC-SHA256 pour l'authentification :
- **Subject** : Nom d'utilisateur
- **Claim "pkh"** : Hash de la clé publique RSA de l'utilisateur
- **Expiration** : 5 minutes
- **Signature** : Clé secrète HMAC partagée avec le serveur

#### 3.3.2 Proof-of-Possession (PoP)

Pour valider qu'un client possède bien la clé privée correspondant à sa clé publique :
1. Le client enregistre sa clé publique RSA auprès du serveur
2. Le serveur inclut le hash de la clé publique (pkh) dans le JWT
3. Le client signe chaque requête avec sa clé privée
4. Le serveur vérifie la signature avec la clé publique enregistrée

#### 3.3.3 Flux d'Authentification

```
1. Client → Serveur : username, password, publicKey
2. Serveur : Vérifie credentials, enregistre publicKey
3. Serveur → Client : JWT (avec pkh)
4. Client → Serveur : Requête + JWT + Signature RSA
5. Serveur : Vérifie JWT, extrait pkh, vérifie signature
```

### 3.4 Contrôle d'Accès (ACL)

Le serveur maintient une liste de contrôle d'accès pour chaque fichier (identifié par son CID) :
- **Owner** : L'utilisateur qui a uploadé le fichier
- **Readers** : Liste des utilisateurs autorisés à télécharger le fichier

L'accès est vérifié avant toute opération de téléchargement ou de partage.

### 3.5 Proxy Re-Encryption (Simulé)

Le système inclut un module de Proxy Re-Encryption qui permettrait théoriquement au serveur de re-chiffrer la clé AES d'un fichier pour un autre utilisateur sans jamais voir la clé en clair. Dans cette implémentation, l'ACL autorise simplement l'accès aux blocs chiffrés, et la transmission de la clé AES doit se faire hors bande.

---

## 4. Mécanismes de Distribution

### 4.1 Réplication des Blocs

Les blocs sont répliqués sur plusieurs nœuds de stockage pour assurer la disponibilité :
- Le serveur distribue les blocs sur tous les nœuds disponibles
- Chaque nœud stocke une copie de chaque bloc
- La répartition est équilibrée entre les nœuds

### 4.2 Heartbeat entre Pairs

Toutes les 10 secondes, chaque nœud :
1. Envoie sa liste de blocs (CID, index, timestamp) à tous ses pairs
2. Reçoit la liste de blocs de chaque pair
3. Compare les listes pour détecter les blocs manquants ou obsolètes

### 4.3 Réparation Automatique

Si un nœud détecte un bloc manquant ou obsolète :
1. Il contacte le pair qui possède la version la plus récente
2. Il télécharge le bloc via une requête GET_BLOCK
3. Il stocke le bloc localement avec le nouveau timestamp

### 4.4 Résolution de Conflits (LWW)

En cas de conflit (même bloc avec des timestamps différents), la stratégie **Last-Writer-Wins** est appliquée :
- Le bloc avec le timestamp le plus récent est conservé
- Cette approche garantit la convergence finale du système

---

## 5. Implémentation Technique

### 5.1 Stack Technologique

- **Langage** : Java 17
- **Build** : Maven
- **Chiffrement** : `javax.crypto` (AES-GCM), RSA
- **Authentification** : `io.jsonwebtoken` (JWT)
- **Sérialisation** : `com.google.gson` (JSON)
- **Réseau** : Sockets TCP natifs (pas de framework lourd)
- **Tests** : JUnit 5

### 5.2 Structure du Code

```
src/main/java/com/stockage/
├── Main.java                    # Lanceur global (demo + composants)
├── client/
│   ├── Client.java              # Point d'entrée client
│   ├── FileEncryptor.java       # AES-GCM encryption/decryption
│   └── MerkleDAG.java           # Découpage + arbre de Merkle
├── server/
│   ├── Server.java              # Serveur central
│   ├── AuthHandler.java         # JWT + login + PoP
│   ├── AccessControl.java       # ACL (owner + readers)
│   └── ProxyReEncrypt.java      # Module Proxy Re-Encryption
├── storage/
│   ├── StorageNode.java         # Nœud de stockage
│   ├── BlockManager.java        # Stockage des blocs
│   └── Replication.java         # Réplication + heartbeat
└── common/
    ├── Message.java             # Définitions des messages réseau
    ├── StreamUtils.java         # Utilitaires I/O
    └── CryptoUtils.java         # Utilitaires crypto (RSA, SHA-256)
```

### 5.3 Protocole de Communication

Le système utilise un protocole simple basé sur JSON sur TCP :
- Chaque message est une ligne JSON terminée par `\n`
- Les données binaires (blocs) sont envoyées après le message JSON
- Types de messages principaux :
  - `UPLOAD_BLOCK` : Envoi d'un bloc
  - `DOWNLOAD_BLOCK` : Téléchargement d'un bloc
  - `HEARTBEAT` : Échange de métadonnées entre nœuds
  - `GET_BLOCK` : Récupération d'un bloc spécifique

---

## 6. Scénarios d'Utilisation

### 6.1 Upload d'un Fichier

```
1. Alice chiffre son fichier avec AES-256-GCM
2. Alice découpe le fichier chiffré en blocs de 256 Ko
3. Alice construit le Merkle DAG et obtient le CID
4. Alice s'authentifie auprès du serveur (username/password + clé publique)
5. Le serveur émet un JWT pour Alice
6. Alice envoie chaque bloc au serveur avec signature PoP
7. Le serveur vérifie la signature et forward les blocs aux nœuds
8. Les nœuds stockent les blocs et les répliquent entre eux
9. Alice sauvegarde la clé AES dans son keystore local
```

### 6.2 Téléchargement d'un Fichier

```
1. Alice s'authentifie auprès du serveur
2. Alice demande le téléchargement d'un CID
3. Le serveur vérifie l'ACL (Alice est owner ou reader)
4. Le serveur renvoie la liste des nœuds possédant les blocs
5. Alice télécharge chaque bloc depuis un nœud
6. Alice vérifie l'intégrité avec le Merkle DAG
7. Alice déchiffre les blocs avec sa clé AES
8. Alice reconstruit le fichier original
```

### 6.3 Partage d'un Fichier

```
1. Alice s'authentifie auprès du serveur
2. Alice demande de partager un CID avec Bob
3. Le serveur vérifie qu'Alice est owner
4. Le serveur ajoute Bob à la liste des readers
5. Alice transmet la clé AES à Bob hors bande
6. Bob peut maintenant télécharger le fichier
```

---

## 7. Tests et Validation

### 7.1 Suite de Tests

Le projet inclut une suite de tests JUnit 5 couvrant :
- **Chiffrement/déchiffrement AES-GCM** : Vérification de la confidentialité et de l'intégrité
- **Merkle DAG** : Validation du découpage et du CID déterministe
- **Upload/download complet** : Test de bout en bout du flux
- **Accès non autorisé** : Vérification qu'un utilisateur non autorisé ne peut pas télécharger
- **Réplication** : Test de la synchronisation entre nœuds

### 7.2 Exécution des Tests

```bash
mvn test
```

### 7.3 Mode Démonstration

Un mode démo permet de lancer rapidement l'environnement complet :
```bash
java -cp target/stockage-distribue-1.0.0-SNAPSHOT-all.jar com.stockage.Main demo
```

Cette commande lance automatiquement :
- 3 nœuds de stockage (ports 9101, 9102, 9103)
- 1 serveur central (port 9000)

---

## 8. Limitations et Perspectives

### 8.1 Limitations Actuelles

1. **Proxy Re-Encryption** : Le serveur ne re-chiffre pas réellement la clé AES. La transmission de la clé entre owner et reader doit se faire hors bande.

2. **Persistance du serveur** : Les données du serveur (owner, ACL, blockCount) sont stockées en mémoire uniquement. Un redémarrage du serveur efface ces métadonnées.

3. **Heartbeat simplifié** : L'intervalle de 10 secondes est arbitraire et pourrait être optimisé. La réparation est synchrone et pourrait bloquer le nœud.

4. **Scalabilité** : Le nombre de nœuds est fixe et configuré manuellement. Le système ne supporte pas l'ajout dynamique de nœuds.

5. **Gestion des utilisateurs** : Les utilisateurs sont hardcodés dans le code (alice/alice, bob/bob). Pas de base de données utilisateurs.

### 8.2 Améliorations Possibles

1. **Proxy Re-Encryption complet** : Implémentation d'un vrai schéma de re-chiffrement proxy (ex: BBS98 ou ElGamal).

2. **Persistance** : Utilisation d'une base de données (PostgreSQL, MongoDB) pour stocker les métadonnées du serveur.

3. **Discovery dynamique** : Implémentation d'un service de découverte pour permettre l'ajout dynamique de nœuds.

4. **Optimisation du heartbeat** : Utilisation d'un protocole de gossip plus efficace (ex: SWIM).

5. **Compression** : Ajout de compression avant chiffrement pour réduire la taille des blocs.

6. **Erasure coding** : Remplacement de la réplication par un codage d'effacement pour une meilleure efficacité de stockage.

7. **Interface web** : Développement d'une interface utilisateur web pour faciliter l'utilisation.

8. **Audit logging** : Journalisation de toutes les opérations pour la traçabilité.

---

## 9. Conclusion

Ce projet a permis de concevoir et implémenter un système de stockage distribué sécurisé combinant plusieurs concepts avancés de sécurité et de systèmes distribués. L'architecture proposée garantit la confidentialité, l'intégrité et la disponibilité des données tout en offrant un mécanisme de partage sécurisé.

L'implémentation en Java pur, sans framework lourd, démontre la faisabilité technique d'un tel système avec une stack technologique minimaliste. Les tests unitaires valident le bon fonctionnement des composants principaux.

Les limitations identifiées ouvrent des perspectives intéressantes pour des travaux futurs, notamment l'implémentation complète du Proxy Re-Encryption, l'ajout de persistance, et l'amélioration de la scalabilité du système.

Ce projet constitue une base solide pour comprendre les défis liés au stockage distribué sécurisé et les solutions cryptographiques et algorithmiques qui peuvent être mises en œuvre pour y répondre.

---

## 10. Références

### 10.1 Concepts Théoriques

- **AES-GCM** : NIST Special Publication 800-38D
- **Merkle Trees** : Merkle, R. C. (1987). "A Digital Signature Based on a Conventional Encryption Function"
- **JWT** : RFC 7519 - JSON Web Token
- **Proxy Re-Encryption** : Blaze, M., Bleumer, G., & Strauss, M. (1998). "Divertible Protocols and Atomic Proxy Cryptography"
- **Last-Writer-Wins** : Vogels, W. (2009). "Eventually Consistent"

### 10.2 Technologies

- Java 17 Documentation
- javax.crypto API
- JJWT Library (io.jsonwebtoken:jjwt:0.12.6)
- Gson Library (com.google.code.gson:gson:2.11.0)
- JUnit 5 (org.junit.jupiter:junit-jupiter:5.10.2)

---

## Annexe A : Guide d'Installation

### A.1 Prérequis

- Java 17 ou supérieur
- Maven 3.6 ou supérieur

### A.2 Compilation

```bash
cd Nouveau dossier/Nouveau dossier
mvn package -DskipTests
```

Le fat-jar est généré dans : `target/stockage-distribue-1.0.0-SNAPSHOT-all.jar`

### A.3 Démarrage

#### Mode Démo
```bash
java -cp target/stockage-distribue-1.0.0-SNAPSHOT-all.jar com.stockage.Main demo
```

#### Mode Manuel
Voir le fichier README.md pour les commandes détaillées de démarrage des composants individuels.

---

## Annexe B : Exemple de Session

### B.1 Upload

```bash
java -cp target/stockage-distribue-1.0.0-SNAPSHOT-all.jar com.stockage.Main client upload 127.0.0.1 9000 alice alice monfichier.txt client.keystore password
```

Résultat : `CID: a1b2c3d4e5f6...`

### B.2 Download

```bash
java -cp target/stockage-distribue-1.0.0-SNAPSHOT-all.jar com.stockage.Main client download 127.0.0.1 9000 alice alice a1b2c3d4e5f6... monfichier.recupere.txt client.keystore password
```

### B.3 Share

```bash
java -cp target/stockage-distribue-1.0.0-SNAPSHOT-all.jar com.stockage.Main client share 127.0.0.1 9000 alice alice a1b2c3d4e5f6... bob
```

---

**Fin du Rapport**
