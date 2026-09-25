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
SUMMARY_COLUMNS = [
    "max_concurrent", "clients", "buffer_size", "frame_budget", "repetitions",
    "queries", "successful", "failed", "latency_mean_ms", "latency_p50_ms",
    "latency_p95_ms", "admission_wait_mean_ms", "execution_mean_ms",
    "makespan_mean_ms", "makespan_median_ms", "makespan_min_ms",
    "makespan_max_ms", "throughput_mean_qps", "read_ios", "write_ios",
    "result_mismatches", "residual_pins", "catalog_clean",
    "residual_buffer_file_ids",
]
NANOS_PER_MILLISECOND = Decimal(1_000_000)
NANOS_PER_SECOND = Decimal(1_000_000_000)


def write_analysis_csvs(
        output_dir: Path, workload_path: Path,
        configurations: list[EngineMetrics]) -> None:
    workloads = read_workload(workload_path)
    query_rows = []
    repetition_rows = []
    summary_rows = []
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
        throughputs = []
        for repetition, makespan in enumerate(metrics.makespans_ns):
            throughput = Decimal(queries) * NANOS_PER_SECOND / Decimal(makespan)
            throughputs.append(throughput)
            repetition_rows.append({
                **common,
                "repetition": repetition,
                "queries": queries,
                "makespan_ms": _milliseconds(makespan),
                "throughput_qps": throughput,
            })
        latencies = [query.client_latency_ns for query in metrics.queries]
        admission_waits = [query.admission_wait_ns for query in metrics.queries]
        executions = [query.execution_ns for query in metrics.queries]
        makespans = list(metrics.makespans_ns)
        summary_rows.append({
            **common,
            "repetitions": metrics.run["repetitions"],
            "queries": metrics.run["queries"],
            "successful": metrics.run["successful"],
            "failed": metrics.run["failed"],
            "latency_mean_ms": _milliseconds(_mean(latencies)),
            "latency_p50_ms": _milliseconds(_median(latencies)),
            "latency_p95_ms": _milliseconds(_nearest_rank(latencies, 95)),
            "admission_wait_mean_ms": _milliseconds(_mean(admission_waits)),
            "execution_mean_ms": _milliseconds(_mean(executions)),
            "makespan_mean_ms": _milliseconds(_mean(makespans)),
            "makespan_median_ms": _milliseconds(_median(makespans)),
            "makespan_min_ms": _milliseconds(min(makespans)),
            "makespan_max_ms": _milliseconds(max(makespans)),
            "throughput_mean_qps": _mean(throughputs),
            "read_ios": metrics.run["read_ios"],
            "write_ios": metrics.run["write_ios"],
            "result_mismatches": metrics.run["result_mismatches"],
            "residual_pins": metrics.run["residual_pins"],
            "catalog_clean": metrics.run["catalog_clean"],
            "residual_buffer_file_ids": metrics.run["residual_buffer_file_ids"],
        })

    _write_csv(output_dir / "queries.csv", QUERY_COLUMNS, query_rows)
    _write_csv(output_dir / "repetitions.csv", REPETITION_COLUMNS, repetition_rows)
    _write_csv(output_dir / "summary.csv", SUMMARY_COLUMNS, summary_rows)


def _milliseconds(nanoseconds) -> Decimal:
    return Decimal(nanoseconds) / NANOS_PER_MILLISECOND


def _mean(values) -> Decimal:
    return sum((Decimal(value) for value in values), Decimal()) / len(values)


def _median(values) -> Decimal:
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return Decimal(ordered[middle])
    return (Decimal(ordered[middle - 1]) + Decimal(ordered[middle])) / 2


def _nearest_rank(values, percentile: int):
    ordered = sorted(values)
    rank = (percentile * len(ordered) + 99) // 100
    return ordered[rank - 1]


def _write_csv(path: Path, columns: list[str], rows: list[dict[str, object]]) -> None:
    with path.open("x", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=columns)
        writer.writeheader()
        writer.writerows(rows)
