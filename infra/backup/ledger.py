#!/usr/bin/env python3
"""LOCAL_ONLY revision ledger, isolated from the application's DB/manifest backups.

This SQLite adapter exercises independent storage semantics on one local Docker
host. It is not an external provider or protection against loss/rollback of that
host. Never restore this ledger from an application backup. There is no delete,
reset, replacement, or implicit initialization API.

All HTTP calls require a bearer token loaded from --token-file. GET /v1/head
returns {store_id, version, sha256, document}. POST /v1/versions accepts
{expected_version, expected_sha256, document}, returning the committed head with
201, or 409 for a stale compare-and-swap. sha256 hashes canonical UTF-8 document
JSON (sorted keys, compact separators, unescaped Unicode). The internal history
hash chain also binds the pinned store UUID, version, and previous chain hash.
"""

import argparse
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import sqlite3
import sys
import uuid


MAX_BODY = 1_048_576
MAX_INTEGER = 9_007_199_254_740_991
EMPTY_DOCUMENT = {"format_version": 1, "zones": {}}
ZERO_HASH = "0" * 64
HASH_PATTERN = re.compile(r"[0-9a-f]{64}\Z")
CLAIM = "demo_initialization_claimed_at"


class LedgerError(Exception):
    """Safe error codes only: never include a request, secret, or raw SQL error."""


class Conflict(LedgerError):
    pass


class InvalidDocument(LedgerError):
    pass


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def digest(value):
    return hashlib.sha256(canonical(value).encode("utf-8")).hexdigest()


def parse_json(value):
    def unique_pairs(pairs):
        result = {}
        for key, item in pairs:
            if key in result:
                raise InvalidDocument("duplicate_json_key")
            result[key] = item
        return result

    def invalid_constant(_):
        raise InvalidDocument("invalid_json_number")

    try:
        return json.loads(value, object_pairs_hook=unique_pairs, parse_constant=invalid_constant)
    except (ValueError, UnicodeError, RecursionError) as error:
        raise InvalidDocument("invalid_json") from error


def safe_integer(value):
    return type(value) is int and 0 <= value <= MAX_INTEGER


def validate_document(document):
    if (not isinstance(document, dict) or set(document) != {"format_version", "zones"}
            or type(document["format_version"]) is not int or document["format_version"] != 1
            or not isinstance(document["zones"], dict)):
        raise InvalidDocument("invalid_document")
    for zone_id, zone in document["zones"].items():
        if (not isinstance(zone_id, str) or not zone_id or not isinstance(zone, dict)
                or zone.get("zone_id") != zone_id
                or not safe_integer(zone.get("manifest_generation"))
                or not safe_integer(zone.get("reserved_through"))
                or not isinstance(zone.get("reserved_at"), str) or not zone["reserved_at"]):
            raise InvalidDocument("invalid_zone")
        if CLAIM in zone and (not isinstance(zone[CLAIM], str) or not zone[CLAIM]):
            raise InvalidDocument("invalid_initialization_claim")
        known = {"zone_id", "manifest_generation", "reserved_through", "reserved_at", CLAIM}
        if any(key not in known and not key.startswith("recovery_") for key in zone):
            raise InvalidDocument("unknown_zone_field")

    def validate_numbers(value):
        if isinstance(value, dict):
            for item in value.values():
                validate_numbers(item)
        elif isinstance(value, list):
            for item in value:
                validate_numbers(item)
        elif isinstance(value, float) or (type(value) is int and not safe_integer(value)):
            raise InvalidDocument("unsafe_integer")

    validate_numbers(document)
    if len(canonical(document).encode("utf-8")) > MAX_BODY:
        raise InvalidDocument("document_too_large")


def validate_transition(previous, following):
    validate_document(following)
    if following == previous:
        raise InvalidDocument("no_op_version")
    old_zones, new_zones = previous["zones"], following["zones"]
    if not old_zones.keys() <= new_zones.keys():
        raise InvalidDocument("zone_removal_forbidden")
    for zone_id, old in old_zones.items():
        new = new_zones[zone_id]
        if CLAIM in old and new.get(CLAIM) != old[CLAIM]:
            raise InvalidDocument("initialization_claim_immutable")
        old_end, new_end = old["reserved_through"], new["reserved_through"]
        old_generation, new_generation = old["manifest_generation"], new["manifest_generation"]
        if new_end < old_end or new_generation < old_generation:
            raise InvalidDocument("reservation_regression")
        if old == new:
            continue
        if new_end > old_end and new_generation > old_generation:
            continue
        if new_end != old_end or new_generation != old_generation:
            raise InvalidDocument("reservation_generation_mismatch")
        changed = {key for key in old.keys() | new.keys() if old.get(key) != new.get(key)}
        allowed = {key for key in changed if key.startswith("recovery_") and key in new}
        if CLAIM not in old and CLAIM in new:
            allowed.add(CLAIM)
        if not changed or not changed <= allowed:
            raise InvalidDocument("non_reservation_change_forbidden")


def chain_digest(store_id, version, document_hash, previous_hash):
    return digest({"store_id": store_id, "version": version, "sha256": document_hash,
                   "previous_chain_sha256": previous_hash})


def connect(database):
    # mode=rw is essential: a missing ledger must never become a new empty DB.
    if not database.is_file():
        raise LedgerError("ledger_missing")
    connection = sqlite3.connect(database.resolve().as_uri() + "?mode=rw", uri=True, timeout=10,
                                 isolation_level=None)
    connection.execute("PRAGMA synchronous=FULL")
    connection.execute("PRAGMA busy_timeout=10000")
    return connection


def verify_history(connection, store_id):
    identities = connection.execute("SELECT store_id FROM metadata").fetchall()
    if identities != [(store_id,)]:
        raise LedgerError("store_identity_mismatch")
    previous_chain = ZERO_HASH
    previous_document = None
    head = None
    for expected, row in enumerate(connection.execute(
            "SELECT version,document,sha256,previous_chain_sha256,chain_sha256 FROM versions ORDER BY version")):
        version, serialized, document_hash, recorded_previous, chain_hash = row
        try:
            document = parse_json(serialized)
            validate_document(document)
            if previous_document is None:
                valid_transition = document == EMPTY_DOCUMENT
            else:
                validate_transition(previous_document, document)
                valid_transition = True
            valid = (safe_integer(version) and version == expected and valid_transition
                     and serialized == canonical(document) and document_hash == digest(document)
                     and recorded_previous == previous_chain
                     and chain_hash == chain_digest(store_id, version, document_hash, previous_chain))
        except (InvalidDocument, TypeError, UnicodeError, RecursionError) as error:
            raise LedgerError("ledger_corrupt") from error
        if not valid:
            raise LedgerError("ledger_corrupt")
        head = {"store_id": store_id, "version": version, "sha256": document_hash, "document": document}
        previous_document, previous_chain = document, chain_hash
    if head is None:
        raise LedgerError("ledger_corrupt")
    return head, previous_chain


def initialize(database, store_id):
    try:
        descriptor = os.open(database, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError as error:
        raise LedgerError("ledger_already_exists") from error
    os.close(descriptor)
    connection = connect(database)
    try:
        connection.execute("BEGIN IMMEDIATE")
        connection.execute("CREATE TABLE metadata(singleton INTEGER PRIMARY KEY CHECK(singleton=1), store_id TEXT NOT NULL)")
        connection.execute("CREATE TABLE versions(version INTEGER PRIMARY KEY, document TEXT NOT NULL, sha256 TEXT NOT NULL, previous_chain_sha256 TEXT NOT NULL, chain_sha256 TEXT NOT NULL)")
        connection.execute("INSERT INTO metadata VALUES(1, ?)", (store_id,))
        initial_hash = digest(EMPTY_DOCUMENT)
        connection.execute("INSERT INTO versions VALUES(0,?,?,?,?)", (
            canonical(EMPTY_DOCUMENT), initial_hash, ZERO_HASH, chain_digest(store_id, 0, initial_hash, ZERO_HASH)))
        for table in ("metadata", "versions"):
            for action in ("UPDATE", "DELETE"):
                connection.execute(f"CREATE TRIGGER {table}_no_{action.lower()} BEFORE {action} ON {table} "
                                   "BEGIN SELECT RAISE(ABORT, 'append_only'); END")
        # SQLite REPLACE can skip DELETE triggers when recursive_triggers is off.
        # Reject duplicate identity inserts and any insertion except the next version.
        connection.execute("CREATE TRIGGER metadata_no_insert BEFORE INSERT ON metadata "
                           "BEGIN SELECT RAISE(ABORT, 'immutable_identity'); END")
        connection.execute("CREATE TRIGGER versions_next_only BEFORE INSERT ON versions "
                           "WHEN NEW.version != (SELECT max(version)+1 FROM versions) "
                           "BEGIN SELECT RAISE(ABORT, 'append_only'); END")
        connection.commit()
    finally:
        connection.close()
    directory_descriptor = os.open(database.parent, os.O_RDONLY)
    try:
        os.fsync(directory_descriptor)
    finally:
        os.close(directory_descriptor)


class Ledger:
    def __init__(self, database, store_id):
        self.database, self.store_id = database, store_id
        self.head()

    def head(self):
        connection = connect(self.database)
        try:
            connection.execute("BEGIN")
            head, _ = verify_history(connection, self.store_id)
            connection.commit()
            return head
        finally:
            connection.close()

    def append(self, request):
        if (not isinstance(request, dict)
                or set(request) != {"expected_version", "expected_sha256", "document"}
                or not safe_integer(request["expected_version"])
                or not isinstance(request["expected_sha256"], str)
                or HASH_PATTERN.fullmatch(request["expected_sha256"]) is None):
            raise InvalidDocument("invalid_version_request")
        validate_document(request["document"])
        connection = connect(self.database)
        try:
            # Cross-process CAS: verify and append occur under the same SQLite writer lock.
            connection.execute("BEGIN IMMEDIATE")
            head, previous_chain = verify_history(connection, self.store_id)
            if (request["expected_version"] != head["version"]
                    or request["expected_sha256"] != head["sha256"]):
                raise Conflict("stale_version")
            validate_transition(head["document"], request["document"])
            version = head["version"] + 1
            if not safe_integer(version):
                raise InvalidDocument("version_space_exhausted")
            document = request["document"]
            document_hash = digest(document)
            connection.execute("INSERT INTO versions VALUES(?,?,?,?,?)", (
                version, canonical(document), document_hash, previous_chain,
                chain_digest(self.store_id, version, document_hash, previous_chain)))
            connection.commit()  # synchronous=FULL: respond only after durable commit.
            return {"store_id": self.store_id, "version": version, "sha256": document_hash, "document": document}
        finally:
            connection.close()


class LedgerServer(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        # Base class tracebacks may expose request data; do not emit them.
        pass


class Handler(BaseHTTPRequestHandler):
    def setup(self):
        super().setup()
        self.connection.settimeout(10)

    def log_message(self, format, *args):
        # Includes access, malformed-request, and error logging from the stdlib.
        pass

    def send_error(self, code, message=None, explain=None):
        self.respond(code, {"error": "invalid_request"})

    def respond(self, status, body):
        encoded = canonical(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate")
        self.send_header("Pragma", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        if self.command != "HEAD":
            self.wfile.write(encoded)

    def authorized(self):
        values = self.headers.get_all("Authorization", [])
        return len(values) == 1 and hmac.compare_digest(values[0].encode("utf-8"), self.server.authorization)

    def dispatch(self):
        if not self.authorized():
            self.respond(401, {"error": "unauthorized"})
            return
        try:
            if self.command == "GET" and self.path == "/v1/head":
                self.respond(200, self.server.ledger.head())
            elif self.command == "POST" and self.path == "/v1/versions":
                lengths = self.headers.get_all("Content-Length", [])
                if (len(lengths) != 1 or not lengths[0].isascii() or not lengths[0].isdigit()
                        or len(lengths[0]) > 10 or not 0 < int(lengths[0]) <= MAX_BODY
                        or self.headers.get("Transfer-Encoding") is not None
                        or self.headers.get_content_type() != "application/json"):
                    raise InvalidDocument("invalid_request_body")
                size = int(lengths[0])
                body = self.rfile.read(size)
                if len(body) != size:
                    raise InvalidDocument("incomplete_request_body")
                self.respond(201, self.server.ledger.append(parse_json(body)))
            elif self.command in ("GET", "POST"):
                self.respond(404, {"error": "not_found"})
            else:
                self.respond(405, {"error": "method_not_allowed"})
        except Conflict:
            self.respond(409, {"error": "stale_version"})
        except (InvalidDocument, UnicodeError, RecursionError):
            self.respond(400, {"error": "invalid_request"})
        except (LedgerError, sqlite3.Error, OSError):
            self.respond(503, {"error": "ledger_unavailable"})

    do_GET = do_POST = do_DELETE = do_PUT = do_PATCH = do_HEAD = do_OPTIONS = dispatch


def main():
    parser = argparse.ArgumentParser(description="LOCAL_ONLY independent revision ledger")
    commands = parser.add_subparsers(dest="command", required=True)
    for action in ("init", "serve"):
        command = commands.add_parser(action)
        command.add_argument("--db", required=True, type=Path)
        command.add_argument("--store-id", required=True)
        if action == "serve":
            command.add_argument("--token-file", required=True, type=Path)
            command.add_argument("--bind", default="0.0.0.0")
            command.add_argument("--port", type=int, default=8088)
    args = parser.parse_args()
    try:
        store_id = str(uuid.UUID(args.store_id))
        if store_id != args.store_id:
            raise LedgerError("invalid_store_identity")
        if args.command == "init":
            initialize(args.db, store_id)
            print(canonical({"status": "LEDGER_INITIALIZED", "scope": "LOCAL_ONLY", "store_id": store_id}))
            return 0
        ledger = Ledger(args.db, store_id)
        token = args.token_file.read_bytes().strip()
        if not token or len(token) > 4096 or any(byte <= 32 or byte >= 127 for byte in token):
            raise LedgerError("invalid_token_file")
        with LedgerServer((args.bind, args.port), Handler) as server:
            server.ledger = ledger
            server.authorization = b"Bearer " + token
            print(canonical({"status": "LEDGER_READY", "scope": "LOCAL_ONLY", "port": server.server_port}), flush=True)
            server.serve_forever()
    except LedgerError as error:
        print(str(error), file=sys.stderr)
        return 1
    except (sqlite3.Error, ValueError, OSError):
        print("ledger_corrupt_or_unavailable", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 0


if __name__ == "__main__":
    sys.exit(main())
