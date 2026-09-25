"""Read the shared-engine benchmark line protocol."""

from dataclasses import dataclass
from pathlib import Path


PROTOCOL_HEADER = "REDB_ENGINE_PROTOCOL version=1"
QUERY_PREFIX = "REDB_ENGINE_QUERY"
RUN_PREFIX = "REDB_ENGINE_RUN"

QUERY_FIELDS = {
    "task_id",
    "workload_index",
    "repetition",
    "status_code",
    "result_count",
    "client_latency_ns",
    "admission_wait_ns",
    "execution_ns",
}
RUN_FIELDS = {
    "buffer_size",
    "max_concurrent",
    "clients",
    "frame_budget",
    "use_index",
    "workloads",
    "warmups",
    "repetitions",
    "queries",
    "successful",
    "failed",
    "makespans_ns",
    "read_ios",
    "write_ios",
    "result_mismatches",
    "residual_pins",
    "catalog_clean",
    "residual_buffer_file_ids",
}


class MetricsParseError(ValueError):
    """The metrics file does not conform to the version-1 line protocol."""


@dataclass(frozen=True)
class QueryMetric:
    task_id: int
    workload_index: int
    repetition: int
    status_code: int
    result_count: int
    client_latency_ns: int
    admission_wait_ns: int
    execution_ns: int


@dataclass(frozen=True)
class EngineMetrics:
    queries: tuple[QueryMetric, ...]
    makespans_ns: tuple[int, ...]
    run: dict[str, int]


def parse_metrics_file(path: Path) -> EngineMetrics:
    """Parse one version-1 metrics file."""
    return parse_metrics(path.read_text(encoding="utf-8"))


def parse_metrics(text: str) -> EngineMetrics:
    lines = text.splitlines()
    if not lines or lines[0] != PROTOCOL_HEADER:
        raise MetricsParseError(f"expected first line: {PROTOCOL_HEADER}")

    records = [line for line in lines[1:] if line.strip()]
    if not records or not records[-1].startswith(RUN_PREFIX + " "):
        raise MetricsParseError("expected one final REDB_ENGINE_RUN record")

    query_values: list[dict[str, int]] = []
    run_values: dict[str, int] | None = None
    makespans: tuple[int, ...] = ()
    for index, record in enumerate(records, start=2):
        if record.startswith(QUERY_PREFIX + " "):
            if run_values is not None:
                raise MetricsParseError(f"query record after run record on line {index}")
            query_values.append(_parse_integer_fields(record, QUERY_PREFIX, QUERY_FIELDS, index))
        elif record.startswith(RUN_PREFIX + " "):
            if run_values is not None:
                raise MetricsParseError("duplicate REDB_ENGINE_RUN record")
            raw = _parse_fields(record, RUN_PREFIX, RUN_FIELDS, index)
            makespans = _parse_integer_list(raw.pop("makespans_ns"), "makespans_ns")
            run_values = {key: _integer(key, value) for key, value in raw.items()}
        else:
            raise MetricsParseError(f"unknown record on line {index}: {record}")

    if run_values is None:
        raise MetricsParseError("missing REDB_ENGINE_RUN record")
    queries = tuple(QueryMetric(**values) for values in query_values)
    return EngineMetrics(queries, makespans, run_values)


def _parse_integer_fields(
        record: str, prefix: str, required: set[str], line_number: int) -> dict[str, int]:
    values = _parse_fields(record, prefix, required, line_number)
    return {key: _integer(key, value) for key, value in values.items()}


def _parse_fields(
        record: str, prefix: str, required: set[str], line_number: int) -> dict[str, str]:
    values: dict[str, str] = {}
    for token in record[len(prefix):].strip().split():
        if "=" not in token:
            raise MetricsParseError(f"malformed field on line {line_number}: {token}")
        key, value = token.split("=", 1)
        if not key or not value:
            raise MetricsParseError(f"malformed field on line {line_number}: {token}")
        if key in values:
            raise MetricsParseError(f"duplicate field on line {line_number}: {key}")
        values[key] = value
    missing = required - values.keys()
    extra = values.keys() - required
    if missing or extra:
        raise MetricsParseError(
            f"invalid fields on line {line_number}; missing={sorted(missing)}, extra={sorted(extra)}")
    return values


def _integer(name: str, value: str) -> int:
    try:
        return int(value)
    except ValueError as failure:
        raise MetricsParseError(f"{name} must be an integer: {value}") from failure


def _parse_integer_list(value: str, name: str) -> tuple[int, ...]:
    if not value:
        raise MetricsParseError(f"{name} must not be empty")
    return tuple(_integer(name, item) for item in value.split(","))
