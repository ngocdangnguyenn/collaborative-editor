#!/bin/bash
cd "$(dirname "$0")/../src/main/java"

HOST=localhost
PORT_MASTER=5000
PORT_S1=5001
PORT_S2=5002
OPS=6

echo "===================================================="
echo " TÂCHE 5 : Fédération avec serveur maître"
echo "===================================================="
echo ""
echo "Compilation..."
javac ServerMaster.java AutoClient.java

pkill -f "java ServerMaster" 2>/dev/null || true
sleep 2

echo ""
echo "===================================================="
echo " TEST A : 1 maître + 1 esclave"
echo "   Maître  : port $PORT_MASTER"
echo "   Esclave : port $PORT_S1"
echo "===================================================="
rm -rf res_t5_2srv barrier_t5_2
mkdir -p res_t5_2srv barrier_t5_2

cat > peers_2.cfg <<EOF
master = localhost $PORT_MASTER
peer   = localhost $PORT_S1
EOF

java ServerMaster "$PORT_MASTER" peers_2.cfg &
sleep 2
java ServerMaster "$PORT_S1" peers_2.cfg &
sleep 3

echo "Lancement clients (barrière=2)..."
java AutoClient "$HOST" "$PORT_MASTER" 1 "$OPS" res_t5_2srv/result_1.txt 2 barrier_t5_2 &
java AutoClient "$HOST" "$PORT_S1"     2 "$OPS" res_t5_2srv/result_2.txt 2 barrier_t5_2 &

while true; do
    cnt=0
    [ -f res_t5_2srv/result_1.txt ] && cnt=$((cnt+1))
    [ -f res_t5_2srv/result_2.txt ] && cnt=$((cnt+1))
    [ "$cnt" -ge 2 ] && break
    sleep 2
done
echo "Clients terminés."

pkill -f "java ServerMaster" 2>/dev/null || true
sleep 2

echo ""; echo "--- result_1.txt (Maître) ---";  cat res_t5_2srv/result_1.txt
echo ""; echo "--- result_2.txt (Esclave) ---"; cat res_t5_2srv/result_2.txt
echo ""
if diff -q res_t5_2srv/result_1.txt res_t5_2srv/result_2.txt > /dev/null 2>&1; then
    echo "[PASS] Test A: master-slave convergence"
else
    echo "[FAIL] Test A: documents diverged"
fi

echo ""
echo "===================================================="
echo " TEST B : 1 maître + 2 esclaves"
echo "   Maître   : port $PORT_MASTER"
echo "   Esclave1 : port $PORT_S1"
echo "   Esclave2 : port $PORT_S2"
echo "===================================================="
rm -rf res_t5_3srv barrier_t5_3
mkdir -p res_t5_3srv barrier_t5_3

cat > peers_3.cfg <<EOF
master = localhost $PORT_MASTER
peer   = localhost $PORT_S1
peer   = localhost $PORT_S2
EOF

java ServerMaster "$PORT_MASTER" peers_3.cfg &
sleep 2
java ServerMaster "$PORT_S1" peers_3.cfg &
java ServerMaster "$PORT_S2" peers_3.cfg &
sleep 4

echo "Lancement clients (barrière=3)..."
java AutoClient "$HOST" "$PORT_MASTER" 1 "$OPS" res_t5_3srv/result_1.txt 3 barrier_t5_3 &
java AutoClient "$HOST" "$PORT_S1"     2 "$OPS" res_t5_3srv/result_2.txt 3 barrier_t5_3 &
java AutoClient "$HOST" "$PORT_S2"     3 "$OPS" res_t5_3srv/result_3.txt 3 barrier_t5_3 &

while true; do
    cnt=0
    for i in 1 2 3; do [ -f "res_t5_3srv/result_$i.txt" ] && cnt=$((cnt+1)); done
    [ "$cnt" -ge 3 ] && break
    sleep 2
done
echo "Clients terminés."

pkill -f "java ServerMaster" 2>/dev/null || true
sleep 2

for i in 1 2 3; do
    echo ""; echo "--- result_$i.txt ---"; cat "res_t5_3srv/result_$i.txt"
done
echo ""

ok=1
diff -q res_t5_3srv/result_1.txt res_t5_3srv/result_2.txt > /dev/null 2>&1 || ok=0
diff -q res_t5_3srv/result_1.txt res_t5_3srv/result_3.txt > /dev/null 2>&1 || ok=0

if [ "$ok" -eq 1 ]; then
    echo "[RÉSULTAT TEST B] CONVERGENCE OK"
else
    echo "[RÉSULTAT TEST B] DIVERGENCE détectée"
fi

echo ""
echo "===================================================="
echo " FIN TÂCHE 5"
echo "===================================================="
