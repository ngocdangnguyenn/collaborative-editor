#!/bin/bash
# test-convergence.sh — Tâche 2 : convergence ServerCentralPush
# 8 clients parallèles, 20 opérations chacun, vérification de convergence finale
set -e
cd "$(dirname "$0")/../src/main/java"

N=8
OPS=20
HOST=localhost
PORT=5000
RESULTS=convergence_results
BARRIER=barrier

echo "=== Compilation ==="
javac ServerCentralPush.java AutoClient.java
echo "OK"

rm -rf "$RESULTS" "$BARRIER"
mkdir -p "$RESULTS" "$BARRIER"

echo ""
echo "=== Démarrage serveur (port $PORT) ==="
java ServerCentralPush "$PORT" &
SERVER_PID=$!
sleep 1

echo ""
echo "=== Lancement de $N clients ($OPS ops chacun) ==="
for i in $(seq 1 "$N"); do
    java AutoClient "$HOST" "$PORT" "$i" "$OPS" "$RESULTS/result_$i.txt" "$N" "$BARRIER" &
done

echo ""
echo "=== Attente de la fin des clients ==="
while true; do
    count=0
    for i in $(seq 1 "$N"); do
        [ -f "$RESULTS/result_$i.txt" ] && count=$((count + 1))
    done
    [ "$count" -ge "$N" ] && break
    sleep 2
done
echo "Tous les clients ont terminé."

kill "$SERVER_PID" 2>/dev/null || true
echo "Serveur arrêté."

echo ""
echo "=== Vérification de la convergence ==="
converged=1
for i in $(seq 2 "$N"); do
    if diff -q "$RESULTS/result_1.txt" "$RESULTS/result_$i.txt" > /dev/null 2>&1; then
        echo "  Client $i  OK  (identique au client 1)"
    else
        echo "  Client $i  [DIVERGENCE]"
        converged=0
    fi
done

echo ""
echo "=== Résultat final ==="
if [ "$converged" -eq 1 ]; then
    echo "  CONVERGENCE : OK — tous les clients ont le même document."
else
    echo "  CONVERGENCE : ÉCHEC — les documents divergent."
fi
