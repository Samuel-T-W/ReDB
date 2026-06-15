"""Parse and convert the integer key/value metrics emitted by the ReDB JVM.

The Java process prints a single ``REDB_METRICS k=v k=v ...`` line to stderr
when run with ``--metrics``. These helpers turn that line into typed values.
"""

import re
from typing import TypeAlias


EngineMetrics: TypeAlias = dict[str, int]

METRICS_PATTERN = re.compile(r"REDB_METRICS (.+)")


def parse_engine_metrics(stderr: str) -> EngineMetrics | None:
    """Parse the integer key/value metrics emitted by the ReDB Java process."""
    match = METRICS_PATTERN.search(stderr)
    if not match:
        return None
    metrics = {}
    try:
        for item in match.group(1).split():
            key, value = item.split("=", 1)
            metrics[key] = int(value)
    except ValueError:
        return None
    return metrics


def metric_value(metrics: EngineMetrics | None, key: str) -> int | None:
    """Safely retrieve an engine metric when parsing succeeded."""
    return metrics.get(key) if metrics else None


def nanos_to_millis(metrics: EngineMetrics | None, key: str) -> float | None:
    """Convert a nonnegative nanosecond engine metric to milliseconds."""
    value = metric_value(metrics, key)
    return value / 1_000_000 if value is not None and value >= 0 else None
