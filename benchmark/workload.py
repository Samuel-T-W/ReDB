"""Read the workload manifest shared by ReDB benchmark runners."""

import csv
from pathlib import Path


WORKLOAD_HEADER = ["name", "start_range", "end_range"]


def read_workload(path: Path) -> list[list[str]]:
    with path.open(newline="", encoding="utf-8") as source:
        reader = csv.reader(source)
        try:
            header = next(reader)
        except StopIteration as failure:
            raise ValueError(f"workload CSV is empty: {path}") from failure
        if header != WORKLOAD_HEADER:
            raise ValueError("workload CSV must have header name,start_range,end_range")
        rows = list(reader)
    if not rows:
        raise ValueError(f"workload CSV has no queries: {path}")
    if any(len(row) != 3 for row in rows):
        raise ValueError("each workload row must have exactly three fields")
    return rows
