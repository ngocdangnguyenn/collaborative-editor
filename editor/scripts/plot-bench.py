#!/usr/bin/env python3
"""
plot-bench.py — Tâche 6 : tracé des courbes latence/débit
Lit bench_results.csv et produit bench_curves.png (4 sous-graphiques).

Usage : python3 plot-bench.py [bench_results.csv]
"""

import csv
import sys
import os
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

csv_file = sys.argv[1] if len(sys.argv) > 1 else "bench_results.csv"

if not os.path.exists(csv_file):
    print(f"Fichier introuvable : {csv_file}")
    sys.exit(1)

rows_A = []
rows_B = []

with open(csv_file, newline='', encoding='utf-8-sig') as f:
    reader = csv.DictReader(f)
    for row in reader:
        for k in row:
            row[k] = row[k].replace(',', '.')
        if row['Scenario'] == 'A':
            rows_A.append(row)
        elif row['Scenario'] == 'B':
            rows_B.append(row)

def extract(rows, x_key, y_key):
    pts = sorted(
        [(int(r[x_key]), float(r[y_key])) for r in rows],
        key=lambda p: p[0]
    )
    return [p[0] for p in pts], [p[1] for p in pts]

A_clients_lat_x, A_clients_lat_y = extract(rows_A, 'NumClients',  'AvgLatencyMs')
A_clients_thr_x, A_clients_thr_y = extract(rows_A, 'NumClients',  'SysThroughput')
B_servers_lat_x, B_servers_lat_y = extract(rows_B, 'NumServers',  'AvgLatencyMs')
B_servers_thr_x, B_servers_thr_y = extract(rows_B, 'NumServers',  'SysThroughput')

fig, axes = plt.subplots(2, 2, figsize=(12, 8))
fig.suptitle("Tâche 6 – Performance de la fédération avec serveur maître",
             fontsize=13, fontweight='bold')

def plot_curve(ax, xs, ys, xlabel, ylabel, title, color):
    ax.plot(xs, ys, marker='o', color=color, linewidth=2, markersize=7)
    for x, y in zip(xs, ys):
        ax.annotate(f"{y:.1f}", (x, y), textcoords="offset points",
                    xytext=(0, 8), ha='center', fontsize=9)
    ax.set_xlabel(xlabel, fontsize=10)
    ax.set_ylabel(ylabel, fontsize=10)
    ax.set_title(title, fontsize=10)
    ax.set_xticks(xs)
    ax.grid(True, linestyle='--', alpha=0.5)

plot_curve(axes[0][0], A_clients_lat_x, A_clients_lat_y,
           "Nombre de clients", "Latence moyenne (ms)",
           "Latence vs Clients  (3 serveurs, 1M+2S)", 'steelblue')

plot_curve(axes[0][1], A_clients_thr_x, A_clients_thr_y,
           "Nombre de clients", "Débit système (ops/s)",
           "Débit vs Clients  (3 serveurs, 1M+2S)", 'seagreen')

plot_curve(axes[1][0], B_servers_lat_x, B_servers_lat_y,
           "Nombre de serveurs", "Latence moyenne (ms)",
           "Latence vs Serveurs  (4 clients)", 'tomato')

plot_curve(axes[1][1], B_servers_thr_x, B_servers_thr_y,
           "Nombre de serveurs", "Débit système (ops/s)",
           "Débit vs Serveurs  (4 clients)", 'darkorange')

plt.tight_layout(rect=[0, 0, 1, 0.95])
out_file = "bench_curves.png"
plt.savefig(out_file, dpi=150, bbox_inches='tight')
print(f"Courbes sauvegardées → {out_file}")
