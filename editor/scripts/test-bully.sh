#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR/../src/main/java"

echo "===================================================="
echo " Bully — Élection de leader"
echo " 3 nœuds, tolère la panne de N-1 nœuds"
echo "===================================================="
echo ""
echo "[1] Compilation..."
javac ServerBully.java AutoClient.java
echo "OK"

pkill -f "java ServerBully" 2>/dev/null || true
sleep 2

for id in 1 2 3; do
    cat > "bully_$id.cfg" <<EOF
myid = $id
node = 1 localhost 5000
node = 2 localhost 5001
node = 3 localhost 5002
EOF
done

echo ""
echo "===================================================="
echo " TEST A : Fonctionnement normal (3 nœuds, 3 clients)"
echo "===================================================="
rm -rf res_bullyA bar_bullyA
mkdir -p res_bullyA bar_bullyA

java ServerBully 5000 bully_1.cfg &
PID1=$!
java ServerBully 5001 bully_2.cfg &
PID2=$!
java ServerBully 5002 bully_3.cfg &
PID3=$!

echo "Attente élection initiale (6s)..."
sleep 6

LEADERPORT=5002
echo "[INFO] Leader attendu sur port $LEADERPORT (ID=3, le plus grand)"

java AutoClient localhost "$LEADERPORT" 1 5 res_bullyA/r1.txt 1 bar_bullyA &
sleep 1
java AutoClient localhost "$LEADERPORT" 2 5 res_bullyA/r2.txt 1 bar_bullyA &
sleep 1
java AutoClient localhost "$LEADERPORT" 3 5 res_bullyA/r3.txt 1 bar_bullyA &

echo "Attente fin clients (20s max)..."
for i in $(seq 1 20); do
    cnt=0
    [ -f res_bullyA/r1.txt ] && cnt=$((cnt+1))
    [ -f res_bullyA/r2.txt ] && cnt=$((cnt+1))
    [ -f res_bullyA/r3.txt ] && cnt=$((cnt+1))
    [ "$cnt" -ge 3 ] && break
    sleep 1
done

kill "$PID1" "$PID2" "$PID3" 2>/dev/null || true
sleep 1

echo ""
echo "--- Documents finaux ---"
for i in 1 2 3; do echo "= Client $i ="; cat "res_bullyA/r$i.txt" 2>/dev/null; echo ""; done

okA=1
diff -q res_bullyA/r1.txt res_bullyA/r2.txt > /dev/null 2>&1 || okA=0
diff -q res_bullyA/r1.txt res_bullyA/r3.txt > /dev/null 2>&1 || okA=0
[ "$okA" -eq 1 ] && echo "[PASS] Test A : convergence OK" || echo "[FAIL] Test A : divergence"

echo ""
echo "===================================================="
echo " TEST B : Panne du leader après 5s"
echo "   Leader initial : nœud 3 (ID=3, port=5002)"
echo "   Nouveau leader attendu : nœud 2 (ID=2, port=5001)"
echo "===================================================="
rm -rf res_bullyB bar_bullyB
mkdir -p res_bullyB bar_bullyB

java ServerBully 5000 bully_1.cfg &
PID1=$!
java ServerBully 5001 bully_2.cfg &
PID2=$!
java ServerBully 5002 bully_3.cfg &
PID3=$!

echo "Attente élection initiale (6s)..."
sleep 6

echo "Connexion de 2 clients au leader (port 5002)..."
java AutoClient localhost 5002 1 10 res_bullyB/r1.txt 1 bar_bullyB &
CPID1=$!
java AutoClient localhost 5001 2 10 res_bullyB/r2.txt 1 bar_bullyB &
CPID2=$!

echo "Pause 5s puis kill du leader (nœud 3)..."
sleep 5
kill "$PID3" 2>/dev/null
echo "[INFO] Nœud 3 (leader) tué. Élection en cours..."
echo "       Dans 3s max, le nœud 2 (ID=2) doit devenir nouveau leader."

echo "Attente convergence (30s max)..."
for i in $(seq 1 30); do
    cnt=0
    [ -f res_bullyB/r1.txt ] && cnt=$((cnt+1))
    [ -f res_bullyB/r2.txt ] && cnt=$((cnt+1))
    [ "$cnt" -ge 2 ] && break
    sleep 1
done

kill "$PID1" "$PID2" "$CPID1" "$CPID2" 2>/dev/null || true
sleep 1

echo ""
echo "--- Documents finaux ---"
for i in 1 2; do echo "= Client $i ="; cat "res_bullyB/r$i.txt" 2>/dev/null; echo ""; done

if [ -f res_bullyB/r1.txt ] && [ -f res_bullyB/r2.txt ]; then
    if diff -q res_bullyB/r1.txt res_bullyB/r2.txt > /dev/null 2>&1; then
        echo "[PASS] Test B : cluster a survécu à la panne du leader, documents identiques"
    else
        echo "[FAIL] Test B : divergence après panne"
    fi
else
    echo "[FAIL] Test B : au moins un client n'a pas terminé"
fi

echo ""
echo "===================================================="
echo " FIN TÂCHE 7"
echo "===================================================="
