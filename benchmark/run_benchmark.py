#!/usr/bin/env python3

import argparse
import csv
import datetime as dt
import json
import os
import platform
import shutil
import subprocess
import sys
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Sequence, TypedDict, cast

from memory_monitor import GroupMetrics, ProcessMemoryMonitor
from metrics import metric_value, nanos_to_millis, parse_engine_metrics
from reporting import (
    ResultRow,
    append_csv,
    display_metric,
    summarize,
)


DATABASE_FILES = ("movies.db", "workedon.db", "people.db")


class Workload(TypedDict):
    """One named movie-title range read from the workload CSV."""

    name: str
    start_range: str
    end_range: str


class BenchmarkArgs(argparse.Namespace):
    """Validated command-line settings used throughout the benchmark."""

    concurrency: list[int]
    repetitions: int
    warmups: int
    buffer_size: int
    workload: Path
    output_dir: Path
    index: bool
    skip_build: bool
    java_xmx: str | None
    memory_sample_ms: int
    run_label: str | None


def parse_args() -> BenchmarkArgs:
    """Parse command-line options and reject invalid benchmark settings."""
    parser = argparse.ArgumentParser(
        description="Run isolated ReDB query processes and record latency/throughput."
    )
    parser.add_argument(
        "--concurrency",
        default="1,2,4",
        help="Comma-separated concurrent worker counts (default: 1,2,4).",
    )
    parser.add_argument(
        "--repetitions",
        type=int,
        default=3,
        help="Measured repetitions of every workload at each concurrency (default: 3).",
    )
    parser.add_argument(
        "--warmups",
        type=int,
        default=1,
        help="Unmeasured warmup repetitions before each concurrency level (default: 1).",
    )
    parser.add_argument(
        "--buffer-size",
        type=int,
        default=20,
        help="Buffer manager frame count for every query (default: 20).",
    )
    parser.add_argument(
        "--workload",
        type=Path,
        default=Path(__file__).with_name("workload.csv"),
        help="CSV containing name,start_range,end_range.",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path(__file__).with_name("results"),
        help="Directory for raw CSV, summary CSV, and metadata JSON.",
    )
    parser.add_argument("--index", action="store_true", help="Use the title index query path.")
    parser.add_argument(
        "--skip-build",
        action="store_true",
        help="Do not run Maven compile before benchmarking.",
    )
    parser.add_argument(
        "--java-xmx",
        help="Optional JVM maximum heap, for example 1g or 1500m.",
    )
    parser.add_argument(
        "--memory-sample-ms",
        type=int,
        default=50,
        help="OS process-memory sampling interval in milliseconds (default: 50).",
    )
    parser.add_argument(
        "--run-label",
        help="Optional human-readable label attached to output rows.",
    )
    args = parser.parse_args()

    try:
        args.concurrency = [int(value) for value in args.concurrency.split(",")]
    except ValueError as exc:
        parser.error(f"--concurrency must contain integers: {exc}")
    if not args.concurrency or any(value < 1 for value in args.concurrency):
        parser.error("--concurrency values must be positive")
    if args.repetitions < 1:
        parser.error("--repetitions must be positive")
    if args.warmups < 0:
        parser.error("--warmups cannot be negative")
    if args.buffer_size < 3:
        parser.error("--buffer-size must be at least 3")
    if args.memory_sample_ms < 1:
        parser.error("--memory-sample-ms must be positive")
    return cast(BenchmarkArgs, args)


def load_workloads(path: Path) -> list[Workload]:
    """Load and validate named title-range query scenarios from a CSV file."""
    with path.open(newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        required = {"name", "start_range", "end_range"}
        if set(reader.fieldnames or ()) != required:
            raise ValueError(
                f"{path} must have exactly these columns: name,start_range,end_range"
            )
        workloads = cast(list[Workload], list(reader))
    if not workloads:
        raise ValueError(f"{path} contains no workloads")
    for workload in workloads:
        if len(workload["start_range"]) > 30 or len(workload["end_range"]) > 30:
            raise ValueError(f"Range exceeds 30 characters in workload {workload['name']}")
    return workloads


def run_checked(command: Sequence[str], cwd: Path) -> None:
    """Run a command in cwd and raise an error if it exits unsuccessfully."""
    completed = subprocess.run(command, cwd=cwd, text=True)
    if completed.returncode != 0:
        raise RuntimeError(f"Command failed with exit code {completed.returncode}: {' '.join(command)}")


def git_value(root: Path, *args: str) -> str | None:
    """Run a Git command and return its trimmed output, or None on failure."""
    completed = subprocess.run(
        ["git", *args],
        cwd=root,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    )
    return completed.stdout.strip() if completed.returncode == 0 else None


def java_version() -> str:
    """Return the first line printed by `java -version` for run metadata."""
    completed = subprocess.run(
        ["java", "-version"],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    return (completed.stderr or completed.stdout).splitlines()[0]


def physical_memory_bytes() -> int | None:
    """Return the machine's physical memory in bytes when the OS exposes it."""
    try:
        return os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES")
    except (ValueError, OSError, AttributeError):
        return None


def create_worker_dir(root: Path, parent: Path) -> Path:
    """Create an isolated worker directory with links to shared database files."""
    worker_dir = Path(tempfile.mkdtemp(prefix="redb-worker-", dir=parent))
    for file_name in DATABASE_FILES:
        os.symlink(root / file_name, worker_dir / file_name)
    return worker_dir


def run_query(
    root: Path,
    work_parent: Path,
    workload: Workload,
    concurrency: int,
    repetition: int,
    args: BenchmarkArgs,
    memory_monitor: ProcessMemoryMonitor,
    warmup: bool = False,
) -> ResultRow:
    """Run one workload in its own JVM and return a raw benchmark result row."""
    worker_dir = create_worker_dir(root, work_parent)
    java_command = ["java"]
    if args.java_xmx:
        java_command.append(f"-Xmx{args.java_xmx}")
    java_command.extend(
        [
            "-cp",
            str(root / "target" / "classes"),
            "Main",
            "run_query",
            workload["start_range"],
            workload["end_range"],
            str(args.buffer_size),
            "--metrics",
        ]
    )
    if args.index:
        java_command.append("--index")

    started_at = dt.datetime.now(dt.timezone.utc).isoformat()
    wall_start = time.perf_counter_ns()
    process = None
    try:
        process = subprocess.Popen(
            java_command,
            cwd=worker_dir,
            text=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
        )
        memory_monitor.register(process.pid)
        _stdout, stderr = process.communicate()
        wall_ns = time.perf_counter_ns() - wall_start
        process_memory = memory_monitor.unregister(process.pid)
        metrics = parse_engine_metrics(stderr)
        status = "ok" if process.returncode == 0 and metrics else "failed"
        error = "" if status == "ok" else stderr.strip().replace("\n", " | ")
        return {
            "started_at_utc": started_at,
            "concurrency": concurrency,
            "repetition": repetition,
            "workload": workload["name"],
            "start_range": workload["start_range"],
            "end_range": workload["end_range"],
            "buffer_size": args.buffer_size,
            "use_index": args.index,
            "warmup": warmup,
            "status": status,
            "exit_code": process.returncode,
            "query_elapsed_ms": nanos_to_millis(metrics, "elapsed_ns"),
            "process_wall_ms": wall_ns / 1_000_000,
            "process_cpu_ms": nanos_to_millis(metrics, "cpu_ns"),
            "result_count": metrics.get("result_count") if metrics else None,
            "jvm_heap_used_end_bytes": metric_value(metrics, "heap_used_bytes"),
            "jvm_heap_committed_end_bytes": metric_value(
                metrics, "heap_committed_bytes"
            ),
            "jvm_heap_pool_peak_sum_bytes": metric_value(
                metrics, "heap_pool_peak_sum_bytes"
            ),
            **process_memory,
            "error": error,
        }
    finally:
        if process is not None:
            memory_monitor.drop(process.pid)
        shutil.rmtree(worker_dir, ignore_errors=True)


def run_group(
    root: Path,
    work_parent: Path,
    workloads: Sequence[Workload],
    concurrency: int,
    repetitions: int,
    args: BenchmarkArgs,
    warmup: bool,
) -> tuple[list[ResultRow], float, GroupMetrics]:
    """Run every workload/repetition pair with a fixed worker concurrency."""
    tasks = [
        (workload, repetition)
        for repetition in range(1, repetitions + 1)
        for workload in workloads
    ]
    group_start = time.perf_counter_ns()
    memory_monitor = ProcessMemoryMonitor(args.memory_sample_ms)
    memory_monitor.start()
    rows = []
    try:
        with ThreadPoolExecutor(max_workers=concurrency) as executor:
            futures = [
                executor.submit(
                    run_query,
                    root,
                    work_parent,
                    workload,
                    concurrency,
                    repetition,
                    args,
                    memory_monitor,
                    warmup,
                )
                for workload, repetition in tasks
            ]
            for future in as_completed(futures):
                rows.append(future.result())
    finally:
        memory_monitor.stop()
    makespan_seconds = (time.perf_counter_ns() - group_start) / 1_000_000_000
    group_metrics = memory_monitor.group_metrics()
    return rows, makespan_seconds, group_metrics


def main() -> int:
    """Validate prerequisites, run all benchmark groups, and write results."""
    args = parse_args()
    root = Path(__file__).resolve().parent.parent

    # Queries require the binary databases produced by the preprocessing step.
    missing = [file_name for file_name in DATABASE_FILES if not (root / file_name).is_file()]
    if missing:
        print(
            f"Missing preprocessed database files: {', '.join(missing)}\n"
            "Run ./run.sh pre_process before starting the benchmark.",
            file=sys.stderr,
        )
        return 2

    try:
        workloads = load_workloads(args.workload)
    except (OSError, ValueError) as exc:
        print(f"Invalid workload: {exc}", file=sys.stderr)
        return 2

    # Compile once before timing unless the caller confirms it is already built.
    if not args.skip_build:
        print("Compiling ReDB...")
        run_checked(["mvn", "-q", "-DskipTests", "compile"], root)

    args.output_dir.mkdir(parents=True, exist_ok=True)
    work_parent = args.output_dir / ".workers"
    work_parent.mkdir(exist_ok=True)
    run_stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    run_created_at = dt.datetime.now(dt.timezone.utc).isoformat()
    run_id = run_stamp
    aggregate_raw_path = args.output_dir / "all_raw.csv"
    aggregate_summary_path = args.output_dir / "all_summary.csv"
    aggregate_metadata_path = args.output_dir / "all_metadata.jsonl"

    run_context = {
        "run_id": run_id,
        "run_label": args.run_label or "",
        "run_created_at_utc": run_created_at,
    }

    summary_context = {
        **run_context,
        "buffer_size": args.buffer_size,
        "use_index": args.index,
        "repetitions": args.repetitions,
        "warmups": args.warmups,
        "java_xmx": args.java_xmx or "",
        "workload_file": str(args.workload),
    }

    # Each concurrency level gets unrecorded warmups followed by measured runs.
    all_rows = []
    summaries = []
    for concurrency in args.concurrency:
        if args.warmups:
            print(f"Warming up concurrency={concurrency}...")
            run_group(
                root,
                work_parent,
                workloads,
                concurrency,
                args.warmups,
                args,
                True,
            )
        print(f"Measuring concurrency={concurrency}...")
        rows, makespan_seconds, group_metrics = run_group(
            root,
            work_parent,
            workloads,
            concurrency,
            args.repetitions,
            args,
            False,
        )
        for row in rows:
            row.update(run_context)
        all_rows.extend(rows)
        summary = summarize(rows, concurrency, makespan_seconds, group_metrics)
        summary.update(summary_context)
        summaries.append(summary)

    raw_fields = [
        "run_id",
        "run_label",
        "run_created_at_utc",
        "started_at_utc",
        "concurrency",
        "repetition",
        "workload",
        "start_range",
        "end_range",
        "buffer_size",
        "use_index",
        "warmup",
        "status",
        "exit_code",
        "query_elapsed_ms",
        "process_wall_ms",
        "process_cpu_ms",
        "result_count",
        "jvm_heap_used_end_bytes",
        "jvm_heap_committed_end_bytes",
        "jvm_heap_pool_peak_sum_bytes",
        "peak_rss_bytes",
        "minor_faults",
        "major_faults",
        "error",
    ]
    summary_fields = list(summaries[0].keys())

    # Metadata makes benchmark results reproducible and easier to compare.
    metadata = {
        "run_id": run_id,
        "run_label": args.run_label or "",
        "created_at_utc": run_created_at,
        "git_commit": git_value(root, "rev-parse", "HEAD"),
        "git_dirty": bool(git_value(root, "status", "--porcelain")),
        "platform": platform.platform(),
        "hostname": platform.node(),
        "logical_cpu_count": os.cpu_count(),
        "physical_memory_bytes": physical_memory_bytes(),
        "java_version": java_version(),
        "config": {
            "concurrency": args.concurrency,
            "repetitions": args.repetitions,
            "warmups": args.warmups,
            "buffer_size": args.buffer_size,
            "workload": str(args.workload),
            "use_index": args.index,
            "java_xmx": args.java_xmx,
            "memory_sample_ms": args.memory_sample_ms,
        },
        "summaries": summaries,
    }

    append_csv(aggregate_raw_path, all_rows, raw_fields)
    append_csv(aggregate_summary_path, summaries, summary_fields)
    with aggregate_metadata_path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(metadata, sort_keys=True) + "\n")

    # Print a compact human-readable view; detailed data remains in the files.
    print()
    print(
        f"{'workers':>7} {'ok':>5} {'fail':>5} {'qps':>10} "
        f"{'p50_ms':>12} {'p95_ms':>12} {'peak_rss_mb':>13}"
    )
    for summary in summaries:
        print(
            f"{summary['concurrency']:>7} {summary['successful']:>5} "
            f"{summary['failed']:>5} {summary['throughput_qps']:>10.3f} "
            f"{display_metric(summary['latency_p50_ms']):>12} "
            f"{display_metric(summary['latency_p95_ms']):>12} "
            f"{display_metric(summary['aggregate_peak_rss_mb']):>13}"
        )
    print(f"\nAggregate raw results: {aggregate_raw_path}")
    print(f"Aggregate summary:     {aggregate_summary_path}")
    print(f"Aggregate metadata:    {aggregate_metadata_path}")
    return 1 if any(summary["failed"] for summary in summaries) else 0


if __name__ == "__main__":
    sys.exit(main())
