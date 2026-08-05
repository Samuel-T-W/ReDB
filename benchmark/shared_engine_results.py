"""Write per-query and per-repetition shared-engine benchmark results."""

import csv
from decimal import Decimal
from pathlib import Path

from shared_engine_metrics import EngineMetrics
from workload import read_workload


QUERY_COLUMNS = [
    "max_concurrent", "clients", "buffer_size", "frame_budget", "repetition",
    "task_id", "workload_index", "workload", "start_range", "end_range",
    "status_code", "result_count", "client_latency_ms", "admission_wait_ms",
    "execution_ms",
]
REPETITION_COLUMNS = [
    "max_concurrent", "clients", "buffer_size", "frame_budget", "repetition",
    "queries", "makespan_ms", "throughput_qps",
]
NANOS_PER_MILLISECOND = Decimal(1_000_000)
NANOS_PER_SECOND = Decimal(1_000_000_000)


def write_analysis_csvs(
        output_dir: Path, workload_path: Path,
        configurations: list[EngineMetrics]) -> None:
    workloads = read_workload(workload_path)
    query_rows = []
    repetition_rows = []
    for metrics in configurations:
        common = {
            "max_concurrent": metrics.run["max_concurrent"],
            "clients": metrics.run["clients"],
            "buffer_size": metrics.run["buffer_size"],
            "frame_budget": metrics.run["frame_budget"],
        }
        for query in metrics.queries:
            workload = workloads[query.workload_index]
            query_rows.append({
                **common,
                "repetition": query.repetition,
                "task_id": query.task_id,
                "workload_index": query.workload_index,
                "workload": workload[0],
                "start_range": workload[1],
                "end_range": workload[2],
                "status_code": query.status_code,
                "result_count": query.result_count,
                "client_latency_ms": _milliseconds(query.client_latency_ns),
                "admission_wait_ms": _milliseconds(query.admission_wait_ns),
                "execution_ms": _milliseconds(query.execution_ns),
            })
        queries = metrics.run["workloads"]
        for repetition, makespan in enumerate(metrics.makespans_ns):
            throughput = Decimal(queries) * NANOS_PER_SECOND / Decimal(makespan)
            repetition_rows.append({
                **common,
                "repetition": repetition,
                "queries": queries,
                "makespan_ms": _milliseconds(makespan),
                "throughput_qps": throughput,
            })

    _write_csv(output_dir / "queries.csv", QUERY_COLUMNS, query_rows)
    _write_csv(output_dir / "repetitions.csv", REPETITION_COLUMNS, repetition_rows)


def _milliseconds(nanoseconds: int) -> Decimal:
    return Decimal(nanoseconds) / NANOS_PER_MILLISECOND


def _write_csv(path: Path, columns: list[str], rows: list[dict[str, object]]) -> None:
    with path.open("x", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=columns)
        writer.writeheader()
        writer.writerows(rows)
