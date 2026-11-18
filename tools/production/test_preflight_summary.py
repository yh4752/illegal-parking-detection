import unittest
from summarize_preflight import public_row, summarize, clock_integrity


class PreflightSummaryTest(unittest.TestCase):
    def test_wall_time_jump_is_not_continuous_performance(self):
        result = clock_integrity(
            [{"timestamp": 100, "elapsed": 0}, {"timestamp": 550, "elapsed": 150}]
        )
        self.assertFalse(result["valid_continuous_timing"])
        self.assertEqual(result["maximum_clock_offset_drift_seconds"], 300)

    def test_outage_not_hidden_by_success_latency(self):
        rows = [
            {
                "kind": "request",
                "phase": "normal",
                "viewer": 0,
                "status": 200,
                "elapsed": 1,
                "ms": 20,
            },
            {
                "kind": "request",
                "phase": "backup_window",
                "viewer": 0,
                "status": 0,
                "elapsed": 12,
                "ms": 8000,
            },
            {
                "kind": "request",
                "phase": "backup_window",
                "viewer": 0,
                "status": 401,
                "elapsed": 13,
                "ms": 10,
            },
            {
                "kind": "request",
                "phase": "backup_window",
                "viewer": 0,
                "status": 200,
                "elapsed": 15,
                "ms": 40,
            },
        ]
        result = summarize(rows)
        self.assertEqual(
            result["phases"]["backup_window"]["successful_response_p95_ms"], 40
        )
        self.assertEqual(result["backup"]["failed_requests_including_relogin"], 2)
        self.assertEqual(result["backup"]["observed_failure_to_recovery_seconds"], 11)
        self.assertFalse(result["backup"]["completed"])
        self.assertIsNone(result["final_http_status"])

    def test_total_uses_simultaneous_samples(self):
        def sample(a, b):
            return {
                "kind": "resources",
                "runner_rss_kib": 2048,
                "containers": [
                    {"name": "/" + name, "service": name, "oom": False, "restarts": 0}
                    for name in ("a", "b")
                ],
                "stats": [
                    {"Name": name, "MemUsage": f"{size}MiB / 1GiB"}
                    for name, size in [("a", a), ("b", b)]
                ],
            }

        result = summarize([sample(100, 10), sample(10, 100)])["resources"]
        self.assertEqual(result["simultaneous_sampled_working_set_peak_mib"], 110)
        self.assertEqual(sum(result["working_set_peak_mib_by_service"].values()), 200)

    def test_generated_event_needs_observed_application_ack(self):
        rows = [
            {
                "kind": "device",
                "records": [
                    {"kind": "VIRTUAL_REPLAY", "event_id": "test-1"},
                    {
                        "kind": "APPLICATION_ACK",
                        "event_id": "test-1",
                        "status": "REJECTED",
                    },
                ],
            }
        ]
        result = summarize(rows)["device"]
        self.assertEqual(result["generated_events_without_observed_ack"], ["test-1"])

    def test_public_export_drops_unreviewed_details(self):
        row = {
            "kind": "fatal",
            "timestamp": 1,
            "elapsed": None,
            "phase": "prepare",
            "error": "RuntimeError",
            "detail": "sensitive unstructured command output",
        }
        self.assertNotIn("detail", public_row(row))
        self.assertNotIn("sensitive", str(public_row(row)))


if __name__ == "__main__":
    unittest.main()
