"""Supplemental read-only sampling; scope is one locally prepared preflight stack."""

import argparse
import json
from pathlib import Path
import subprocess
import time


def command(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.PIPE, timeout=10)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path)
    args = parser.parse_args()
    release = json.loads((args.run / "stack/release.json").read_text())
    project = release["project"]
    if not project.startswith("parking-o4-") or not args.run.name.startswith(
        "preflight-"
    ):
        raise ValueError("preflight_stack_required")
    observations = args.run / "measurements.jsonl"
    initial = next(
        json.loads(line)
        for line in observations.read_text().splitlines()
        if json.loads(line)["kind"] == "initial_database"
    )
    started = initial["timestamp"] - initial["elapsed"]
    while True:
        rows = [json.loads(line) for line in observations.read_text().splitlines()]
        if any(r["kind"] in ("final", "fatal") for r in rows):
            break
        sample = {
            "timestamp": time.time(),
            "elapsed": time.time() - started,
            "containers": [],
            "missed": [],
        }
        names = command(
            "docker",
            "ps",
            "--filter",
            "label=com.docker.compose.project=" + project,
            "--format",
            '{{.Names}} {{.Label "com.docker.compose.service"}}',
        ).splitlines()
        for line in names:
            name, service = line.split()
            try:
                values = command(
                    "docker",
                    "exec",
                    name,
                    "sh",
                    "-c",
                    "cat /sys/fs/cgroup/memory.current /sys/fs/cgroup/memory.peak /sys/fs/cgroup/memory.events",
                ).splitlines()
                sample["containers"].append(
                    {
                        "service": service,
                        "name": name,
                        "current_bytes": int(values[0]),
                        "peak_bytes": int(values[1]),
                        "events": {
                            k: int(v) for k, v in (x.split() for x in values[2:])
                        },
                    }
                )
            except (subprocess.SubprocessError, ValueError):
                sample["missed"].append(service)
        with (args.run / "cgroups.jsonl").open("a") as output:
            output.write(json.dumps(sample) + "\n")
        time.sleep(10)


if __name__ == "__main__":
    main()
