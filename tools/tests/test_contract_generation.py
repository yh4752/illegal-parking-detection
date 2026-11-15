import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("contracts", ROOT / "tools/build_contracts.py")
contracts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(contracts)


class ContractGenerationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.overlay = contracts.read_json(ROOT / contracts.OVERLAY)
        for meta in self.overlay["base"].values():
            path = self.root / meta["path"]
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / meta["path"], path)
        self.write_overlay()

    def write_overlay(self):
        path = self.root / contracts.OVERLAY
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(self.overlay), encoding="utf-8")

    def test_generation_is_deterministic_and_preserves_mqtt_and_success_fields(self):
        generated = contracts.generate(self.root)
        self.assertEqual(generated, contracts.generate(self.root))
        source = contracts.read_json(self.root / self.overlay["base"]["parking.schema.json"]["path"])
        effective = json.loads(generated["parking.schema.json"])
        for name, value in source["$defs"].items():
            if name != "RestError":
                self.assertEqual(value, effective["$defs"][name], name)
        self.assertEqual(set(source["$defs"]["RestError"]["required"]), set(effective["$defs"]["RestError"]["required"]))
        self.assertFalse((self.root / contracts.OUTPUT).exists())  # generate() itself has no write side effects

    def test_mqtt_enum_expansion_is_rejected_even_with_a_valid_patch_target(self):
        self.overlay["schema_patch"].append({"op": "replace", "path": "/$defs/error_code/enum", "value": ["INTERNAL_ERROR"]})
        self.write_overlay()
        with self.assertRaisesRegex(ValueError, "REST-only scope"):
            contracts.generate(self.root)

    def test_success_response_changes_are_rejected(self):
        self.overlay["openapi_patch"].append({"op": "replace", "path": "/paths/~1api~1csrf/get/responses/200/description", "value": "changed"})
        self.write_overlay()
        with self.assertRaisesRegex(ValueError, "REST-only scope"):
            contracts.generate(self.root)

    def test_source_hash_mismatch_stops_generation(self):
        path = self.root / self.overlay["base"]["parking.schema.json"]["path"]
        path.write_text(path.read_text() + " ")
        with self.assertRaisesRegex(ValueError, "source hash mismatch"):
            contracts.generate(self.root)

    def test_patch_replacement_cannot_silently_create_a_new_property(self):
        with self.assertRaisesRegex(ValueError, "precondition failed"):
            contracts.patch_document({"a": {}}, [{"op": "replace", "path": "/a/missing", "value": 1}])

    def test_duplicate_json_keys_are_rejected(self):
        path = self.root / "bad.json"
        path.write_text('{"code":"one","code":"two"}')
        with self.assertRaisesRegex(ValueError, "duplicate JSON key"):
            contracts.read_json(path)


if __name__ == "__main__":
    unittest.main()
