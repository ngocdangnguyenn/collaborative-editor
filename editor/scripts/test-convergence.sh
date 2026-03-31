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

echo "Compilation..." 
javac ServerCentralPush.java AutoClient.java

rm -rf "$RESULTS" "$BARRIER"
mkdir -p "$RESULTS" "$BARRIER"

echo "Démarrage serveur..."
for i in $(seq 1 "$N"); do
    java AutoClient "$HOST" "$PORT" "$i" "$OPS" "$RESULTS/result_$i.txt" "$N" "$BARRIER" &
done

echo "Attente des clients..."
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

echo "Vérification de la convergence..."
converged=1
for i in $(seq 2 "$N"); do
    if diff -q "$RESULTS/result_1.txt" "$RESULTS/result_$i.txt" > /dev/null 2>&1; then
        echo "  Client $i: identique"
    else
        echo "  Client $i: DIVERGENCE"
        converged=0
    fi
done

if [ "$converged" -eq 1 ]; then
    echo "[PASS] Convergence confirmed"
else
    echo "[FAIL] Convergence check failed"
fi
