#!/usr/bin/env python3
"""Download IMDb's public datasets and build ReDB's benchmark CSVs."""

import argparse
import csv
import gzip
import os
import shutil
import urllib.request
from dataclasses import dataclass
from pathlib import Path


BASE_URL = "https://datasets.imdbws.com"
REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE_DIR = REPO_ROOT / "data" / "imdb-source"
DEFAULT_OUTPUT_DIR = REPO_ROOT / "data" / "imdb-benchmark"
NULL = r"\N"
OUTPUT_LIMITS = {
    "movieId": 10,
    "personId": 10,
    "title": 482,
    "category": 20,
    "name": 105,
}


@dataclass(frozen=True)
class Dataset:
    source: str
    output: str
    columns: tuple[tuple[str, str], ...]


DATASETS = (
    Dataset(
        "title.basics.tsv.gz", "title.csv", (
            ("tconst", "movieId"), ("primaryTitle", "title"),
        )
    ),
    Dataset(
        "title.principals.tsv.gz", "workedon.csv", (
            ("tconst", "movieId"), ("nconst", "personId"),
            ("category", "category"),
        )
    ),
    Dataset(
        "name.basics.tsv.gz", "name.csv", (
            ("nconst", "personId"), ("primaryName", "name"),
        )
    ),
)


def download(dataset: Dataset, source_dir: Path, refresh: bool) -> Path:
    destination = source_dir / dataset.source
    if destination.exists() and not refresh:
        return destination

    temporary = destination.with_suffix(destination.suffix + ".part")
    try:
        with urllib.request.urlopen(f"{BASE_URL}/{dataset.source}") as response:
            with temporary.open("wb") as output:
                shutil.copyfileobj(response, output)
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)
    return destination


def output_value(column: str, value: str) -> str:
    if value == NULL:
        value = ""
    byte_count = len(value.encode("utf-8"))
    if byte_count > OUTPUT_LIMITS[column]:
        raise ValueError(
            f"{column} exceeds {OUTPUT_LIMITS[column]} UTF-8 bytes: {byte_count}")
    return value


def convert(dataset: Dataset, source: Path, output: Path) -> int:
    temporary = output.with_suffix(output.suffix + ".tmp")
    required = {source_name for source_name, _ in dataset.columns}
    rows = 0
    try:
        with gzip.open(source, "rt", encoding="utf-8", newline="") as input_file:
            reader = csv.DictReader(input_file, delimiter="\t", quoting=csv.QUOTE_NONE)
            missing = required.difference(reader.fieldnames or ())
            if missing:
                raise ValueError(f"{source.name} is missing columns: {sorted(missing)}")

            with temporary.open("w", encoding="utf-8", newline="") as output_file:
                writer = csv.writer(output_file, lineterminator="\n")
                writer.writerow([output_name for _, output_name in dataset.columns])
                for source_row in reader:
                    values = [source_row[name] for name, _ in dataset.columns]
                    if any("\n" in value or "\r" in value for value in values):
                        raise ValueError(f"{source.name} contains a multiline field")
                    writer.writerow([
                        output_value(output_name, value)
                        for (_, output_name), value in zip(dataset.columns, values)
                    ])
                    rows += 1
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)
    return rows


def run(source_dir: Path, output_dir: Path, refresh: bool = False) -> None:
    source_dir.mkdir(parents=True, exist_ok=True)
    output_dir.mkdir(parents=True, exist_ok=True)
    for dataset in DATASETS:
        source = download(dataset, source_dir, refresh)
        rows = convert(dataset, source, output_dir / dataset.output)
        print(f"{dataset.output}: {rows:,} rows")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path, default=DEFAULT_SOURCE_DIR)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--refresh", action="store_true")
    args = parser.parse_args()
    run(args.source_dir, args.output_dir, args.refresh)


if __name__ == "__main__":
    main()
