"""Read-only dashboard-query and SSE smoke observations for a local preflight run."""

import argparse
import json
from pathlib import Path
import time
import urllib.parse
from local_backup import LocalBackup, ROOT
from verify_o2 import Client, snapshot


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path)
    args = parser.parse_args()
    stack = LocalBackup(args.run / "stack")
    if not stack.release["project"].startswith(
        "parking-o4-"
    ) or not args.run.name.startswith("preflight-"):
        raise ValueError("preflight_stack_required")
    dashboard = json.loads(
        (ROOT / "infra/monitoring/grafana/dashboards/parking.json").read_text()
    )
    queries = [t["expr"] for p in dashboard["panels"] for t in p.get("targets", [])]
    while True:
        rows = [
            json.loads(line)
            for line in (args.run / "measurements.jsonl").read_text().splitlines()
        ]
        final = any(r["kind"] in ("final", "fatal") for r in rows)
        if final:
            break  # The runner may now stop the stack; that is not a load-test failure.
        started = time.time()
        record = {"timestamp": started, "final": final, "queries": []}
        for expression in queries:
            before = time.monotonic()
            try:
                response = stack.prometheus_api(
                    "/api/v1/query?" + urllib.parse.urlencode({"query": expression})
                )
                record["queries"].append(
                    {
                        "expression": expression,
                        "status": response["status"],
                        "series": len(response.get("data", {}).get("result", [])),
                        "ms": (time.monotonic() - before) * 1000,
                    }
                )
            except Exception as error:
                record["queries"].append(
                    {"expression": expression, "error": type(error).__name__}
                )
        connection = None
        try:
            client = Client(stack)
            client.login(
                stack.release["username"],
                Path(stack.release["password_file"]).read_text(),
            )
            connection, response = client.stream()
            payload = snapshot(response)
            record["sse"] = {
                "snapshot_received": True,
                "view_revision": payload.get("view_revision"),
            }
        except Exception as error:
            record["sse"] = {"snapshot_received": False, "error": type(error).__name__}
        finally:
            if connection:
                connection.close()
        with (args.run / "dashboard-queries.jsonl").open("a") as file:
            file.write(json.dumps(record) + "\n")
        time.sleep(max(0, 30 - (time.time() - started)))


if __name__ == "__main__":
    main()
