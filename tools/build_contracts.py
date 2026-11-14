#!/usr/bin/env python3
"""Build REST contracts from immutable v1.43 + the reviewed REST-only overlay.

No network or third-party packages. MQTT continues to use the original resource.
Run --check in CI; default writes deterministic generated files atomically.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import tempfile

ROOT = Path(__file__).resolve().parents[1]
OVERLAY = Path("contracts/overlays/public-demo-rest-v1.json")
OUTPUT = Path("contracts/effective")


def read_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate JSON key: {key}")
            result[key] = value
        return result
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)


def patch_document(document, operations):
    result = copy.deepcopy(document)
    for op in operations:
        if op["op"] not in ("add", "replace") or not op["path"].startswith("/"):
            raise ValueError("unsupported patch operation")
        tokens = [t.replace("~1", "/").replace("~0", "~") for t in op["path"][1:].split("/")]
        parent = result
        for token in tokens[:-1]:
            parent = parent[token]
        key = tokens[-1]
        if not isinstance(parent, dict):
            raise ValueError("only object property patches are permitted")
        if (op["op"] == "replace") != (key in parent):
            raise ValueError(f"patch target precondition failed: {op['path']}")
        parent[key] = copy.deepcopy(op["value"])
    return result


def verify_refs(document):
    def walk(node):
        if isinstance(node, dict):
            if "$ref" in node:
                ref = node["$ref"]
                if not ref.startswith("#/"):
                    raise ValueError(f"external reference not permitted: {ref}")
                value = document
                for token in ref[2:].split("/"):
                    value = value[token.replace("~1", "/").replace("~0", "~")]
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)
    walk(document)


def verify_scope(original, generated, schema_kind):
    # Reconstruct the permitted changes independently of the supplied patch paths.
    expected = copy.deepcopy(original)
    base_ref = "#/$defs/error_code" if schema_kind == "schema" else "#/components/schemas/error_code"
    definitions = expected["$defs"] if schema_kind == "schema" else expected["components"]["schemas"]
    definitions["RestError"]["properties"]["code"] = {
        "anyOf": [{"$ref": base_ref}, {"type": "string", "enum": ["RATE_LIMITED", "SERVICE_UNAVAILABLE", "INTERNAL_ERROR"]}]
    }
    if schema_kind == "openapi":
        allowed = {("/api/csrf", "get"), ("/api/auth/login", "post"), ("/api/auth/logout", "post"),
                   ("/api/auth/me", "get"), ("/api/zones/{id}/status", "get"), ("/api/events", "get"),
                   ("/api/events/{id}", "get"), ("/api/stream", "get")}
        for path, method in allowed:
            responses = generated["paths"][path][method]["responses"]
            for status in ("500", "503", "429"):
                if status == "429" and path not in ("/api/auth/login", "/api/stream"):
                    continue
                response = responses[status]
                if response["content"] != {"application/json": {"schema": {"$ref": "#/components/schemas/RestError"}}}:
                    raise ValueError("new errors must use RestError")
                expected["paths"][path][method]["responses"][status] = copy.deepcopy(response)
    if expected != generated:
        raise ValueError("overlay changed fields outside its REST-only scope")
    verify_refs(generated)


def generate(root=ROOT):
    overlay = read_json(root / OVERLAY)
    outputs = {}
    for filename, kind in (("parking.schema.json", "schema"), ("openapi.json", "openapi")):
        source = overlay["base"][filename]
        path = (root / source["path"]).resolve()
        if not path.is_relative_to((root / "reference/v1.43").resolve()):
            raise ValueError("base must be inside preserved reference/v1.43")
        if hashlib.sha256(path.read_bytes()).hexdigest() != source["sha256"]:
            raise ValueError(f"source hash mismatch: {filename}")
        original = read_json(path)
        result = patch_document(original, overlay[kind + "_patch"])
        verify_scope(original, result, kind)
        outputs[filename] = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    return outputs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail if a generated file is stale or missing")
    args = parser.parse_args()
    outputs = generate()
    for name, text in outputs.items():
        path = ROOT / OUTPUT / name
        if args.check:
            if not path.is_file() or path.read_text(encoding="utf-8") != text:
                raise SystemExit(f"Stale contract: {path.relative_to(ROOT)}; run python3 tools/build_contracts.py")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent, delete=False) as f:
                temp = Path(f.name)
                f.write(text)
            try:
                temp.replace(path)
            finally:
                temp.unlink(missing_ok=True)
    print("REST contracts: " + ("verified" if args.check else "generated") + "; MQTT source unchanged")


if __name__ == "__main__":
    main()
