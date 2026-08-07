"""Run the fixed shared-engine benchmark matrix without aggregating results."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
from pathlib import Path
import shutil
import subprocess
from typing import Sequence

from shared_engine_metrics import parse_metrics_file
from shared_engine_metadata import write_run_metadata
from shared_engine_results import write_analysis_csvs
from workload import read_workload


MATRIX = ((1, 1, 20), (2, 2, 40), (4, 4, 80))
WARMUPS = 1
REPETITIONS = 5


def find_repo_root(start: Path) -> Path:
    """Find the nearest ReDB root at or above start."""
    location = start.resolve()
    if location.is_file():
        location = location.parent
    for candidate in (location, *location.parents):
        if (candidate / "pom.xml").is_file() and (
                candidate / "src" / "EngineBenchmark.java").is_file():
            return candidate
    raise FileNotFoundError(f"could not find ReDB repository root from: {start}")


REPO_ROOT = find_repo_root(Path(__file__))
DEFAULT_WORKLOAD = REPO_ROOT / "benchmark" / "concurrency_workload.csv"
DEFAULT_RESULTS_ROOT = REPO_ROOT / "benchmark" / "results" / "shared-engine"


def default_output_dir(now: datetime | None = None) -> Path:
    timestamp = (now or datetime.now(timezone.utc)).strftime("%Y%m%dT%H%M%S.%fZ")
    return DEFAULT_RESULTS_ROOT / timestamp


def run_benchmark(
        workload: Path, output_dir: Path, skip_build: bool,
        repo_root: Path = REPO_ROOT) -> list[Path]:
    repo_root = repo_root.resolve()
    workload = workload.resolve()
    output_dir = output_dir.resolve()
    read_workload(workload)

    output_dir.mkdir(parents=True, exist_ok=False)
    print(f"Run directory: {output_dir}")
    run_workload = output_dir / "workload.csv"
    shutil.copyfile(workload, run_workload)

    if not skip_build:
        subprocess.run(
            ["mvn", "-q", "-DskipTests", "compile"],
            cwd=repo_root,
            check=True)

    successful = []
    configuration_results = []
    for max_concurrent, clients, buffer_size in MATRIX:
        config_dir = output_dir / f"concurrency-{max_concurrent}-buffer-{buffer_size}"
        config_dir.mkdir()
        metrics = config_dir / "engine.metrics"
        stderr_path = config_dir / "engine.stderr.log"
        command = [
            "java", "-cp", "target/classes", "EngineBenchmark",
            "--workload", str(run_workload),
            "--buffer-size", str(buffer_size),
            "--max-concurrent", str(max_concurrent),
            "--clients", str(clients),
            "--repetitions", str(REPETITIONS),
            "--warmups", str(WARMUPS),
            "--output-dir", str(config_dir),
            "--result-file", str(metrics),
        ]
        with stderr_path.open("x", encoding="utf-8") as stderr:
            subprocess.run(
                command,
                cwd=repo_root,
                stdout=subprocess.DEVNULL,
                stderr=stderr,
                check=True)
        parsed = parse_metrics_file(metrics)
        configuration_results.append(parsed)
        successful.append(config_dir)
        print(f"Successful config: {config_dir}")
    write_analysis_csvs(output_dir, run_workload, configuration_results)
    write_run_metadata(output_dir, repo_root, skip_build)
    return successful


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workload", type=Path, default=DEFAULT_WORKLOAD)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--skip-build", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    output_dir = args.output_dir or default_output_dir()
    run_benchmark(args.workload, output_dir, args.skip_build)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
