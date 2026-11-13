"""Print a canonical event or one deliberately invalid variation; never publish implicitly."""
import argparse
import json
from pathlib import Path


def main():
    root = Path(__file__).resolve().parents[1]
    cases_path = root / "docs/design/event-validation-cases.json"
    design_cases = {case["id"]: case for case in json.loads(cases_path.read_text(encoding="utf-8"))["cases"]}
    parser = argparse.ArgumentParser(description=__doc__)
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--case", choices=["valid", "sequence-mismatch", "missing-field", "unknown-device"], default="valid")
    selection.add_argument("--design-case", choices=design_cases, help="Print an acceptance fixture from event-validation-cases.json")
    parser.add_argument("--message-id", help="Override message_id for a new message; reuse only for intentional retransmission/conflict")
    parser.add_argument("--device-seq", type=int, help="Set both envelope and snapshot sequence")
    args = parser.parse_args()
    examples = root / "reference/v1.43/개발자료/09_구현설계/contracts/examples.json"
    originals = json.loads(examples.read_text(encoding="utf-8"))
    if args.design_case:
        case = design_cases[args.design_case]
        event = originals[case["base_example"]]
        for pointer, value in case["changes"].items():
            parts = [part.replace("~1", "/").replace("~0", "~") for part in pointer.split("/")[1:]]
            parent = event
            for part in parts[:-1]:
                parent = parent[int(part)] if isinstance(parent, list) else parent[part]
            key = int(parts[-1]) if isinstance(parent, list) else parts[-1]
            if isinstance(parent, dict) and key not in parent:
                raise ValueError(f"Unknown fixture path: {pointer}")
            parent[key] = value
    else:
        event = originals["guidance_complete"]
    if args.message_id is not None:
        if not 1 <= len(args.message_id) <= 128 or "\x00" in args.message_id:
            parser.error("message-id must contain 1..128 characters without NUL")
        event["message_id"] = args.message_id
    if args.device_seq is not None:
        if not 0 <= args.device_seq <= 9007199254740991:
            parser.error("device-seq must be within 0..9007199254740991")
        event["device_seq"] = args.device_seq
        event["snapshot"]["device_seq"] = args.device_seq
    if args.case == "sequence-mismatch":
        event["snapshot"]["device_seq"] = event["device_seq"] + 1
    elif args.case == "missing-field":
        del event["image_update"]
    elif args.case == "unknown-device":
        event["device_id"] = "edge-unknown"
    print(json.dumps(event, ensure_ascii=False))


if __name__ == "__main__":
    main()
