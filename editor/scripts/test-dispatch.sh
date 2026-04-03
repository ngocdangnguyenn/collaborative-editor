#!/bin/bash
cd "$(dirname "$0")/../src/main/java"

HOST=localhost
DISPATCH_PORT=4999
PORT_M=5000
PORT_S1=5001
PORT_S2=5002
OPS=8
NCLIENTS=6

echo "===================================================="
echo " TÂCHE 6 : Validation fonctionnelle avec dispatch"
echo " 3 serveurs (1 maître + 2 esclaves) + ServerDispatch"
echo " $NCLIENTS clients parallèles via dispatch"
echo "===================================================="
echo ""
echo "[1/2] Compilation..."
javac ServerMaster.java ServerDispatch.java BenchClient.java AutoClient.java
echo "OK"

pkill -f "java Server" 2>/dev/null || true
sleep 2

cat > peers_t6.cfg <<EOF
master = $HOST $PORT_M
peer   = $HOST $PORT_S1
peer   = $HOST $PORT_S2
EOF

cat > dispatch_t6.cfg <<EOF
server = $HOST $PORT_M
server = $HOST $PORT_S1
server = $HOST $PORT_S2
EOF

rm -rf res_t6 bar_t6
mkdir -p res_t6 bar_t6

echo ""
echo "Démarrage : Maître ($PORT_M) + Esclave1 ($PORT_S1) + Esclave2 ($PORT_S2) + Dispatch ($DISPATCH_PORT)"
java ServerMaster  "$PORT_M"          peers_t6.cfg    &
sleep 2
java ServerMaster  "$PORT_S1"         peers_t6.cfg    &
java ServerMaster  "$PORT_S2"         peers_t6.cfg    &
sleep 3
java ServerDispatch "$DISPATCH_PORT"  dispatch_t6.cfg &
sleep 2

echo "Serveurs prêts. Lancement de $NCLIENTS clients via dispatch (barrière=$NCLIENTS)..."
for i in $(seq 1 "$NCLIENTS"); do
    java BenchClient dispatch "$HOST" "$DISPATCH_PORT" "$i" "$OPS" 80 \
        "res_t6/result_$i.txt" "$NCLIENTS" bar_t6 &
done

while true; do
    cnt=0
    for i in $(seq 1 "$NCLIENTS"); do
        [ -f "res_t6/result_$i.txt" ] && cnt=$((cnt+1))
    done
    [ "$cnt" -ge "$NCLIENTS" ] && break
    sleep 2
done
echo "Tous les clients ont terminé."

pkill -f "java Server" 2>/dev/null || true
sleep 2

echo ""
echo "===================================================="
echo " Résultats (documents finaux de chaque client)"
echo "===================================================="
for i in $(seq 1 "$NCLIENTS"); do
    echo ""; echo "--- Client $i ---"; cat "res_t6/result_$i.txt"
done

echo ""
echo "===================================================="
echo " Vérification de convergence"
echo "===================================================="
for i in $(seq 1 "$NCLIENTS"); do
    tail -n +2 "res_t6/result_$i.txt" > "res_t6/doc_$i.txt" 2>/dev/null || true
done

divergence=0
for i in $(seq 2 "$NCLIENTS"); do
    if ! diff -q res_t6/doc_1.txt "res_t6/doc_$i.txt" > /dev/null 2>&1; then
        echo "DIVERGENCE : client 1 vs client $i"
        divergence=1
    fi
done

if [ "$divergence" -eq 0 ]; then
    echo "[CONVERGENCE OK] Tous les $NCLIENTS clients ont le même document final."
else
    echo "[DIVERGENCE DÉTECTÉE] Les documents ne sont pas identiques."
fi

echo ""
echo "===================================================="
echo " FIN TEST TÂCHE 6"
echo "===================================================="
