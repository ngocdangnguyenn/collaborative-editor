# Éditeur collaboratif — Groupe A9

## Auteurs
* NICAISE Enzo  
* NGUYEN Ngoc Dang Nguyen

Ce projet implémente un éditeur de texte collaboratif en Java, en partant d'un serveur centralisé simple (tâche 1) jusqu'à un cluster tolérant aux pannes basé sur Raft (tâche 7). L'unité d'édition est la ligne ; plusieurs clients peuvent lire et modifier un document partagé simultanément.

---

## Prérequis

- **Java 21** (les sources utilisent `--enable-preview` pour les nouvelles syntaxes)
- **JavaFX 23** — téléchargé automatiquement par Gradle via Maven Central, connexion internet requise à la première exécution du client graphique
- **Python 3** avec `matplotlib` — uniquement pour tracer les courbes de benchmark (`plot-bench.py`)

Testé sur Ubuntu 22.04 / macOS avec le JDK Temurin 21.

---

## Structure du projet

```
editor/
├── build.gradle / settings.gradle / gradlew   ← build JavaFX (GUI uniquement)
├── RAPPORT.md / README.md
├── scripts/
│   ├── test-convergence.sh   ← convergence ServerCentralPush (tâche 2)
│   ├── test-federation.sh    ← fédération simple 2 et 3 serveurs (tâche 4)
│   ├── test-master.sh        ← fédération avec serveur maître (tâche 5)
│   ├── test-dispatch.sh      ← validation + dispatch, 6 clients (tâche 6)
│   ├── test-spof.sh          ← démonstration SPOF, ServerMasterFaulty (tâche 7A)
│   ├── test-raft.sh          ← Raft : fonctionnement normal + panne d'un nœud (tâche 7B)
│   ├── bench.sh              ← benchmark latence/débit, scénarios A et B
│   ├── find-leader.sh        ← détection du leader Raft (utilisé par test-raft.sh)
│   └── plot-bench.py         ← tracé des courbes benchmark (matplotlib)
└── src/main/
    ├── java/
    │   ├── ServerCentral.java
    │   ├── ServerCentralPush.java
    │   ├── ServerFederated.java
    │   ├── ServerMaster.java
    │   ├── ServerMasterFaulty.java
    │   ├── ServerDispatch.java
    │   ├── ServerRaft.java
    │   ├── AutoClient.java
    │   ├── BenchClient.java
    │   ├── ClientController.java
    │   └── GUIClient.java
    └── resources/
        └── clientView.fxml
```

---

## Compilation

### Serveurs et clients automatiques

Les scripts de test recompilent automatiquement. Pour compiler manuellement :

```bash
cd editor/src/main/java
javac *.java
```

### Client graphique (GUIClient)

JavaFX ne se compile pas bien en `javac` direct à cause du classpath des modules. On utilise Gradle qui gère cela proprement :

```bash
# Depuis editor/
./gradlew run
```

---

## Lancement manuel

Depuis `editor/src/main/java/` après compilation :

```bash
# Tâche 1 — Centralisé
java ServerCentral 5000

# Tâche 2 — Push
java ServerCentralPush 5000

# Tâche 3/4 — Fédération
java ServerFederated 5000 &
java ServerFederated 5001
# Connexion B → A via TCP :
python3 -c "import socket,time; s=socket.socket(); s.connect(('localhost',5001)); s.send(b'CONNECT localhost 5000\n'); time.sleep(2); s.close()"

# Tâche 5 — Maître + esclaves
java ServerMaster 5000 peers.cfg &   # maître
java ServerMaster 5001 peers.cfg     # esclave

# Tâche 6 — Dispatch
java ServerDispatch 4999 dispatch.cfg

# Tâche 7B — Raft (3 nœuds)
java ServerRaft 5000 raft_1.cfg &
java ServerRaft 5001 raft_2.cfg &
java ServerRaft 5002 raft_3.cfg
```

Exemple de `peers.cfg` pour la tâche 5 :
```
master = localhost 5000
peer   = localhost 5001
peer   = localhost 5002
```

---

## Scripts de test automatisés

Depuis `editor/scripts/`. Chaque script compile les classes requises, lance les serveurs en arrière-plan, exécute les clients avec barrière de synchronisation, compare les documents finaux, puis arrête les processus. En sortie normale : `[CONVERGENCE OK]`.

```bash
chmod +x scripts/*.sh   # une seule fois, pour rendre les scripts exécutables

./scripts/test-convergence.sh   # tâche 2 : convergence ServerCentralPush
./scripts/test-federation.sh    # tâche 4 : fédération simple 2 et 3 serveurs
./scripts/test-master.sh        # tâche 5 : fédération maître-esclaves
./scripts/test-dispatch.sh      # tâche 6 : 6 clients via dispatch
./scripts/test-spof.sh          # tâche 7A : démonstration SPOF (maître gelé puis crashé)
./scripts/test-raft.sh          # tâche 7B : Raft, Test A normal + Test B panne d'un nœud
```

Pour les tâches 1 et 2, utiliser `test-convergence.sh` ou lancer le client graphique avec `./gradlew run`.

### Benchmark (tâche 6)

Le script fait varier le nombre de clients (1, 2, 4, 8) et de serveurs (2, 3, 5, 10), mesure la latence moyenne et le débit, et écrit les résultats dans `bench_results.csv` :

```bash
# Depuis editor/scripts/
./bench.sh

# Pour tracer les courbes :
python3 plot-bench.py
```

---

## Protocole (résumé)

### Client → Serveur

| Commande | Description |
|---|---|
| `GETD` | Obtenir le document complet |
| `GETL i` | Obtenir la ligne i |
| `ADDL i texte` | Insérer une ligne en position i |
| `MDFL i texte` | Modifier la ligne i |
| `RMVL i` | Supprimer la ligne i |
| `GETSERVER` | (dispatch) Demander l'adresse d'un serveur disponible |

### Serveur → Client

| Commande | Description |
|---|---|
| `LINE i ver texte` | Contenu de la ligne i, version ver (réponse GETD ou push modification) |
| `ADDL i ver texte` | Notification push : insertion à la position i |
| `RMVL i` | Notification push : suppression de la ligne i |
| `DONE` | Fin d'opération ou fin de transfert GETD |
| `ERRL [i] msg` | Erreur (version obsolète, nœud non-leader, etc.) |
| `SERVER host port` | (dispatch) Adresse du serveur assigné |

