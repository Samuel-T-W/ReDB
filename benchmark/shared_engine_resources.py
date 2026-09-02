"""Run one benchmark JVM while collecting existing OS resource metrics."""

import json
from pathlib import Path
import subprocess
from typing import Sequence

from memory_monitor import ProcessMemoryMonitor, WorkerMetrics


SAMPLE_INTERVAL_MS = 100


def run_monitored_java(command: Sequence[str], cwd: Path, config_dir: Path) -> None:
    monitor = ProcessMemoryMonitor(SAMPLE_INTERVAL_MS)
    process = None
    process_metrics: WorkerMetrics = {
        "peak_rss_bytes": None,
        "minor_faults": None,
        "major_faults": None,
    }
    monitor.start()
    try:
        with (config_dir / "engine.stderr.log").open("x", encoding="utf-8") as stderr:
            process = subprocess.Popen(
                command,
                cwd=cwd,
                stdout=subprocess.DEVNULL,
                stderr=stderr)
            monitor.register(process.pid)
            returncode = process.wait()
            process_metrics = monitor.unregister(process.pid)
    finally:
        if process is not None:
            monitor.drop(process.pid)
        monitor.stop()
        _write_resources(config_dir, process_metrics, monitor.group_metrics())

    if returncode != 0:
        raise subprocess.CalledProcessError(returncode, command)


def _write_resources(config_dir: Path, process_metrics, monitor_metrics) -> None:
    artifact = {
        "sample_interval_ms": SAMPLE_INTERVAL_MS,
        "process": process_metrics,
        "monitor": monitor_metrics,
    }
    with (config_dir / "resources.json").open("x", encoding="utf-8") as output:
        json.dump(artifact, output, indent=2, sort_keys=True)
        output.write("\n")
