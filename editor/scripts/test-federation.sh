#!/bin/bash
# test-federation.sh — Tâche 4 : fédération simple (2 et 3 serveurs)
# Test A : 2 serveurs (B → A), Test B : 3 serveurs en étoile (B → A ← C)
cd "$(dirname "$0")/../src/main/java"

HOST=localhost
PORT_A=5000
PORT_B=5001
PORT_C=5002
OPS=6

# Envoie la commande CONNECT host port à un ServerFederated via TCP
connect_peer() {
    python3 - "$1" "$2" "$3" "$4" <<'PY'
import socket, time, sys
s = socket.socket()
s.connect((sys.argv[1], int(sys.argv[2])))
s.send(("CONNECT " + sys.argv[3] + " " + sys.argv[4] + "\n").encode())
time.sleep(2)
s.close()
PY
}

echo "===================================================="
echo " TÂCHE 4 : Fédération simple"
echo "===================================================="
echo ""
echo "Compilation..."
javac ServerFederated.java AutoClient.java

pkill -f "java ServerFederated" 2>/dev/null || true
sleep 2

# ── TEST A : 2 serveurs ──────────────────────────────────────────────────────
echo ""
echo "Test A: 2-server federation (B → A)"
rm -rf res_2srv barrier2
mkdir -p res_2srv barrier2

java ServerFederated "$PORT_A" &
PID_A=$!
java ServerFederated "$PORT_B" &
PID_B=$!
sleep 2

echo "Connexion fédération : B → A"
connect_peer "$HOST" "$PORT_B" "$HOST" "$PORT_A"
sleep 1
echo "Fédération établie."

echo "Lancement clients (barrière=2)..."
java AutoClient "$HOST" "$PORT_A" 1 "$OPS" res_2srv/result_1.txt 2 barrier2 &
java AutoClient "$HOST" "$PORT_B" 2 "$OPS" res_2srv/result_2.txt 2 barrier2 &

while true; do
    cnt=0
    [ -f res_2srv/result_1.txt ] && cnt=$((cnt+1))
    [ -f res_2srv/result_2.txt ] && cnt=$((cnt+1))
    [ "$cnt" -ge 2 ] && break
    sleep 2
done
echo "Tous les clients ont terminé."

kill "$PID_A" "$PID_B" 2>/dev/null || true
sleep 1

echo ""; echo "--- result_1.txt (Server A) ---"; cat res_2srv/result_1.txt
echo ""; echo "--- result_2.txt (Server B) ---"; cat res_2srv/result_2.txt
echo ""
if diff -q res_2srv/result_1.txt res_2srv/result_2.txt > /dev/null 2>&1; then
    echo "[PASS] Test A: documents converged"
else
    echo "[FAIL] Test A: document divergence"
fi

# ── TEST B : 3 serveurs en étoile ────────────────────────────────────────────
echo ""
echo "===================================================="
echo " TEST B : 3 serveurs fédérés (topologie étoile)"
echo "   A (port $PORT_A) ← B (port $PORT_B)"
echo "   A (port $PORT_A) ← C (port $PORT_C)"
echo "===================================================="
rm -rf res_3srv barrier3
mkdir -p res_3srv barrier3

java ServerFederated "$PORT_A" &
PID_A=$!
java ServerFederated "$PORT_B" &
PID_B=$!
java ServerFederated "$PORT_C" &
PID_C=$!
sleep 2

echo "Connexion fédération : B → A et C → A"
connect_peer "$HOST" "$PORT_B" "$HOST" "$PORT_A"
connect_peer "$HOST" "$PORT_C" "$HOST" "$PORT_A"
sleep 1
echo "Fédération établie (étoile centrée sur A)."

echo "Lancement clients (barrière=3)..."
java AutoClient "$HOST" "$PORT_A" 1 "$OPS" res_3srv/result_1.txt 3 barrier3 &
java AutoClient "$HOST" "$PORT_B" 2 "$OPS" res_3srv/result_2.txt 3 barrier3 &
java AutoClient "$HOST" "$PORT_C" 3 "$OPS" res_3srv/result_3.txt 3 barrier3 &

while true; do
    cnt=0
    for i in 1 2 3; do [ -f "res_3srv/result_$i.txt" ] && cnt=$((cnt+1)); done
    [ "$cnt" -ge 3 ] && break
    sleep 2
done
echo "Tous les clients ont terminé."

kill "$PID_A" "$PID_B" "$PID_C" 2>/dev/null || true
sleep 1

for i in 1 2 3; do
    echo ""; echo "--- result_$i.txt ---"; cat "res_3srv/result_$i.txt"
done
echo ""

conv3=1
for pair in "1 2" "1 3" "2 3"; do
    a=$(echo "$pair" | awk '{print $1}')
    b=$(echo "$pair" | awk '{print $2}')
    if diff -q "res_3srv/result_$a.txt" "res_3srv/result_$b.txt" > /dev/null 2>&1; then
        echo "[A$a vs A$b] OK"
    else
        echo "[A$a vs A$b] DIVERGENCE"
        conv3=0
    fi
done

echo ""
if [ "$conv3" -eq 1 ]; then
    echo "[RÉSULTAT TEST B] CONVERGENCE OK — tous les documents sont identiques"
else
    echo "[RÉSULTAT TEST B] DIVERGENCE détectée — les documents diffèrent"
fi

echo ""
echo "===================================================="
echo " FIN DES TESTS TÂCHE 4"
echo "===================================================="
