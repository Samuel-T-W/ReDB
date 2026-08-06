from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from run_shared_engine_benchmark import (
    DEFAULT_WORKLOAD,
    MATRIX,
    find_repo_root,
    run_benchmark,
)
from workload import read_workload


class SharedEngineBenchmarkRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        self.workload = self.root / "workload.csv"
        self.workload.write_text(
            "name,start_range,end_range\nA,a,b\nB,c,d\nC,e,f\n",
            encoding="utf-8")

    def tearDown(self):
        self.temporary.cleanup()

    def test_default_manifest_contains_four_cyclic_copies(self):
        rows = read_workload(DEFAULT_WORKLOAD)

        self.assertEqual(12, len(rows))
        self.assertEqual(
            ["small_a_range", "medium_m_range", "medium_t_range"] * 4,
            [row[0] for row in rows])

    def test_finds_repo_root_from_arbitrarily_nested_path(self):
        repository = self.root / "repository"
        (repository / "src").mkdir(parents=True)
        (repository / "pom.xml").touch()
        (repository / "src" / "EngineBenchmark.java").touch()
        nested = repository / "one" / "two" / "three"
        nested.mkdir(parents=True)

        self.assertEqual(repository, find_repo_root(nested))

    def test_repo_root_discovery_fails_without_markers(self):
        markerless = self.root / "markerless" / "nested"
        markerless.mkdir(parents=True)

        with self.assertRaisesRegex(FileNotFoundError, "could not find ReDB repository root"):
            find_repo_root(markerless)

    def test_wires_build_and_fixed_matrix_commands(self):
        output = self.root / "run"

        with patch(
                "run_shared_engine_benchmark.subprocess.run",
                return_value=subprocess.CompletedProcess([], 0)) as runner, patch(
                "run_shared_engine_benchmark.parse_metrics_file") as parser, patch(
                "run_shared_engine_benchmark.write_analysis_csvs") as writer:
            writer.side_effect = lambda *_: self.assertEqual(3, parser.call_count)
            successful = run_benchmark(self.workload, output, False, self.root)

        self.assertEqual(4, runner.call_count)
        self.assertEqual(["mvn", "-q", "-DskipTests", "compile"], runner.call_args_list[0].args[0])
        self.assertEqual(3, parser.call_count)
        writer.assert_called_once()
        self.assertEqual(3, len(writer.call_args.args[2]))
        self.assertTrue(all(
            metrics is parser.return_value for metrics in writer.call_args.args[2]))
        self.assertEqual(self.workload.read_bytes(), (output / "workload.csv").read_bytes())
        self.assertEqual(
            [output / f"concurrency-{concurrency}-buffer-{buffer}" for concurrency, _, buffer in MATRIX],
            successful)
        for call, (concurrency, clients, buffer) in zip(runner.call_args_list[1:], MATRIX):
            config = output / f"concurrency-{concurrency}-buffer-{buffer}"
            self.assertEqual(
                [
                    "java", "-cp", "target/classes", "EngineBenchmark",
                    "--workload", str(output / "workload.csv"),
                    "--buffer-size", str(buffer),
                    "--max-concurrent", str(concurrency),
                    "--clients", str(clients),
                    "--repetitions", "5",
                    "--warmups", "1",
                    "--output-dir", str(config),
                    "--result-file", str(config / "engine.metrics"),
                ],
                call.args[0])
            self.assertEqual(self.root, call.kwargs["cwd"])
            self.assertTrue(call.kwargs["check"])
            self.assertTrue((config / "engine.stderr.log").is_file())
            self.assertEqual(subprocess.DEVNULL, call.kwargs["stdout"])
            self.assertEqual(config / "engine.stderr.log", Path(call.kwargs["stderr"].name))

    def test_fails_fast_and_preserves_failed_config(self):
        failure = subprocess.CalledProcessError(1, ["java"])
        output = self.root / "failed-run"

        with patch(
                "run_shared_engine_benchmark.subprocess.run",
                side_effect=[subprocess.CompletedProcess([], 0), failure]) as runner, patch(
                "run_shared_engine_benchmark.parse_metrics_file") as parser, patch(
                "run_shared_engine_benchmark.write_analysis_csvs") as writer:
            with self.assertRaises(subprocess.CalledProcessError):
                run_benchmark(self.workload, output, False, self.root)

        self.assertEqual(2, runner.call_count)
        parser.assert_not_called()
        writer.assert_not_called()
        failed = output / "concurrency-1-buffer-20"
        self.assertTrue((failed / "engine.stderr.log").is_file())
        self.assertFalse((output / "concurrency-2-buffer-40").exists())
        self.assertFalse((output / "queries.csv").exists())
        self.assertFalse((output / "repetitions.csv").exists())
        self.assertFalse((output / "summary.csv").exists())

    def test_rejects_existing_output_directory(self):
        output = self.root / "existing"
        output.mkdir()

        with patch("run_shared_engine_benchmark.subprocess.run") as runner:
            with self.assertRaises(FileExistsError):
                run_benchmark(self.workload, output, False, self.root)

        runner.assert_not_called()


if __name__ == "__main__":
    unittest.main()
