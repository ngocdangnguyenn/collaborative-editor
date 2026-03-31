#!/bin/bash
# test-spof.sh — Tâche 7A : démonstration du SPOF (ServerMasterFaulty)
# Le maître se gèle toutes les 15s pendant 5s, puis crash après 3 minutes.
# Les clients se bloquent pendant les gels, puis définitivement après le crash.
cd "$(dirname "$0")/../src/main/java"

echo "===================================================="
echo " TÂCHE 7A : Démonstration du point de défaillance unique"
echo " ServerMasterFaulty — maître se gèle puis s'arrête"
echo "===================================================="
echo ""
echo "[1] Compilation..."
javac ServerMasterFaulty.java AutoClient.java
echo "OK"

pkill -f "java ServerMasterFaulty" 2>/dev/null || true
sleep 2

cat > peers_faulty.cfg <<EOF
master = localhost 5000
peer   = localhost 5001
peer   = localhost 5002
EOF

rm -rf res_faulty bar_faulty
mkdir -p res_faulty bar_faulty

echo ""
echo "[2] Démarrage : 1 maître défaillant + 2 esclaves..."
java ServerMasterFaulty 5000 peers_faulty.cfg &
sleep 2
java ServerMasterFaulty 5001 peers_faulty.cfg &
java ServerMasterFaulty 5002 peers_faulty.cfg &
sleep 4

echo ""
echo "[3] Lancement de 3 clients (30 opérations chacun)..."
echo "    Les clients se bloquent lors des gels du maître."
echo "    Après 3 minutes, le maître crashe — le cluster devient inopérant."
java AutoClient localhost 5000 1 30 res_faulty/result_1.txt 3 bar_faulty &
java AutoClient localhost 5001 2 30 res_faulty/result_2.txt 3 bar_faulty &
java AutoClient localhost 5002 3 30 res_faulty/result_3.txt 3 bar_faulty &

echo ""
echo "== OBSERVATION EN COURS =="
echo "Attente des résultats (max 3m30s)..."
waited=0
while true; do
    cnt=0
    [ -f res_faulty/result_1.txt ] && cnt=$((cnt+1))
    [ -f res_faulty/result_2.txt ] && cnt=$((cnt+1))
    [ -f res_faulty/result_3.txt ] && cnt=$((cnt+1))
    [ "$cnt" -ge 3 ] && break
    waited=$((waited+3))
    if [ "$waited" -ge 210 ]; then
        echo "[TIMEOUT] Certains clients ne terminent pas — crash du maître confirmé."
        echo "[COMPORTEMENT ATTENDU : point de défaillance unique démontré]"
        break
    fi
    sleep 3
done

pkill -f "java ServerMasterFaulty" 2>/dev/null || true

echo ""
echo "--- Documents obtenus ---"
for i in 1 2 3; do
    echo ""
    echo "= Client $i ="
    if [ -f "res_faulty/result_$i.txt" ]; then
        cat "res_faulty/result_$i.txt"
    else
        echo "[PAS DE RÉSULTAT — client bloqué par la panne]"
    fi
done

echo ""
echo "===================================================="
echo " FIN TÂCHE 7A"
echo "===================================================="
