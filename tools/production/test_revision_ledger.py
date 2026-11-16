"""Real HTTP and SQLite tests for the LOCAL_ONLY independent revision adapter."""

import concurrent.futures
import copy
import hashlib
import http.client
import json
from pathlib import Path
import selectors
import sqlite3
import subprocess
import sys
import tempfile
import threading
import unittest
import uuid


ROOT = Path(__file__).resolve().parents[2]
LEDGER = ROOT / "infra/backup/ledger.py"
MAX_INTEGER = 9_007_199_254_740_991


def document(end=999_999, generation=1):
    return {"format_version": 1, "zones": {"zone-01": {
        "zone_id": "zone-01", "manifest_generation": generation,
        "reserved_through": end, "reserved_at": "2026-11-16T00:00:00Z",
    }}}


class RevisionLedgerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.database = self.directory / "ledger.sqlite3"
        self.identity = str(uuid.uuid4())
        self.token = "fixture-token-not-a-real-secret"
        self.token_file = self.directory / "token"
        self.token_file.write_text(self.token + "\n")
        self.server = None
        self.addCleanup(self.stop)
        self.assertEqual(self.command("init").returncode, 0, "explicit init must create the ledger")

    def command(self, action, identity=None, database=None):
        args = [sys.executable, str(LEDGER), action, "--db", str(database or self.database),
                "--store-id", identity or self.identity]
        if action == "serve":
            args += ["--token-file", str(self.token_file), "--bind", "127.0.0.1", "--port", "0"]
        return subprocess.run(args, capture_output=True, text=True, timeout=5)

    def start(self):
        self.server = subprocess.Popen([
            sys.executable, str(LEDGER), "serve", "--db", str(self.database),
            "--store-id", self.identity, "--token-file", str(self.token_file),
            "--bind", "127.0.0.1", "--port", "0",
        ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        with selectors.DefaultSelector() as selector:
            selector.register(self.server.stdout, selectors.EVENT_READ)
            self.assertTrue(selector.select(timeout=5), "ledger startup did not finish")
        line = self.server.stdout.readline()
        self.assertTrue(line, "ledger exited before becoming ready")
        ready = json.loads(line)
        self.assertEqual(ready["status"], "LEDGER_READY")
        self.assertEqual(ready["scope"], "LOCAL_ONLY")
        self.port = ready["port"]

    def stop(self):
        if self.server is None:
            return "", ""
        self.server.terminate()
        output = self.server.communicate(timeout=5)
        self.server = None
        return output

    def request(self, method="GET", path="/v1/head", body=None, token=True, raw=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + (self.token if token is True else token)
        payload = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        try:
            connection.request(method, path, payload, headers)
            response = connection.getresponse()
            data = response.read()
            return response.status, dict(response.getheaders()), json.loads(data)
        finally:
            connection.close()

    def head(self):
        status, _, head = self.request()
        self.assertEqual(status, 200)
        return head

    def append(self, head, next_document):
        return self.request("POST", "/v1/versions", {
            "expected_version": head["version"], "expected_sha256": head["sha256"],
            "document": next_document,
        })

    def first_reservation(self):
        status, _, head = self.append(self.head(), document())
        self.assertEqual(status, 201)
        return head

    def corrupt(self, sql, parameters=()):
        with sqlite3.connect(self.database) as connection:
            # Simulate storage/operator corruption outside the append-only HTTP interface.
            triggers = connection.execute("SELECT name FROM sqlite_master WHERE type='trigger'").fetchall()
            for (name,) in triggers:
                connection.execute('DROP TRIGGER "' + name + '"')
            connection.execute(sql, parameters)

    def test_explicit_init_is_exclusive_and_pins_identity(self):
        original = self.database.read_bytes()
        result = self.command("init")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("ledger_already_exists", result.stderr)
        self.assertEqual(self.database.read_bytes(), original)
        result = self.command("serve", identity=str(uuid.uuid4()))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("store_identity_mismatch", result.stderr)

    def test_serve_does_not_create_a_missing_database(self):
        missing = self.directory / "missing.sqlite3"
        result = self.command("serve", database=missing)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("ledger_missing", result.stderr)
        self.assertFalse(missing.exists())

    def test_missing_or_empty_token_file_fails_startup(self):
        self.token_file.unlink()
        self.assertNotEqual(self.command("serve").returncode, 0)
        self.token_file.write_text("\n")
        self.assertNotEqual(self.command("serve").returncode, 0)

    def test_authenticated_head_has_canonical_hash_and_no_cache(self):
        self.start()
        for token in (False, "wrong-token"):
            self.assertEqual(self.request(token=token)[0], 401)
            self.assertEqual(self.request("POST", "/v1/versions", {}, token=token)[0], 401)
        status, headers, head = self.request()
        self.assertEqual(status, 200)
        self.assertEqual(head["store_id"], self.identity)
        self.assertEqual(head["version"], 0)
        self.assertEqual(head["document"], {"format_version": 1, "zones": {}})
        canonical = json.dumps(head["document"], sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        self.assertEqual(head["sha256"], hashlib.sha256(canonical.encode()).hexdigest())
        self.assertIn("no-store", headers["Cache-Control"])
        self.assertIn("no-cache", headers["Cache-Control"])

    def test_compare_and_swap_rejects_stale_version_or_hash(self):
        self.start()
        empty = self.head()
        first = self.first_reservation()
        self.assertEqual(first["version"], 1)
        self.assertEqual(self.append(empty, document(1_999_999, 2))[0], 409)
        invalid_hash = dict(first, sha256="0" * 64)
        self.assertEqual(self.append(invalid_hash, document(1_999_999, 2))[0], 409)
        self.assertEqual(self.head(), first)

    def test_two_concurrent_writers_create_exactly_one_version(self):
        self.start()
        previous = self.first_reservation()
        barrier = threading.Barrier(2)

        def contender(end):
            barrier.wait(timeout=3)
            return self.append(previous, document(end, 2))[0]

        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(contender, [1_999_999, 2_999_999]))
        self.assertEqual(sorted(results), [201, 409])
        self.assertEqual(self.head()["version"], 2)
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(connection.execute("SELECT count(*) FROM versions").fetchone()[0], 3)

    def test_history_and_head_survive_restart(self):
        self.start()
        first = self.first_reservation()
        status, _, second = self.append(first, document(1_999_999, 2))
        self.assertEqual(status, 201)
        self.stop()
        self.start()
        self.assertEqual(self.head(), second)
        with sqlite3.connect(self.database) as connection:
            rows = connection.execute("SELECT version,sha256,previous_chain_sha256,chain_sha256 FROM versions ORDER BY version").fetchall()
        self.assertEqual([row[0] for row in rows], [0, 1, 2])
        self.assertEqual(rows[1][2], rows[0][3])
        self.assertEqual(rows[2][2], rows[1][3])

    def test_no_delete_api_and_database_history_is_append_only(self):
        self.start()
        self.first_reservation()
        self.assertEqual(self.request("DELETE", "/v1/versions")[0], 405)
        with sqlite3.connect(self.database) as connection:
            for sql in ("DELETE FROM versions", "UPDATE versions SET sha256='changed'", "DELETE FROM metadata"):
                with self.assertRaises(sqlite3.DatabaseError):
                    connection.execute(sql)
        self.assertEqual(self.head()["version"], 1)

    def test_sql_replace_cannot_bypass_immutable_history_or_identity(self):
        with sqlite3.connect(self.database) as connection:
            with self.assertRaises(sqlite3.DatabaseError):
                connection.execute("INSERT OR REPLACE INTO versions SELECT * FROM versions WHERE version=0")
            with self.assertRaises(sqlite3.DatabaseError):
                connection.execute("INSERT OR REPLACE INTO metadata VALUES(1, ?)", (str(uuid.uuid4()),))

    def test_removal_regression_and_noop_are_rejected_without_new_versions(self):
        self.start()
        first = self.first_reservation()
        bad_documents = [first["document"], {"format_version": 1, "zones": {}},
                         document(999_998, 2), document(1_999_999, 1), document(999_999, 2),
                         document(1_999_999, 0)]
        changed_time = document()
        changed_time["zones"]["zone-01"]["reserved_at"] = "2026-11-17T00:00:00Z"
        bad_documents.append(changed_time)
        for candidate in bad_documents:
            with self.subTest(candidate=candidate):
                self.assertEqual(self.append(first, candidate)[0], 400)
                self.assertEqual(self.head(), first)

    def test_initialization_claim_is_permanent_across_reservations(self):
        self.start()
        first = self.first_reservation()
        claimed = copy.deepcopy(first["document"])
        claimed["zones"]["zone-01"]["demo_initialization_claimed_at"] = "2026-11-16T00:01:00Z"
        status, _, head = self.append(first, claimed)
        self.assertEqual(status, 201)
        for candidate in (document(), document(1_999_999, 2)):
            self.assertEqual(self.append(head, candidate)[0], 400)
        altered = copy.deepcopy(claimed)
        altered["zones"]["zone-01"]["demo_initialization_claimed_at"] = "2026-11-16T00:02:00Z"
        self.assertEqual(self.append(head, altered)[0], 400)
        extension = document(1_999_999, 2)
        extension["zones"]["zone-01"]["demo_initialization_claimed_at"] = claimed["zones"]["zone-01"]["demo_initialization_claimed_at"]
        self.assertEqual(self.append(head, extension)[0], 201)

    def test_same_range_allows_recovery_audit_but_not_unrelated_edits(self):
        self.start()
        first = self.first_reservation()
        recovered = copy.deepcopy(first["document"])
        recovered["zones"]["zone-01"]["recovery_operation_id"] = "fixture-recovery"
        recovered["zones"]["zone-01"]["recovery_checked_at"] = "2026-11-16T00:02:00Z"
        status, _, head = self.append(first, recovered)
        self.assertEqual(status, 201)
        changed = copy.deepcopy(recovered)
        changed["zones"]["zone-01"]["reserved_at"] = "2026-11-17T00:00:00Z"
        self.assertEqual(self.append(head, changed)[0], 400)

    def test_zone_superset_can_add_independent_reservation(self):
        self.start()
        first = self.first_reservation()
        enlarged = copy.deepcopy(first["document"])
        enlarged["zones"]["zone-02"] = dict(enlarged["zones"]["zone-01"], zone_id="zone-02")
        self.assertEqual(self.append(first, enlarged)[0], 201)

    def test_numeric_and_document_shape_validation(self):
        self.start()
        first = self.first_reservation()
        for field in ("reserved_through", "manifest_generation"):
            for value in (-1, MAX_INTEGER + 1, True, 1.5, "1000000", None):
                invalid = document(1_999_999, 2)
                invalid["zones"]["zone-01"][field] = value
                with self.subTest(field=field, value=value):
                    self.assertEqual(self.append(first, invalid)[0], 400)
        for candidate in ({}, {"format_version": True, "zones": {}}, {"format_version": 1, "zones": []}):
            self.assertEqual(self.append(first, candidate)[0], 400)
        bad_zone = document(1_999_999, 2)
        bad_zone["zones"]["zone-01"]["zone_id"] = "zone-other"
        self.assertEqual(self.append(first, bad_zone)[0], 400)

    def test_malformed_duplicate_json_and_oversized_body_are_rejected(self):
        self.start()
        for raw in (b"{bad", b'{"expected_version":0,"expected_version":1}', b'{"n":NaN}'):
            self.assertEqual(self.request("POST", "/v1/versions", raw=raw)[0], 400)
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        try:
            connection.putrequest("POST", "/v1/versions")
            connection.putheader("Authorization", "Bearer " + self.token)
            connection.putheader("Content-Type", "application/json")
            connection.putheader("Content-Length", "1048577")
            connection.endheaders()
            self.assertEqual(connection.getresponse().status, 400)
        finally:
            connection.close()
        self.assertEqual(self.head()["version"], 0)

    def test_database_disappearance_during_service_does_not_recreate(self):
        self.start()
        self.database.unlink()
        self.assertEqual(self.request()[0], 503)
        self.assertFalse(self.database.exists())

    def test_runtime_hash_corruption_fails_closed(self):
        self.start()
        first = self.first_reservation()
        self.corrupt("UPDATE versions SET sha256=? WHERE version=1", ("0" * 64,))
        self.assertEqual(self.request()[0], 503)
        self.assertEqual(self.append(first, document(1_999_999, 2))[0], 503)

    def test_startup_detects_document_and_history_chain_corruption(self):
        self.start()
        self.first_reservation()
        self.stop()
        pristine = self.database.read_bytes()
        for sql, parameters in (
            ("UPDATE versions SET document=? WHERE version=1", ('{"format_version":1,"zones":{}}',)),
            ("UPDATE versions SET previous_chain_sha256=? WHERE version=1", ("f" * 64,)),
            ("UPDATE versions SET chain_sha256=? WHERE version=0", ("f" * 64,)),
            ("DELETE FROM versions WHERE version=0", ()),
        ):
            with self.subTest(sql=sql):
                self.database.write_bytes(pristine)
                self.corrupt(sql, parameters)
                result = self.command("serve")
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("ledger_corrupt", result.stderr)

    def test_requests_bodies_and_tokens_are_not_logged(self):
        self.start()
        self.request(path="/do-not-log-this-path", token="do-not-log-this-token")
        self.request("POST", "/v1/versions", raw=b"do-not-log-this-body")
        output, errors = self.stop()
        self.assertEqual(output, "")
        self.assertEqual(errors, "")


if __name__ == "__main__":
    unittest.main()
