from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

from run_benchmark import (
    DEFAULT_WORKLOAD,
    REPO_ROOT,
    Workload,
    load_workloads,
    parse_args,
    repo_root_from_script,
    run_repetition,
)


class FakeMemoryMonitor:
    """Monitor whose accumulated peak is fixed when the instance is created."""

    def __init__(self, peak_rss_bytes):
        self.peak_rss_bytes = peak_rss_bytes
        self.starts = 0
        self.stops = 0

    def start(self):
        self.starts += 1

    def stop(self):
        self.stops += 1

    def group_metrics(self):
        return {"aggregate_peak_rss_bytes": self.peak_rss_bytes}


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


class RepetitionIsolationTest(unittest.TestCase):
    """Each measured repetition must describe only the work it performed."""

    def setUp(self):
        self.args = parse_args([])
        self.workloads = [
            Workload(name=f"w{index}", start_range="a", end_range="b")
            for index in range(3)
        ]

    def measure(self, peaks, run_query):
        """Run one repetition per supplied peak and return monitors and results."""
        monitors = []

        def build_monitor(_sample_interval_ms):
            monitors.append(FakeMemoryMonitor(peaks[len(monitors)]))
            return monitors[-1]

        with patch("run_benchmark.ProcessMemoryMonitor", side_effect=build_monitor), \
                patch("run_benchmark.run_query", side_effect=run_query):
            results = [
                run_repetition(
                    Path("/repo"), Path("/work"), self.workloads,
                    2, repetition, self.args, False)
                for repetition in range(1, len(peaks) + 1)
            ]
        return monitors, results

    @staticmethod
    def succeed(_root, _work_parent, workload, _concurrency, repetition, *_rest):
        return {"status": "ok", "workload": workload["name"], "repetition": repetition}

    def test_peak_memory_falls_back_after_a_spiking_repetition(self):
        monitors, results = self.measure([400, 900, 400, 400, 400], self.succeed)
        peaks = [metrics["aggregate_peak_rss_bytes"] for _, _, metrics in results]

        # A shared accumulator could only ever report a non-decreasing sequence,
        # so a spike would be inherited by every later repetition.
        self.assertEqual([400, 900, 400, 400, 400], peaks)
        self.assertLess(peaks[2], peaks[1])
        self.assertEqual(5, len(set(id(monitor) for monitor in monitors)))
        self.assertEqual([1] * 5, [monitor.starts for monitor in monitors])
        self.assertEqual([1] * 5, [monitor.stops for monitor in monitors])

    def test_makespan_excludes_earlier_repetitions(self):
        def slow_second_repetition(*call):
            if call[4] == 2:
                time.sleep(0.05)
            return self.succeed(*call)

        _monitors, results = self.measure([0, 0, 0], slow_second_repetition)
        makespans = [makespan for _, makespan, _ in results]

        self.assertGreater(makespans[1], makespans[0])
        self.assertLess(makespans[2], makespans[1])

    def test_every_workload_runs_once_per_repetition(self):
        _monitors, results = self.measure([0, 0], self.succeed)

        for repetition, (rows, _makespan, _metrics) in enumerate(results, start=1):
            self.assertEqual(
                ["w0", "w1", "w2"], sorted(row["workload"] for row in rows))
            self.assertEqual([repetition] * 3, [row["repetition"] for row in rows])


if __name__ == "__main__":
    unittest.main()
