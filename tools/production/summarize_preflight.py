"""Publish allowlisted local preflight observations; never publish a release or secrets."""

import argparse
from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path
import re


def mib(value):
    match = re.fullmatch(r"([\d.]+)([A-Za-z]+)", value.strip())
    factors = {
        "B": 1 / 1048576,
        "KiB": 1 / 1024,
        "MiB": 1,
        "GiB": 1024,
        "kB": 1000 / 1048576,
        "MB": 1000000 / 1048576,
        "GB": 1000000000 / 1048576,
    }
    return float(match[1]) * factors[match[2]]


def percentile(values, fraction):
    return (
        round(sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)], 3)
        if values
        else None
    )


def clock_integrity(rows):
    timed = [r for r in rows if r.get("elapsed") is not None and "timestamp" in r]
    if not timed:
        return {"valid_continuous_timing": None}
    timed.sort(key=lambda r: r["elapsed"])
    first_offset = timed[0]["timestamp"] - timed[0]["elapsed"]
    previous = first_offset
    jumps = []
    for row in timed[1:]:
        offset = row["timestamp"] - row["elapsed"]
        if abs(offset - previous) > 2:
            jumps.append(
                {
                    "elapsed": row["elapsed"],
                    "timestamp": row["timestamp"],
                    "offset_change_seconds": offset - previous,
                }
            )
        previous = offset
    drift = max(abs(r["timestamp"] - r["elapsed"] - first_offset) for r in timed)
    return {
        "valid_continuous_timing": drift <= 2,
        "maximum_clock_offset_drift_seconds": drift,
        "wall_clock_span_seconds": timed[-1]["timestamp"] - timed[0]["timestamp"],
        "clock_discontinuities": jumps,
    }


def summarize(rows):
    requests = [r for r in rows if r["kind"] == "request"]
    phases = {}
    for phase in ("normal", "backup_window", "recovery"):
        selected = [r for r in requests if r["phase"] == phase]
        successes = [r["ms"] for r in selected if r["status"] == 200]
        phases[phase] = {
            "requests": len(selected),
            "statuses": dict(Counter(str(r["status"]) for r in selected)),
            "successful_response_p50_ms": percentile(successes, 0.5),
            "successful_response_p95_ms": percentile(successes, 0.95),
            "successful_response_max_ms": max(successes, default=None),
            "viewers": sorted(set(r["viewer"] for r in selected)),
        }
    resources = [r for r in rows if r["kind"] == "resources"]
    peaks = defaultdict(float)
    totals = []
    cpu_totals = []
    oom = set()
    restart_max = 0
    for row in resources:
        services = {c["name"].lstrip("/"): c["service"] for c in row["containers"]}
        total = 0
        for stat in row["stats"]:
            usage = mib(stat["MemUsage"].split("/")[0])
            service = services.get(stat["Name"], "unknown")
            peaks[service] = max(peaks[service], usage)
            total += usage
        totals.append(total)
        cpu_totals.append(
            sum(float(stat.get("CPUPerc", "0%").rstrip("%")) for stat in row["stats"])
        )
        for container in row["containers"]:
            if container["oom"]:
                oom.add(container["service"])
            restart_max = max(restart_max, container["restarts"])
    failures = [r for r in requests if r["status"] != 200]
    first_failure = min((r["elapsed"] - r["ms"] / 1000 for r in failures), default=None)
    last_failure = max((r["elapsed"] for r in failures), default=None)
    recovered = next(
        (
            r["elapsed"]
            for r in requests
            if last_failure is not None
            and r["elapsed"] > last_failure
            and r["status"] == 200
        ),
        None,
    )
    device_records = [
        record for row in rows if row["kind"] == "device" for record in row["records"]
    ]
    final = next((r for r in rows if r["kind"] == "final"), None)
    if final:
        for line in final["final_simulation"].splitlines():
            if line.startswith("{"):
                device_records.append(json.loads(line))
    events = {r["event_id"] for r in device_records if r["kind"] == "VIRTUAL_REPLAY"}
    acknowledgements = {
        r["event_id"]
        for r in device_records
        if r["kind"] == "APPLICATION_ACK" and r["status"] in ("STORED", "DUPLICATE")
    }
    backup = next((r for r in rows if r["kind"] == "backup_end"), None)
    initial = next((r for r in rows if r["kind"] == "initial_database"), None)
    previous_revision = {}
    revision_regressions = 0
    for row in requests:
        revision = row.get("revision")
        if row["status"] == 200 and revision is not None:
            if (
                row["viewer"] in previous_revision
                and revision < previous_revision[row["viewer"]]
            ):
                revision_regressions += 1
            previous_revision[row["viewer"]] = revision
    return {
        "scope": "LOCAL_PER_CONTAINER_MEMORY_BUDGET_NOT_A_4GB_VM",
        "timing": clock_integrity(rows),
        "phases": phases,
        "resources": {
            "samples": len(resources),
            "working_set_peak_mib_by_service": dict(sorted(peaks.items())),
            "simultaneous_sampled_working_set_peak_mib": max(totals, default=None),
            "sampled_container_cpu_peak_percent": max(cpu_totals, default=None),
            "longest_sample_gap_seconds": max(
                (
                    b["elapsed"] - a["elapsed"]
                    for a, b in zip(resources, resources[1:])
                    if "elapsed" in a and "elapsed" in b
                ),
                default=None,
            ),
            "runner_rss_peak_mib": max(
                (r["runner_rss_kib"] / 1024 for r in resources), default=None
            ),
            "sampled_oom_services": sorted(oom),
            "max_automatic_restarts": restart_max,
        },
        "backup": {
            "completed": backup is not None,
            "whole_operation_seconds": backup["seconds"] if backup else None,
            "verification": backup["result"]["result"] if backup else None,
            "restore_comparison": (
                backup["result"]["restore_comparison"] if backup else None
            ),
            "failed_requests_including_relogin": len(failures),
            "first_failed_request_start_elapsed": first_failure,
            "last_failed_request_end_elapsed": last_failure,
            "first_success_after_last_failure_elapsed": recovered,
            "observed_failure_to_recovery_seconds": (
                recovered - first_failure if recovered is not None else None
            ),
        },
        "device": {
            "generated_unique_events": len(events),
            "acknowledged_unique_events": len(acknowledgements),
            "generated_events_without_observed_ack": sorted(events - acknowledgements),
            "ack_statuses": dict(
                Counter(
                    r["status"]
                    for r in device_records
                    if r["kind"] == "APPLICATION_ACK"
                )
            ),
            "initial_db_rows": initial["rows"] if initial else None,
            "final_db_rows": final["database_rows"] if final else None,
            "final_pending": final["pending_count"] if final else None,
        },
        "final_http_status": final["http_status"] if final else None,
        "observed_revision_regressions": revision_regressions,
        "measured_elapsed_seconds": final["elapsed"] if final else None,
        "errors": dict(
            Counter(
                r["kind"]
                for r in rows
                if r["kind"].endswith("_error") or r["kind"] == "fatal"
            )
        ),
        "acceptance": "OBSERVATIONS_ONLY_CLOUD_CAPACITY_NOT_ACCEPTED",
    }


def public_row(row):
    kind = row["kind"]
    keys = {
        "request": ("viewer", "status", "ms", "revision", "error"),
        "resources": ("stats", "containers", "runner_rss_kib", "sampling_warnings"),
        "device": ("seconds", "records"),
        "environment": (
            "docker",
            "images",
            "project",
            "duration_minutes",
            "users",
            "scope",
            "cpu_limit",
        ),
        "initial_database": ("rows",),
        "backup_start": (),
        "cgroup_final": ("container", "usage"),
        "monitoring_final": ("grafana", "backend_scrape"),
    }
    base = {k: row[k] for k in ("timestamp", "elapsed", "phase", "kind")}
    if "wall_elapsed" in row:
        base["wall_elapsed"] = row["wall_elapsed"]
    if kind in keys:
        return {**base, **{k: row[k] for k in keys[kind] if k in row}}
    if kind == "backup_end":
        return {
            **base,
            "seconds": row["seconds"],
            "result": {
                k: row["result"][k]
                for k in (
                    "result",
                    "archive",
                    "ciphertext_sha256",
                    "started_at",
                    "verified_at",
                    "restore_comparison",
                )
            },
        }
    if kind == "final":
        records = [
            json.loads(line)
            for line in row["final_simulation"].splitlines()
            if line.startswith("{")
        ]
        return {
            **base,
            **{
                k: row[k]
                for k in ("database_rows", "pending_count", "http_status", "revision")
            },
            "final_simulation": "\n".join(json.dumps(r) for r in records),
        }
    return {**base, "error": row.get("error", "unclassified")}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    rows = [
        public_row(json.loads(line)) for line in args.input.read_text().splitlines()
    ]
    args.output.mkdir(parents=True, exist_ok=False)
    raw = args.output / "observations.jsonl"
    raw.write_text("".join(json.dumps(row) + "\n" for row in rows))
    summary = summarize(rows)
    supplementary = args.input.parent / "cgroups.jsonl"
    if supplementary.exists():
        # This observer writes only numeric cgroup counters and synthetic container names.
        (args.output / supplementary.name).write_bytes(supplementary.read_bytes())
        samples = [json.loads(line) for line in supplementary.read_text().splitlines()]
        summary["cgroups"] = {
            "samples": len(samples),
            "first_elapsed_seconds": samples[0]["elapsed"] if samples else None,
            "sequential_sample_sum_peak_mib": max(
                (
                    sum(c["current_bytes"] for c in r["containers"]) / 1048576
                    for r in samples
                ),
                default=None,
            ),
            "container_peak_mib_by_service": {
                service: max(
                    c["peak_bytes"]
                    for r in samples
                    for c in r["containers"]
                    if c["service"] == service
                )
                / 1048576
                for service in sorted(
                    {c["service"] for r in samples for c in r["containers"]}
                )
            },
            "observed_oom_kills": max(
                (
                    c["events"].get("oom_kill", 0)
                    for r in samples
                    for c in r["containers"]
                ),
                default=None,
            ),
            "missed_container_reads": sum(len(r["missed"]) for r in samples),
        }
    supplementary = args.input.parent / "dashboard-queries.jsonl"
    if supplementary.exists():
        (args.output / supplementary.name).write_bytes(supplementary.read_bytes())
        samples = [json.loads(line) for line in supplementary.read_text().splitlines()]
        cleanup_rounds = sum(r.get("final", False) for r in samples)
        samples = [r for r in samples if not r.get("final", False)]
        queries = [q for r in samples for q in r["queries"]]
        summary["dashboard"] = {
            "cleanup_rounds_excluded_but_preserved_in_raw": cleanup_rounds,
            "rounds": len(samples),
            "queries": len(queries),
            "query_errors": sum(q.get("status") != "success" for q in queries),
            "successful_query_p95_ms": percentile(
                [q["ms"] for q in queries if q.get("status") == "success"], 0.95
            ),
            "sse_smoke_successes": sum(r["sse"]["snapshot_received"] for r in samples),
            "sse_smoke_failures": sum(
                not r["sse"]["snapshot_received"] for r in samples
            ),
        }
    summary["observations_sha256"] = hashlib.sha256(raw.read_bytes()).hexdigest()
    (args.output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
