"""Statistics, unit conversion, and CSV/console output helpers.

Pure formatting and aggregation utilities shared by the benchmark runner.
None of these touch processes or the filesystem beyond writing a CSV.
"""

import csv
import math
import statistics
from pathlib import Path
from typing import Any, Mapping, Sequence, TypeAlias

from memory_monitor import GroupMetrics


Numeric: TypeAlias = int | float
ResultRow: TypeAlias = dict[str, Any]
SummaryRow: TypeAlias = dict[str, Any]


def percentile(values: Sequence[float], fraction: float) -> float:
    """Calculate a nearest-rank percentile from a nonempty sequence."""
    ordered = sorted(values)
    index = max(0, math.ceil(fraction * len(ordered)) - 1)
    return ordered[index]


def present_values(rows: Sequence[ResultRow], key: str) -> list[Numeric]:
    """Extract the non-None values for one metric from result rows."""
    return [row[key] for row in rows if row[key] is not None]


def bytes_to_mb(value: Numeric | None) -> float | None:
    """Convert bytes to mebibytes, preserving unavailable values as None."""
    return value / (1024 * 1024) if value is not None else None


def display_metric(value: Numeric | None) -> str:
    """Format a numeric console metric to three decimals or show n/a."""
    return f"{value:.3f}" if value is not None else "n/a"


def write_csv(
    path: Path,
    rows: Sequence[Mapping[str, Any]],
    fieldnames: Sequence[str],
) -> None:
    """Write dictionaries to a UTF-8 CSV using the requested column order."""
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def summarize(
    rows: Sequence[ResultRow],
    concurrency: int,
    makespan_seconds: float,
    group_metrics: GroupMetrics,
) -> SummaryRow:
    """Aggregate raw query rows into one summary for a concurrency level."""
    successful = [row for row in rows if row["status"] == "ok"]
    latencies = [row["query_elapsed_ms"] for row in successful]
    peak_rss_values = present_values(successful, "peak_rss_bytes")
    heap_peak_values = present_values(successful, "jvm_heap_pool_peak_sum_bytes")
    cpu_values = present_values(successful, "process_cpu_ms")
    minor_fault_values = present_values(successful, "minor_faults")
    major_fault_values = present_values(successful, "major_faults")
    return {
        "concurrency": concurrency,
        "queries": len(rows),
        "successful": len(successful),
        "failed": len(rows) - len(successful),
        "makespan_seconds": makespan_seconds,
        "throughput_qps": len(successful) / makespan_seconds if makespan_seconds else 0,
        "latency_mean_ms": statistics.fmean(latencies) if latencies else None,
        "latency_p50_ms": statistics.median(latencies) if latencies else None,
        "latency_p95_ms": percentile(latencies, 0.95) if latencies else None,
        "latency_min_ms": min(latencies) if latencies else None,
        "latency_max_ms": max(latencies) if latencies else None,
        "worker_peak_rss_mean_mb": bytes_to_mb(statistics.fmean(peak_rss_values))
        if peak_rss_values
        else None,
        "worker_peak_rss_max_mb": bytes_to_mb(max(peak_rss_values))
        if peak_rss_values
        else None,
        "aggregate_peak_rss_mb": bytes_to_mb(
            group_metrics["aggregate_peak_rss_bytes"]
        ),
        "jvm_heap_peak_mean_mb": bytes_to_mb(statistics.fmean(heap_peak_values))
        if heap_peak_values
        else None,
        "jvm_heap_peak_max_mb": bytes_to_mb(max(heap_peak_values))
        if heap_peak_values
        else None,
        "process_cpu_total_ms": sum(cpu_values) if cpu_values else None,
        "cpu_utilization_cores": (sum(cpu_values) / 1000) / makespan_seconds
        if cpu_values and makespan_seconds
        else None,
        "minor_faults_total": sum(minor_fault_values) if minor_fault_values else None,
        "major_faults_total": sum(major_fault_values) if major_fault_values else None,
        "memory_pid_samples_attempted": group_metrics[
            "memory_pid_samples_attempted"
        ],
        "memory_pid_samples_successful": group_metrics[
            "memory_pid_samples_successful"
        ],
        "memory_pid_samples_failed": group_metrics["memory_pid_samples_failed"],
    }
