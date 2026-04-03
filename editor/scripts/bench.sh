#!/bin/bash
cd "$(dirname "$0")/../src/main/java"

OPS_PER_CLIENT=20
DISPATCH_PORT=4999
MASTER_BASE=5000

while [[ "$#" -gt 0 ]]; do
    case "$1" in
        --ops)            OPS_PER_CLIENT="$2"; shift ;;
        --dispatch-port)  DISPATCH_PORT="$2";  shift ;;
        --master-base)    MASTER_BASE="$2";    shift ;;
    esac
    shift
done

CSV_FILE="bench_results.csv"


wait_port() {
    local port=$1
    local deadline=$((SECONDS + 15))
    while [ $SECONDS -lt $deadline ]; do
        python3 -c "import socket; s=socket.socket(); s.settimeout(0.3); \
r=s.connect_ex(('localhost',$port)); s.close(); exit(0 if r==0 else 1)" 2>/dev/null && return 0
        sleep 0.3
    done
    return 1
}

stop_all_servers() {
    pkill -f "java Server" 2>/dev/null || true
    sleep 1
}

start_federation() {
    local num_slaves=$1 master_port=$2 cfg=$3
    echo "master = localhost $master_port" > "$cfg"
    for i in $(seq 1 "$num_slaves"); do
        echo "peer   = localhost $((master_port + i))" >> "$cfg"
    done
    java ServerMaster "$master_port" "$cfg" &
    sleep 2
    wait_port "$master_port" || { echo "  [WARN] Maître $master_port non disponible"; return 1; }
    for i in $(seq 1 "$num_slaves"); do
        java ServerMaster "$((master_port + i))" "$cfg" &
    done
    local wait_sec=$(( num_slaves > 3 ? num_slaves + 2 : 4 ))
    sleep "$wait_sec"
}

start_dispatch() {
    local dp=$1 cfg=$2 master=$3 num_slaves=$4
    echo "server = localhost $master" > "$cfg"
    for i in $(seq 1 "$num_slaves"); do
        echo "server = localhost $((master + i))" >> "$cfg"
    done
    java ServerDispatch "$dp" "$cfg" &
    wait_port "$dp" || true
    sleep 0.5
}

run_scenario() {
    local name=$1 num_servers=$2 num_clients=$3
    local num_slaves=$((num_servers - 1))

    echo ""
    echo "[$name] numServers=$num_servers  numClients=$num_clients  ops=$OPS_PER_CLIENT"
    stop_all_servers

    local res_dir="br_${name}_${num_servers}s_${num_clients}c"
    local bar_dir="bb_${name}_${num_servers}s_${num_clients}c"
    rm -rf "$res_dir" "$bar_dir"
    mkdir -p "$res_dir" "$bar_dir"

    start_federation "$num_slaves" "$MASTER_BASE" "bp_${name}.cfg"
    start_dispatch "$DISPATCH_PORT" "bd_${name}.cfg" "$MASTER_BASE" "$num_slaves"

    for c in $(seq 1 "$num_clients"); do
        java BenchClient dispatch localhost "$DISPATCH_PORT" "$c" \
            "$OPS_PER_CLIENT" 0 "$res_dir/result_$c.txt" "$num_clients" "$bar_dir" &
    done

    local deadline=$((SECONDS + 120))
    while [ $SECONDS -lt $deadline ]; do
        local found=0
        for c in $(seq 1 "$num_clients"); do
            [ -f "$res_dir/result_$c.txt" ] && found=$((found+1))
        done
        [ "$found" -ge "$num_clients" ] && break
        sleep 2
    done

    local sum_lat=0 sum_thr=0 count=0
    for c in $(seq 1 "$num_clients"); do
        local f="$res_dir/result_$c.txt"
        [ -f "$f" ] || continue
        local stats
        stats=$(head -1 "$f")
        echo "  STATS[$c]: $stats"
        local lat thr
        lat=$(echo "$stats" | awk '{print $5}')
        thr=$(echo "$stats" | awk '{print $8}')
        [ -z "$lat" ] && continue
        sum_lat=$(python3 -c "print($sum_lat + $lat)")
        sum_thr=$(python3 -c "print($sum_thr + $thr)")
        count=$((count + 1))
    done

    if [ "$count" -eq 0 ]; then
        echo "  [WARN] Aucune donnée collectée pour ce scénario."
        stop_all_servers
        return
    fi

    local avg_lat sys_thr
    avg_lat=$(python3 -c "print(round($sum_lat / $count, 2))")
    sys_thr=$(python3  -c "print(round($sum_thr, 2))")

    local converged=true
    for c in $(seq 2 "$num_clients"); do
        if [ -f "$res_dir/result_1.txt" ] && [ -f "$res_dir/result_$c.txt" ]; then
            d1=$(tail -n +2 "$res_dir/result_1.txt")
            dc=$(tail -n +2 "$res_dir/result_$c.txt")
            [ "$d1" = "$dc" ] || converged=false
        fi
    done

    echo "  avgLat=${avg_lat}ms  sysThr=${sys_thr}ops/s  converged=$converged"

    echo "$name,$num_servers,$num_clients,$OPS_PER_CLIENT,$avg_lat,$sys_thr,$converged" >> "$CSV_FILE"

    stop_all_servers
}

echo "=== Compilation ==="
javac ServerMaster.java ServerDispatch.java BenchClient.java
echo "OK"

stop_all_servers

echo "Scenario,NumServers,NumClients,OpsPerClient,AvgLatencyMs,SysThroughput,Converged" > "$CSV_FILE"

echo ""
echo "=== Scénario A : clients variables (3 serveurs, 1 maître + 2 esclaves) ==="
for nc in 1 2 4 8; do
    run_scenario A 3 "$nc"
done

echo ""
echo "=== Scénario B : serveurs variables (4 clients fixes) ==="
for ns in 2 3 5 10; do
    run_scenario B "$ns" 4
done

echo ""
echo "=== Résultats exportés dans $CSV_FILE ==="
cat "$CSV_FILE"
echo ""
echo "Lancez 'python3 plot-bench.py' pour tracer les courbes."
