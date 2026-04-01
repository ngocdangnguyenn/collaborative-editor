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
RAPPORT.md / README.md
editor/
├── build.gradle / settings.gradle / gradlew
├── scripts/
│   ├── test-convergence.sh
│   ├── test-federation.sh
│   ├── test-master.sh
│   ├── test-dispatch.sh
│   ├── test-spof.sh
│   ├── test-raft.sh
│   ├── bench.sh
│   ├── find-leader.sh
│   └── plot-bench.py
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

```bash
cd editor/src/main/java
javac *.java
```

### Client graphique (GUIClient)

```bash
# Depuis editor/
./gradlew run
```

---

## Lancement manuel

Depuis `editor/src/main/java/` :

```bash
java ServerCentral 5000
java ServerCentralPush 5000
java ServerFederated 5000
java ServerMaster 5000 peers.cfg
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

Depuis `editor/`. Chaque script compile, lance les serveurs, exécute les tests avec barrière de synchronisation, puis affiche le résultat de convergence.

```bash
chmod +x scripts/*.sh

./scripts/test-convergence.sh   # Tâche 2 : ServerCentralPush
./scripts/test-federation.sh    # Tâche 4 : fédération 2 et 3 serveurs
./scripts/test-master.sh        # Tâche 5 : maître-esclaves
./scripts/test-dispatch.sh      # Tâche 6 : 6 clients via dispatch
./scripts/test-spof.sh          # Tâche 7A : démonstration SPOF
./scripts/test-raft.sh          # Tâche 7B : Raft
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

