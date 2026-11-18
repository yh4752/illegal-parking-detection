"""Render continuous local preflight evidence. Requires matplotlib 3.11.2."""
import argparse
from collections import defaultdict
import hashlib
import json
import math
from pathlib import Path
import re
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

parser = argparse.ArgumentParser()
parser.add_argument("evidence", type=Path)
args = parser.parse_args()
s = json.loads((args.evidence / "summary.json").read_text())
assert s["timing"]["valid_continuous_timing"] is True
assert s["measured_elapsed_seconds"] >= 1800
raw = args.evidence / "observations.jsonl"
assert hashlib.sha256(raw.read_bytes()).hexdigest() == s["observations_sha256"]
rows = [json.loads(line) for line in raw.read_text().splitlines()]
cgroups = [json.loads(line) for line in (args.evidence / "cgroups.jsonl").read_text().splitlines()]
factors = {"B":1/1048576,"KiB":1/1024,"MiB":1,"GiB":1024,"kB":1000/1048576,"MB":1000000/1048576,"GB":1000000000/1048576}
def mib(text):
    number, unit = re.fullmatch(r"([\d.]+)([A-Za-z]+)",text.strip()).groups()
    return float(number)*factors[unit]
resources = [r for r in rows if r["kind"] == "resources"]
groups = defaultdict(list)
for r in rows:
    if r["kind"] == "request": groups[int(r["elapsed"] // 30)].append(r)
bins = sorted(groups)
times = [(b*30+15)/60 for b in bins]
latencies=[]
for b in bins:
    values=sorted(r["ms"] for r in groups[b] if r["status"]==200)
    latencies.append(values[math.ceil(len(values)*.95)-1] if values else None)
errors=[sum(r["status"]!=200 for r in groups[b]) for b in bins]
plt.rcParams.update({"font.size":11,"axes.spines.top":False,"axes.spines.right":False,"svg.hashsalt":"preflight-evidence"})
fig, axes=plt.subplots(3,1,figsize=(12,8),sharex=True,constrained_layout=True)
fig.suptitle("Continuous local preflight | container memory budgets",fontsize=17,fontweight="bold")
axes[0].set_title("5 authenticated API pollers + virtual device; NOT an actual 4GB / 2-vCPU host",fontsize=10)
axes[0].plot([r["elapsed"]/60 for r in resources],[sum(mib(x["MemUsage"].split("/")[0]) for x in r["stats"])/1024 for r in resources],label="Docker CLI memory",color="#2563eb")
axes[0].plot([r["elapsed"]/60 for r in cgroups],[sum(x["current_bytes"] for x in r["containers"])/1073741824 for r in cgroups],label="Cgroup memory incl. cache (sequential samples)",color="#0f766e")
axes[0].set_ylabel("Containers / GiB")
axes[0].set_ylim(bottom=0)
axes[0].legend(fontsize=9,loc="upper left")
axes[1].plot(times,latencies,color="#2563eb",marker=".")
axes[1].set_ylabel("HTTP 200 p95 / ms")
axes[1].set_ylim(bottom=0)
axes[1].set_title("Successful responses only; p95 in each 30-second bucket",fontsize=10)
axes[2].bar(times,errors,width=.43,color="#d76643")
axes[2].set_ylabel("Non-200 / 30 sec")
axes[2].set_xlabel("Minutes after measurement start")
axes[2].set_title("Connection errors, HTTP 502 and re-login responses remain in the failure count",fontsize=10)
a=next(r["elapsed"]/60 for r in rows if r["kind"]=="backup_start")
b=next(r["elapsed"]/60 for r in rows if r["kind"]=="backup_end")
for ax in axes:
    ax.axvspan(a,b,color="#f59e0b",alpha=.2)
    ax.grid(alpha=.15)
    ax.set_xlim(0,30.5)
axes[1].text((a+b)/2,.9,"Backup",transform=axes[1].get_xaxis_transform(),ha="center",fontsize=9,color="#9a5b00")
fig.savefig(args.evidence / "measurements.png",dpi=160)
fig.savefig(args.evidence / "measurements.svg",metadata={"Date":None})
# Keep the generated SVG clean in Git without changing its geometry.
svg_path = args.evidence / "measurements.svg"
svg_path.write_text("\n".join(line.rstrip() for line in svg_path.read_text().splitlines()) + "\n")
