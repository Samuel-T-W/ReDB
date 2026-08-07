"""Persist provenance and environment metadata for a shared-engine run."""

from __future__ import annotations

from datetime import datetime, timezone
import json
import os
from pathlib import Path
import platform
import subprocess


def write_run_metadata(
        output_dir: Path, repo_root: Path, build_skipped: bool,
        now: datetime | None = None) -> None:
    metadata = {
        "schema_version": 1,
        "created_at_utc": _utc_text(now or datetime.now(timezone.utc)),
        "git_commit": _git_value(repo_root, "rev-parse", "HEAD"),
        "git_dirty": bool(_git_value(repo_root, "status", "--porcelain")),
        "platform": platform.platform(),
        "hostname": platform.node(),
        "logical_cpu_count": os.cpu_count(),
        "physical_memory_bytes": _physical_memory_bytes(),
        "java_version": _java_version(),
        "build_skipped": build_skipped,
    }
    with (output_dir / "metadata.json").open("x", encoding="utf-8") as output:
        json.dump(metadata, output, indent=2, sort_keys=True)
        output.write("\n")


def _utc_text(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _git_value(root: Path, *args: str) -> str | None:
    completed = subprocess.run(
        ["git", *args],
        cwd=root,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL)
    return completed.stdout.strip() if completed.returncode == 0 else None


def _java_version() -> str:
    completed = subprocess.run(
        ["java", "-version"],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=True)
    return (completed.stderr or completed.stdout).splitlines()[0]


def _physical_memory_bytes() -> int | None:
    try:
        return os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES")
    except (AttributeError, OSError, ValueError):
        return None
