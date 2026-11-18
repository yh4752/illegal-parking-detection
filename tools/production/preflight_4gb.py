"""Isolated memory-budget experiment. Never changes Docker VM settings or existing volumes."""

import json, os, threading, time, uuid, subprocess, argparse, traceback, ipaddress
from pathlib import Path
from local_backup import LocalBackup, ROOT
from verify_o2 import Client, free_port


class BudgetStack(LocalBackup):
    compose_files = (*LocalBackup.compose_files, "infra/preflight/compose.yaml")


def parse_stats_output(result):
    """Keep valid rows if Docker's stream ends while a short-lived helper exits."""
    rows = []
    warnings = []
    if result.returncode:
        warnings.append("docker_stats_nonzero:" + result.stderr.strip()[:200])
    for line in result.stdout.splitlines():
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError:
            warnings.append("incomplete_stats_row")
    if not rows:
        raise RuntimeError("no_resource_rows:" + ";".join(warnings))
    return rows, warnings


def verify_clock_progress(monotonic_elapsed, wall_elapsed):
    if abs(wall_elapsed - monotonic_elapsed) > 2:
        raise RuntimeError(
            "host_sleep_or_clock_discontinuity_invalidates_continuous_run"
        )


def safe_health(stack):
    return stack.grafana("/api/health").get("database")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--minutes", type=int, default=30)
    parser.add_argument(
        "--restore-containers-list",
        type=Path,
        help="Explicit private JSON list of previously running containers to restart at exit",
    )
    args = parser.parse_args()
    if args.minutes < 3:
        raise ValueError("at_least_3_minutes_required")
    run_id = uuid.uuid4().hex[:8]
    output = ROOT / ".local-state" / ("preflight-" + run_id)
    output.mkdir(mode=0o700)
    networks = json.loads(
        subprocess.check_output(
            [
                "docker",
                "network",
                "inspect",
                *subprocess.check_output(
                    ["docker", "network", "ls", "-q"], text=True
                ).split(),
            ],
            text=True,
        )
    )
    existing = [
        ipaddress.ip_network(c["Subnet"])
        for n in networks
        for c in (n["IPAM"]["Config"] or [])
        if c.get("Subnet")
    ]
    pool = next(
        ipaddress.ip_network(f"10.238.{n}.0/24")
        for n in range(180, 250)
        if not any(
            ipaddress.ip_network(f"10.238.{n}.0/24").overlaps(x)
            for x in existing
            if x.version == 4
        )
    )
    ipam = output / "networks.json"
    ipam.write_text(
        json.dumps(
            {
                "networks": {
                    name: {"ipam": {"config": [{"subnet": str(net)}]}}
                    for name, net in zip(
                        (
                            "web",
                            "app",
                            "data",
                            "mqtt",
                            "monitoring",
                            "monitoring-ui",
                            "ledger",
                        ),
                        pool.subnets(new_prefix=28),
                    )
                }
            }
        )
    )
    stack = BudgetStack(output / "stack")
    stack.compose_files = (*stack.compose_files, str(ipam))
    (ROOT / ".local-state/preflight-current.txt").write_text(str(output))
    lock = threading.Lock()
    stop = threading.Event()
    sim_lock = threading.Lock()
    started = 0
    wall_started = 0
    phase = "prepare"

    def check_clock():
        verify_clock_progress(time.monotonic() - started, time.time() - wall_started)

    def log(kind, **data):
        row = {
            "timestamp": time.time(),
            "elapsed": time.monotonic() - started if started else None,
            "wall_elapsed": time.time() - wall_started if wall_started else None,
            "phase": phase,
            "kind": kind,
            **data,
        }
        with lock:
            with (output / "measurements.jsonl").open("a") as f:
                f.write(json.dumps(row) + "\n")

    def client():
        c = Client(stack)
        c.login(
            stack.release["username"], Path(stack.release["password_file"]).read_text()
        )
        return c

    def viewer(index):
        c = None
        while not stop.is_set():
            t = time.monotonic()
            try:
                if c is None:
                    c = client()
                status, _, body = c.request("/api/zones/zone-01/status")
                log(
                    "request",
                    viewer=index,
                    status=status,
                    ms=(time.monotonic() - t) * 1000,
                    revision=(
                        body.get("view_revision") if isinstance(body, dict) else None
                    ),
                )
                if status == 401:
                    c = None
            except Exception as e:
                log(
                    "request",
                    viewer=index,
                    status=0,
                    ms=(time.monotonic() - t) * 1000,
                    error=type(e).__name__,
                )
            stop.wait(max(0, 1 - (time.monotonic() - t)))

    def sampler():
        while not stop.is_set():
            try:
                ids = stack.run(
                    "docker",
                    "ps",
                    "-q",
                    "--filter",
                    "label=com.docker.compose.project=" + stack.release["project"],
                ).split()
                if ids:
                    stats, warnings = parse_stats_output(
                        subprocess.run(
                            [
                                "docker",
                                "stats",
                                "--no-stream",
                                "--format",
                                "{{json .}}",
                                *ids,
                            ],
                            capture_output=True,
                            text=True,
                            timeout=20,
                            cwd=ROOT,
                        )
                    )
                    inspect = []
                    for identifier in ids:
                        try:
                            inspect.extend(
                                json.loads(
                                    stack.run(
                                        "docker", "inspect", identifier, timeout=20
                                    )
                                )
                            )
                        except RuntimeError:
                            warnings.append("container_disappeared_or_inspect_failed")
                    log(
                        "resources",
                        stats=stats,
                        sampling_warnings=warnings,
                        containers=[
                            {
                                "name": x["Name"],
                                "service": x["Config"]["Labels"].get(
                                    "com.docker.compose.service"
                                ),
                                "memory_limit": x["HostConfig"]["Memory"],
                                "swap_limit": x["HostConfig"]["MemorySwap"],
                                "restarts": x["RestartCount"],
                                "oom": x["State"]["OOMKilled"],
                            }
                            for x in inspect
                        ],
                        runner_rss_kib=int(
                            subprocess.check_output(
                                ["ps", "-o", "rss=", "-p", str(os.getpid())], text=True
                            ).strip()
                        ),
                    )
            except Exception as e:
                log("sample_error", error=type(e).__name__)
            stop.wait(5)

    def device():
        while not stop.is_set():
            with sim_lock:
                if stop.is_set():
                    break
                t = time.monotonic()
                try:
                    out = stack.simulate(5)
                    rows = []
                    for line in out.splitlines():
                        if line.startswith("{"):
                            rows.append(json.loads(line))
                    log("device", seconds=time.monotonic() - t, records=rows)
                except Exception as e:
                    log(
                        "device_error",
                        error=type(e).__name__,
                        detail=stack.redact(str(e))[-1000:],
                    )
            stop.wait(5)

    threads = []
    try:
        print("PREFLIGHT_PREPARING", output.name, flush=True)
        stack.prepare(
            free_port(),
            free_port(),
            "parking-o4-" + run_id,
            "acceptance-o4",
            grafana_port=free_port(),
        )
        log(
            "environment",
            docker=json.loads(stack.run("docker", "info", "--format", "{{json .}}"))[
                "MemTotal"
            ],
            images=stack.release["images"],
            project=stack.release["project"],
            duration_minutes=args.minutes,
            users=5,
            scope="PER_CONTAINER_MEMORY_BUDGET_ONLY",
            cpu_limit=False,
        )
        started = time.monotonic()
        wall_started = time.time()
        phase = "normal"
        log(
            "initial_database",
            rows=int(stack.sql("SELECT count(*) FROM event_message")),
        )
        for fn in [sampler, device, *[lambda i=i: viewer(i) for i in range(5)]]:
            t = threading.Thread(target=fn)
            t.start()
            threads.append(t)
        print("PREFLIGHT_MEASURING", args.minutes, "minutes", flush=True)
        boundary = args.minutes * 60 / 3
        while time.monotonic() - started < boundary:
            check_clock()
            time.sleep(1)
        phase = "backup_window"
        # Current O4 backup deliberately stops app/web and snapshots the simulator.
        with sim_lock:
            t = time.monotonic()
            log("backup_start")
            try:
                result = stack.backup()
                log("backup_end", seconds=time.monotonic() - t, result=result)
            except Exception as e:
                log(
                    "backup_error",
                    error=type(e).__name__,
                    detail=stack.redact(str(e))[-2000:],
                )
                stack.up()
        while time.monotonic() - started < boundary * 2:
            check_clock()
            time.sleep(1)
        phase = "recovery"
        while time.monotonic() - started < args.minutes * 60:
            check_clock()
            time.sleep(1)
        check_clock()
        stop.set()
        for t in threads:
            t.join(timeout=90)
        print("PREFLIGHT_FINAL_CHECK", flush=True)
        out = stack.simulate(6)
        from verify_o4 import pending

        c = client()
        status, _, body = c.request("/api/zones/zone-01/status")
        log(
            "final",
            database_rows=int(stack.sql("SELECT count(*) FROM event_message")),
            pending_count=len(pending(stack)),
            http_status=status,
            final_simulation=out,
            revision=body.get("view_revision"),
        )
        for cid in stack.run(
            "docker",
            "ps",
            "-q",
            "--filter",
            "label=com.docker.compose.project=" + stack.release["project"],
        ).split():
            try:
                usage = stack.run(
                    "docker",
                    "exec",
                    cid,
                    "sh",
                    "-c",
                    "cat /sys/fs/cgroup/memory.current /sys/fs/cgroup/memory.peak /sys/fs/cgroup/memory.events",
                )
                log("cgroup_final", container=cid, usage=usage)
            except Exception:
                pass
        log(
            "monitoring_final",
            grafana=safe_health(stack),
            backend_scrape=stack.prom('up{job="parking"}'),
        )
        print("PREFLIGHT_DONE", output.name, flush=True)
    except Exception as e:
        log("fatal", error=type(e).__name__, detail=stack.redact(str(e))[-2000:])
        traceback.print_exc()
        raise
    finally:
        stop.set()
        for t in threads:
            t.join(timeout=90)
        if stack.release:
            try:
                stack.dc("--profile", "*", "stop", timeout=120)
            except Exception as e:
                log("cleanup_error", error=type(e).__name__)
        # Keep this run's new volumes and private artifacts for investigation.
        restart = args.restore_containers_list
        if restart is not None:
            ids = json.loads(restart.read_text())
            if ids:
                subprocess.run(
                    ["docker", "start", *ids], check=True, stdout=subprocess.DEVNULL
                )
            print("EXPLICIT_CONTAINER_RESTORE_COMPLETED", flush=True)


if __name__ == "__main__":
    main()
