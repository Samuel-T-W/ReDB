import unittest

from shared_engine_metrics import MetricsParseError, parse_metrics


HEADER = "REDB_ENGINE_PROTOCOL version=1"
QUERIES = [
    "REDB_ENGINE_QUERY task_id=0 workload_index=0 repetition=0 status_code=0 "
    "result_count=3 client_latency_ns=100 admission_wait_ns=10 execution_ns=90",
    "REDB_ENGINE_QUERY task_id=1 workload_index=1 repetition=0 status_code=0 "
    "result_count=4 client_latency_ns=110 admission_wait_ns=10 execution_ns=100",
    "REDB_ENGINE_QUERY task_id=2 workload_index=0 repetition=1 status_code=0 "
    "result_count=3 client_latency_ns=95 admission_wait_ns=5 execution_ns=90",
    "REDB_ENGINE_QUERY task_id=3 workload_index=1 repetition=1 status_code=0 "
    "result_count=4 client_latency_ns=105 admission_wait_ns=5 execution_ns=100",
]
RUN = (
    "REDB_ENGINE_RUN buffer_size=40 max_concurrent=2 clients=2 frame_budget=20 "
    "use_index=0 workloads=2 warmups=1 repetitions=2 queries=4 successful=4 failed=0 "
    "makespans_ns=150,140 read_ios=12 write_ios=0 result_mismatches=0 residual_pins=0 "
    "catalog_clean=1 residual_buffer_file_ids=0"
)


class SharedEngineMetricsTest(unittest.TestCase):
    def test_parses_complete_metrics(self):
        metrics = parse_metrics("\n".join([HEADER, *QUERIES, RUN]) + "\n")

        self.assertEqual(4, len(metrics.queries))
        self.assertEqual((150, 140), metrics.makespans_ns)
        self.assertEqual(12, metrics.run["read_ios"])

    def test_rejects_unsupported_protocol(self):
        text = "\n".join(["REDB_ENGINE_PROTOCOL version=2", *QUERIES, RUN])

        with self.assertRaisesRegex(MetricsParseError, "expected first line"):
            parse_metrics(text)

    def test_rejects_missing_query_field(self):
        malformed = QUERIES[0].replace(" result_count=3", "")
        text = "\n".join([HEADER, malformed, *QUERIES[1:], RUN])

        with self.assertRaisesRegex(MetricsParseError, "missing=\['result_count'\]"):
            parse_metrics(text)

    def test_rejects_duplicate_run_field(self):
        text = "\n".join([HEADER, *QUERIES, RUN + " failed=0"])

        with self.assertRaisesRegex(MetricsParseError, "duplicate field.*failed"):
            parse_metrics(text)


if __name__ == "__main__":
    unittest.main()
