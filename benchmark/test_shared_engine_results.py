import csv
from pathlib import Path
import tempfile
import unittest

from shared_engine_metrics import EngineMetrics, QueryMetric
from shared_engine_results import write_analysis_csvs


class SharedEngineResultsTest(unittest.TestCase):
    def test_writes_query_and_repetition_analysis_inputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            workload = output / "workload.csv"
            workload.write_text(
                "name,start_range,end_range\nfirst,a,b\nsecond,c,d\n",
                encoding="utf-8")
            metrics = EngineMetrics(
                queries=(
                    QueryMetric(0, 0, 0, 0, 3, 1_234_567, 234_567, 1_000_000),
                    QueryMetric(1, 1, 0, 2, -1, 2_500_000, 500_000, 2_000_000),
                    QueryMetric(2, 0, 1, 0, 3, 1_000_001, 1, 1_000_000),
                    QueryMetric(3, 1, 1, 0, 4, 2_000_000, 0, 2_000_000),
                ),
                makespans_ns=(2_000_000_000, 4_000_000_000),
                run={
                    "max_concurrent": 7,
                    "clients": 8,
                    "buffer_size": 90,
                    "frame_budget": 12,
                    "workloads": 2,
                },
            )

            write_analysis_csvs(output, workload, [metrics])

            with (output / "queries.csv").open(newline="", encoding="utf-8") as source:
                query_reader = csv.DictReader(source)
                queries = list(query_reader)
            self.assertEqual(
                ["max_concurrent", "clients", "buffer_size", "frame_budget", "repetition",
                 "task_id", "workload_index", "workload", "start_range", "end_range",
                 "status_code", "result_count", "client_latency_ms", "admission_wait_ms",
                 "execution_ms"],
                query_reader.fieldnames)
            self.assertEqual("7", queries[0]["max_concurrent"])
            self.assertEqual("8", queries[0]["clients"])
            self.assertEqual("90", queries[0]["buffer_size"])
            self.assertEqual("12", queries[0]["frame_budget"])
            self.assertEqual("first", queries[0]["workload"])
            self.assertEqual("a", queries[0]["start_range"])
            self.assertEqual("1.234567", queries[0]["client_latency_ms"])
            self.assertEqual("0.234567", queries[0]["admission_wait_ms"])
            self.assertEqual("2", queries[1]["status_code"])
            self.assertEqual("-1", queries[1]["result_count"])

            with (output / "repetitions.csv").open(newline="", encoding="utf-8") as source:
                repetition_reader = csv.DictReader(source)
                repetitions = list(repetition_reader)
            self.assertEqual(
                ["max_concurrent", "clients", "buffer_size", "frame_budget", "repetition",
                 "queries", "makespan_ms", "throughput_qps"],
                repetition_reader.fieldnames)
            self.assertEqual(["0", "1"], [row["repetition"] for row in repetitions])
            self.assertEqual(["2", "2"], [row["queries"] for row in repetitions])
            self.assertEqual(["2000", "4000"], [row["makespan_ms"] for row in repetitions])
            self.assertEqual(["1", "0.5"], [row["throughput_qps"] for row in repetitions])


if __name__ == "__main__":
    unittest.main()
