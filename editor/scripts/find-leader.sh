#!/bin/bash
# find-leader.sh — Détection du leader Raft parmi les ports 5000-5002
# Usage  : ./find-leader.sh [output_file]
# Sortie : écrit le port du leader dans output_file (défaut : leader_port.tmp)
#          exit 0 si leader trouvé, exit 1 + port=5000 en fallback sinon
OUT="${1:-leader_port.tmp}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

python3 - "$OUT" <<'PY'
import sys, socket, time

out = sys.argv[1]
ports = [5000, 5001, 5002]

for _ in range(15):
    for p in ports:
        try:
            s = socket.socket()
            s.settimeout(4)
            s.connect(('localhost', p))
            f = s.makefile('rwb', buffering=0)
            # Vider GETD
            f.write(b'GETD\n'); f.flush()
            while True:
                l = f.readline().decode().strip()
                if not l or l == 'DONE':
                    break
            # Sonder : DONE = leader, ERRL = follower
            f.write(b'ADDL 1 1 probe\n'); f.flush()
            found = False
            while True:
                r = f.readline().decode().strip()
                if not r:
                    break
                if r == 'DONE':
                    found = True
                    break
                if r.startswith('ERRL'):
                    break
            s.close()
            if found:
                with open(out, 'w') as fout:
                    fout.write(str(p))
                sys.exit(0)
        except Exception:
            pass
    time.sleep(0.6)

# Fallback
with open(out, 'w') as fout:
    fout.write('5000')
sys.exit(1)
PY
