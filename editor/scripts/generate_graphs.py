#!/usr/bin/env python3
"""Generate benchmark charts for RAPPORT."""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.ticker as ticker
import os

OUT = os.path.dirname(os.path.abspath(__file__))

# ── Scenario A: variable clients, 3 fixed servers ──────────────────────────
clients      = [1, 2, 4, 8]
latency_A    = [3.4, 4.0, 3.7, 4.0]
throughput_A = [167, 196, 686, 1025]

fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(10, 4))
fig.suptitle("Scénario A — 3 serveurs fixes, clients variables", fontsize=13)

ax1.plot(clients, latency_A, marker="o", color="#2176AE", linewidth=2)
ax1.fill_between(clients, latency_A, alpha=0.12, color="#2176AE")
ax1.set_xlabel("Nombre de clients")
ax1.set_ylabel("Latence moyenne (ms)")
ax1.set_xticks(clients)
ax1.yaxis.set_minor_locator(ticker.AutoMinorLocator())
ax1.grid(True, which="both", linestyle="--", alpha=0.4)
ax1.set_title("Latence")

ax2.bar(clients, throughput_A, color="#57CC99", width=0.6, edgecolor="white")
ax2.set_xlabel("Nombre de clients")
ax2.set_ylabel("Débit total (ops/s)")
ax2.set_xticks(clients)
ax2.grid(True, axis="y", linestyle="--", alpha=0.4)
ax2.set_title("Débit")

plt.tight_layout()
path_A = os.path.join(OUT, "bench_scenario_A.png")
fig.savefig(path_A, dpi=150)
plt.close(fig)
print(f"Saved {path_A}")

# ── Scenario B: variable servers, 4 fixed clients ──────────────────────────
servers      = [2, 3, 5, 10]
latency_B    = [3.5, 3.3, 4.3, 7.2]
throughput_B = [442, 686, 633, 408]

fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(10, 4))
fig.suptitle("Scénario B — 4 clients fixes, serveurs variables", fontsize=13)

ax1.plot(servers, latency_B, marker="s", color="#E63946", linewidth=2)
ax1.fill_between(servers, latency_B, alpha=0.12, color="#E63946")
ax1.set_xlabel("Nombre de serveurs")
ax1.set_ylabel("Latence moyenne (ms)")
ax1.set_xticks(servers)
ax1.yaxis.set_minor_locator(ticker.AutoMinorLocator())
ax1.grid(True, which="both", linestyle="--", alpha=0.4)
ax1.set_title("Latence")

ax2.bar(servers, throughput_B, color="#F4A261", width=0.6, edgecolor="white")
ax2.set_xlabel("Nombre de serveurs")
ax2.set_ylabel("Débit total (ops/s)")
ax2.set_xticks(servers)
ax2.grid(True, axis="y", linestyle="--", alpha=0.4)
ax2.set_title("Débit")

plt.tight_layout()
path_B = os.path.join(OUT, "bench_scenario_B.png")
fig.savefig(path_B, dpi=150)
plt.close(fig)
print(f"Saved {path_B}")
