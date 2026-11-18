from types import SimpleNamespace
import unittest
from preflight_4gb import parse_stats_output, verify_clock_progress


class ResourceStreamTest(unittest.TestCase):
    def test_suspend_or_clock_jump_invalidates_continuous_run(self):
        for monotonic, wall in [(1800, 2250), (1800, 1700)]:
            with self.assertRaisesRegex(RuntimeError, "clock_discontinuity"):
                verify_clock_progress(monotonic, wall)
        verify_clock_progress(1800, 1800.1)

    def test_nonzero_exit_preserves_complete_rows(self):
        rows, warnings = parse_stats_output(
            SimpleNamespace(
                returncode=1,
                stdout='{"Name":"backend","MemUsage":"300MiB / 1GiB"}\n',
                stderr="EOF\n",
            )
        )
        self.assertEqual(rows[0]["Name"], "backend")
        self.assertEqual(warnings, ["docker_stats_nonzero:EOF"])

    def test_partial_line_does_not_discard_other_services(self):
        rows, warnings = parse_stats_output(
            SimpleNamespace(
                returncode=1, stdout='{"Name":"backend"}\n{"Name":', stderr="EOF"
            )
        )
        self.assertEqual(len(rows), 1)
        self.assertIn("incomplete_stats_row", warnings)

    def test_empty_failed_sample_is_not_zero_usage(self):
        with self.assertRaisesRegex(RuntimeError, "no_resource_rows"):
            parse_stats_output(SimpleNamespace(returncode=1, stdout="", stderr="EOF"))


if __name__ == "__main__":
    unittest.main()
