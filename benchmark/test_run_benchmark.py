from pathlib import Path
import tempfile
import unittest

from run_benchmark import (
    DEFAULT_OUTPUT_DIR,
    DEFAULT_WORKLOAD,
    REPO_ROOT,
    load_workloads,
    parse_args,
    repo_root_from_script,
)


class LegacyBenchmarkDefaultsTest(unittest.TestCase):
    def test_reports_broken_benchmark_script_layout(self):
        with tempfile.TemporaryDirectory() as temporary:
            misplaced = Path(temporary) / "one" / "two" / "runner.py"
            misplaced.parent.mkdir(parents=True)

            with self.assertRaisesRegex(
                    FileNotFoundError,
                    "script path no longer matches <repo>/benchmark/<script>"):
                repo_root_from_script(misplaced)

    def test_defaults_match_controlled_comparison(self):
        args = parse_args([])

        self.assertEqual([1, 2, 4], args.concurrency)
        self.assertEqual(5, args.repetitions)
        self.assertEqual(1, args.warmups)
        self.assertEqual(20, args.buffer_size)
        self.assertEqual(
            REPO_ROOT / "benchmark" / "concurrency_workload.csv",
            args.workload)
        self.assertEqual(
            REPO_ROOT / "benchmark" / "results" / "legacy-multi-jvm" / "runs",
            args.output_dir)

    def test_default_workload_uses_shared_reader(self):
        workloads = load_workloads(DEFAULT_WORKLOAD)

        self.assertEqual(12, len(workloads))
        self.assertEqual(
            ["small_a_range", "medium_m_range", "medium_t_range"] * 4,
            [workload["name"] for workload in workloads])
        self.assertEqual(DEFAULT_OUTPUT_DIR, parse_args([]).output_dir)


if __name__ == "__main__":
    unittest.main()
