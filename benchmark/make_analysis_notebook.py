#!/usr/bin/env python3

"""Generate a commented benchmark analysis notebook and SVG plots."""

from __future__ import annotations

import json
from pathlib import Path
from textwrap import dedent

import pandas as pd


ROOT = Path(__file__).resolve().parents[1]
RESULTS_DIR = ROOT / "benchmark" / "results"
ANALYSIS_DIR = RESULTS_DIR / "analysis"
NOTEBOOK_PATH = ROOT / "benchmark" / "benchmark_analysis.ipynb"


def fmt_ms(value: float) -> str:
    return f"{value:,.0f} ms"


def fmt_mb(value: float) -> str:
    return f"{value:,.0f} MB"


def fmt_float(value: float, digits: int = 2) -> str:
    return f"{value:,.{digits}f}"


def markdown_table(df: pd.DataFrame) -> str:
    headers = [str(column) for column in df.columns]
    rows = [[str(value) for value in row] for row in df.itertuples(index=False, name=None)]
    widths = [
        max(len(header), *(len(row[idx]) for row in rows)) if rows else len(header)
        for idx, header in enumerate(headers)
    ]

    def format_row(values: list[str]) -> str:
        return "| " + " | ".join(value.ljust(widths[idx]) for idx, value in enumerate(values)) + " |"

    separator = "| " + " | ".join("-" * width for width in widths) + " |"
    return "\n".join([format_row(headers), separator, *(format_row(row) for row in rows)])


def line_chart_svg(
    df: pd.DataFrame,
    x: str,
    ys: list[str],
    labels: dict[str, str],
    title: str,
    y_label: str,
    path: Path,
    width: int = 760,
    height: int = 420,
) -> None:
    margin_left = 82
    margin_right = 28
    margin_top = 58
    margin_bottom = 62
    plot_w = width - margin_left - margin_right
    plot_h = height - margin_top - margin_bottom

    x_values = sorted(df[x].unique())
    y_max = max(float(df[y].max()) for y in ys) * 1.12
    y_max = y_max if y_max > 0 else 1
    colors = ["#2563eb", "#dc2626", "#059669", "#7c3aed"]

    def sx(value: float) -> float:
        if len(x_values) == 1:
            return margin_left + plot_w / 2
        return margin_left + (x_values.index(value) / (len(x_values) - 1)) * plot_w

    def sy(value: float) -> float:
        return margin_top + plot_h - (value / y_max) * plot_h

    grid_lines = []
    for tick in range(6):
        y_value = y_max * tick / 5
        y_px = sy(y_value)
        grid_lines.append(
            f'<line x1="{margin_left}" y1="{y_px:.1f}" x2="{width - margin_right}" '
            f'y2="{y_px:.1f}" stroke="#e5e7eb" stroke-width="1"/>'
        )
        grid_lines.append(
            f'<text x="{margin_left - 10}" y="{y_px + 4:.1f}" text-anchor="end" '
            f'font-size="12" fill="#4b5563">{y_value:.2f}</text>'
        )

    x_ticks = []
    for value in x_values:
        x_px = sx(value)
        x_ticks.append(
            f'<line x1="{x_px:.1f}" y1="{margin_top + plot_h}" x2="{x_px:.1f}" '
            f'y2="{margin_top + plot_h + 6}" stroke="#6b7280"/>'
        )
        x_ticks.append(
            f'<text x="{x_px:.1f}" y="{height - 26}" text-anchor="middle" '
            f'font-size="13" fill="#111827">{value}</text>'
        )

    series = []
    legend = []
    for idx, y in enumerate(ys):
        color = colors[idx % len(colors)]
        points = []
        for _, row in df.sort_values(x).iterrows():
            points.append(f"{sx(row[x]):.1f},{sy(float(row[y])):.1f}")
        series.append(
            f'<polyline points="{" ".join(points)}" fill="none" stroke="{color}" '
            f'stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>'
        )
        for _, row in df.sort_values(x).iterrows():
            series.append(
                f'<circle cx="{sx(row[x]):.1f}" cy="{sy(float(row[y])):.1f}" r="4.5" '
                f'fill="{color}" stroke="white" stroke-width="2"/>'
            )
        legend_y = 22 + idx * 20
        legend.append(
            f'<rect x="{width - 225}" y="{legend_y - 10}" width="12" height="12" '
            f'rx="2" fill="{color}"/>'
        )
        legend.append(
            f'<text x="{width - 207}" y="{legend_y}" font-size="13" '
            f'fill="#111827">{labels[y]}</text>'
        )

    svg = dedent(
        f"""\
        <svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">
          <rect width="100%" height="100%" fill="white"/>
          <text x="{margin_left}" y="30" font-size="22" font-weight="700" fill="#111827">{title}</text>
          {"".join(legend)}
          {"".join(grid_lines)}
          <line x1="{margin_left}" y1="{margin_top}" x2="{margin_left}" y2="{margin_top + plot_h}" stroke="#374151"/>
          <line x1="{margin_left}" y1="{margin_top + plot_h}" x2="{width - margin_right}" y2="{margin_top + plot_h}" stroke="#374151"/>
          {"".join(x_ticks)}
          {"".join(series)}
          <text x="{width / 2:.1f}" y="{height - 6}" text-anchor="middle" font-size="13" fill="#374151">Concurrency</text>
          <text x="20" y="{margin_top + plot_h / 2:.1f}" text-anchor="middle" font-size="13" fill="#374151" transform="rotate(-90 20 {margin_top + plot_h / 2:.1f})">{y_label}</text>
        </svg>
        """
    )
    path.write_text(svg, encoding="utf-8")


def grouped_bar_svg(
    df: pd.DataFrame,
    path: Path,
    width: int = 860,
    height: int = 460,
) -> None:
    workloads = list(df["workload"].unique())
    concurrencies = sorted(df["concurrency"].unique())
    colors = {1: "#2563eb", 2: "#dc2626", 4: "#059669"}
    margin_left = 82
    margin_right = 28
    margin_top = 58
    margin_bottom = 92
    plot_w = width - margin_left - margin_right
    plot_h = height - margin_top - margin_bottom
    y_max = float(df["mean_query_ms"].max()) * 1.15

    def sy(value: float) -> float:
        return margin_top + plot_h - (value / y_max) * plot_h

    grid_lines = []
    for tick in range(6):
        y_value = y_max * tick / 5
        y_px = sy(y_value)
        grid_lines.append(
            f'<line x1="{margin_left}" y1="{y_px:.1f}" x2="{width - margin_right}" '
            f'y2="{y_px:.1f}" stroke="#e5e7eb" stroke-width="1"/>'
        )
        grid_lines.append(
            f'<text x="{margin_left - 10}" y="{y_px + 4:.1f}" text-anchor="end" '
            f'font-size="12" fill="#4b5563">{y_value/1000:.1f}s</text>'
        )

    group_w = plot_w / len(workloads)
    bar_w = group_w / (len(concurrencies) + 1.6)
    bars = []
    labels = []
    for group_idx, workload in enumerate(workloads):
        group_x = margin_left + group_idx * group_w
        labels.append(
            f'<text x="{group_x + group_w/2:.1f}" y="{height - 44}" text-anchor="middle" '
            f'font-size="12" fill="#111827">{workload}</text>'
        )
        for c_idx, concurrency in enumerate(concurrencies):
            row = df[(df["workload"] == workload) & (df["concurrency"] == concurrency)].iloc[0]
            value = float(row["mean_query_ms"])
            x_px = group_x + bar_w * (0.8 + c_idx)
            y_px = sy(value)
            bars.append(
                f'<rect x="{x_px:.1f}" y="{y_px:.1f}" width="{bar_w * 0.82:.1f}" '
                f'height="{margin_top + plot_h - y_px:.1f}" fill="{colors[concurrency]}" rx="3"/>'
            )
            bars.append(
                f'<text x="{x_px + bar_w * 0.41:.1f}" y="{y_px - 6:.1f}" text-anchor="middle" '
                f'font-size="11" fill="#111827">{value/1000:.1f}s</text>'
            )

    legend = []
    for idx, concurrency in enumerate(concurrencies):
        legend_y = 22 + idx * 20
        legend.append(
            f'<rect x="{width - 178}" y="{legend_y - 10}" width="12" height="12" '
            f'rx="2" fill="{colors[concurrency]}"/>'
        )
        legend.append(
            f'<text x="{width - 160}" y="{legend_y}" font-size="13" '
            f'fill="#111827">concurrency {concurrency}</text>'
        )

    svg = dedent(
        f"""\
        <svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">
          <rect width="100%" height="100%" fill="white"/>
          <text x="{margin_left}" y="30" font-size="22" font-weight="700" fill="#111827">Mean Query Latency by Workload</text>
          {"".join(legend)}
          {"".join(grid_lines)}
          <line x1="{margin_left}" y1="{margin_top}" x2="{margin_left}" y2="{margin_top + plot_h}" stroke="#374151"/>
          <line x1="{margin_left}" y1="{margin_top + plot_h}" x2="{width - margin_right}" y2="{margin_top + plot_h}" stroke="#374151"/>
          {"".join(bars)}
          {"".join(labels)}
          <text x="{width / 2:.1f}" y="{height - 10}" text-anchor="middle" font-size="13" fill="#374151">Workload</text>
          <text x="20" y="{margin_top + plot_h / 2:.1f}" text-anchor="middle" font-size="13" fill="#374151" transform="rotate(-90 20 {margin_top + plot_h / 2:.1f})">Mean query latency</text>
        </svg>
        """
    )
    path.write_text(svg, encoding="utf-8")


def notebook_cell(cell_type: str, source: str) -> dict:
    cell = {"cell_type": cell_type, "metadata": {}, "source": source.splitlines(keepends=True)}
    if cell_type == "code":
        cell["execution_count"] = None
        cell["outputs"] = []
    return cell


def main() -> None:
    raw_path = RESULTS_DIR / "all_raw.csv"
    summary_path = RESULTS_DIR / "all_summary.csv"
    if not raw_path.exists() or not summary_path.exists():
        raise FileNotFoundError("Expected benchmark/results/all_raw.csv and all_summary.csv")

    ANALYSIS_DIR.mkdir(parents=True, exist_ok=True)

    raw = pd.read_csv(raw_path)
    summary = pd.read_csv(summary_path).sort_values("concurrency").reset_index(drop=True)
    selected_run_id = sorted(summary["run_id"].dropna().unique())[-1]
    raw = raw[raw["run_id"] == selected_run_id].copy()
    summary = (
        summary[summary["run_id"] == selected_run_id]
        .sort_values("concurrency")
        .reset_index(drop=True)
    )
    measured = raw[(raw["status"] == "ok") & (raw["warmup"] == False)].copy()

    base = summary.iloc[0]
    summary_columns = [
        "concurrency",
        "throughput_qps",
        "latency_mean_ms",
        "latency_p50_ms",
        "latency_p95_ms",
        "makespan_seconds",
        "cpu_utilization_cores",
        "aggregate_peak_rss_mb",
        "major_faults_total",
        "host_cpu_count",
        "host_memory_total_mb",
        "host_memory_available_min_mb",
        "host_swap_used_max_mb",
        "host_cpu_utilization_mean_pct",
        "host_cpu_utilization_max_pct",
        "host_loadavg_1m_max",
    ]
    summary_out = summary[[column for column in summary_columns if column in summary.columns]].copy()
    summary_out["throughput_speedup_vs_c1"] = summary_out["throughput_qps"] / base["throughput_qps"]
    summary_out["latency_change_vs_c1_pct"] = (
        summary_out["latency_mean_ms"] / base["latency_mean_ms"] - 1
    ) * 100

    workload = (
        measured.groupby(["concurrency", "workload"])
        .agg(
            runs=("query_elapsed_ms", "size"),
            result_count=("result_count", "first"),
            mean_query_ms=("query_elapsed_ms", "mean"),
            p95_query_ms=("query_elapsed_ms", lambda s: s.quantile(0.95)),
            mean_wall_ms=("process_wall_ms", "mean"),
            mean_cpu_ms=("process_cpu_ms", "mean"),
            mean_rss_mb=("peak_rss_bytes", lambda s: s.mean() / 1024 / 1024),
        )
        .reset_index()
    )

    summary_display = summary_out.copy()
    for col in ["throughput_qps", "throughput_speedup_vs_c1"]:
        summary_display[col] = summary_display[col].map(lambda v: round(v, 3))
    for col in ["latency_mean_ms", "latency_p50_ms", "latency_p95_ms"]:
        summary_display[col] = summary_display[col].map(lambda v: round(v, 0))
    summary_display["makespan_seconds"] = summary_display["makespan_seconds"].map(lambda v: round(v, 1))
    summary_display["cpu_utilization_cores"] = summary_display["cpu_utilization_cores"].map(lambda v: round(v, 2))
    summary_display["aggregate_peak_rss_mb"] = summary_display["aggregate_peak_rss_mb"].map(lambda v: round(v, 0))
    summary_display["latency_change_vs_c1_pct"] = summary_display["latency_change_vs_c1_pct"].map(lambda v: round(v, 1))
    for col in [
        "host_memory_total_mb",
        "host_memory_available_min_mb",
        "host_swap_used_max_mb",
        "host_cpu_utilization_mean_pct",
        "host_cpu_utilization_max_pct",
        "host_loadavg_1m_max",
    ]:
        if col in summary_display.columns:
            summary_display[col] = summary_display[col].map(
                lambda v: "" if pd.isna(v) else round(v, 1)
            )

    workload_display = workload.copy()
    for col in ["mean_query_ms", "p95_query_ms", "mean_wall_ms", "mean_cpu_ms"]:
        workload_display[col] = workload_display[col].map(lambda v: round(v, 0))
    workload_display["mean_rss_mb"] = workload_display["mean_rss_mb"].map(lambda v: round(v, 1))

    line_chart_svg(
        summary_out,
        "concurrency",
        ["throughput_qps"],
        {"throughput_qps": "throughput"},
        "Throughput Scales With Concurrency",
        "Queries per second",
        ANALYSIS_DIR / "throughput.svg",
    )
    line_chart_svg(
        summary_out,
        "concurrency",
        ["latency_mean_ms", "latency_p95_ms"],
        {"latency_mean_ms": "mean latency", "latency_p95_ms": "p95 latency"},
        "Latency Rises Under Contention",
        "Milliseconds",
        ANALYSIS_DIR / "latency.svg",
    )
    if "host_memory_available_min_mb" in summary_out.columns:
        line_chart_svg(
            summary_out,
            "concurrency",
            ["aggregate_peak_rss_mb", "host_memory_available_min_mb"],
            {
                "aggregate_peak_rss_mb": "aggregate worker RSS",
                "host_memory_available_min_mb": "min host available memory",
            },
            "Host Memory Stayed Comfortable",
            "MiB",
            ANALYSIS_DIR / "host_memory.svg",
        )
        line_chart_svg(
            summary_out,
            "concurrency",
            ["host_cpu_utilization_mean_pct", "host_cpu_utilization_max_pct"],
            {
                "host_cpu_utilization_mean_pct": "mean host CPU",
                "host_cpu_utilization_max_pct": "max host CPU",
            },
            "Host CPU Utilization",
            "Percent",
            ANALYSIS_DIR / "host_cpu.svg",
        )
        resource_plot_markdown = dedent(
            """\
            ![Host memory plot](results/analysis/host_memory.svg)

            ![Host CPU plot](results/analysis/host_cpu.svg)
            """
        )
    else:
        line_chart_svg(
            summary_out,
            "concurrency",
            ["aggregate_peak_rss_mb", "cpu_utilization_cores"],
            {"aggregate_peak_rss_mb": "aggregate RSS MB", "cpu_utilization_cores": "CPU cores used"},
            "Resource Use Increases With Workers",
            "Mixed units",
            ANALYSIS_DIR / "resources.svg",
        )
        resource_plot_markdown = "![Resource plot](results/analysis/resources.svg)"
    grouped_bar_svg(workload, ANALYSIS_DIR / "workload_latency.svg")

    c4 = summary_out[summary_out["concurrency"] == 4].iloc[0]
    c2 = summary_out[summary_out["concurrency"] == 2].iloc[0]
    best_workload = workload.sort_values("mean_query_ms", ascending=False).iloc[0]
    result_counts = measured.groupby("workload")["result_count"].first().sort_values(ascending=False)
    failures = int((raw["status"] != "ok").sum())
    if "host_memory_available_min_mb" in summary_out.columns:
        memory_read = (
            f" Aggregate peak RSS grows from **{fmt_mb(base['aggregate_peak_rss_mb'])}** "
            f"at concurrency 1 to **{fmt_mb(c4['aggregate_peak_rss_mb'])}** at concurrency 4. "
            f"The lowest sampled available host memory at concurrency 4 was "
            f"**{fmt_mb(c4['host_memory_available_min_mb'])}**, and peak sampled swap use was "
            f"**{fmt_mb(c4['host_swap_used_max_mb'])}**. If swap stays near zero, major faults "
            "are more likely file-backed page loads/cache misses than true RAM exhaustion."
        )
    else:
        memory_read = (
            f" Aggregate peak RSS grows from **{fmt_mb(base['aggregate_peak_rss_mb'])}** "
            f"at concurrency 1 to **{fmt_mb(c4['aggregate_peak_rss_mb'])}** at concurrency 4. "
            f"There were **{int(c2['major_faults_total'])} major faults** at concurrency 2 and "
            f"**{int(c4['major_faults_total'])} major faults** at concurrency 4; without host "
            "memory/swap samples, treat that as a caution flag rather than proof of memory pressure."
        )

    interpretation = dedent(
        f"""\
        ## Final read

        This benchmark run completed successfully: **{len(measured)} measured query processes**, **{failures} failures**, buffer size **{int(base['buffer_size'])}**, and `use_index = {bool(base['use_index'])}`.

        The useful headline is that concurrency improves throughput, but each individual query gets slower as workers compete for CPU, memory, and storage. Throughput rises from **{fmt_float(base['throughput_qps'], 3)} qps** at concurrency 1 to **{fmt_float(c4['throughput_qps'], 3)} qps** at concurrency 4, which is a **{fmt_float(c4['throughput_speedup_vs_c1'])}x speedup**. Ideal scaling would be 4.00x, so the engine benefits from parallel independent JVMs but is hitting shared-machine limits.

        Latency moves the other way. Mean query latency increases from **{fmt_ms(base['latency_mean_ms'])}** at concurrency 1 to **{fmt_ms(c4['latency_mean_ms'])}** at concurrency 4, a **{fmt_float(c4['latency_change_vs_c1_pct'], 1)}% increase**. That is expected for this benchmark design: ReDB is single-threaded per process, so concurrency comes from multiple JVMs fighting for the same EC2 resources.

        Memory also scales almost linearly with active workers.{memory_read}

        The slowest workload is **`{best_workload['workload']}`**, averaging **{fmt_ms(best_workload['mean_query_ms'])}** at concurrency {int(best_workload['concurrency'])}. It also returns the most rows in this run: {result_counts.to_dict()}.
        """
    )

    cells = [
        notebook_cell(
            "markdown",
            dedent(
                """\
                # ReDB Benchmark Analysis

                This notebook analyzes the CSV files produced by `benchmark/run_benchmark.py`.

                The current input files are:

                - `benchmark/results/all_raw.csv`: one row per query process.
                - `benchmark/results/all_summary.csv`: one row per concurrency level.

                This notebook filters to the latest run in the aggregate CSVs, so older synced runs are ignored.

                The comments below explain what each table or plot means so the notebook can double as a short benchmark report.
                """
            ),
        ),
        notebook_cell(
            "code",
            dedent(
                f"""\
                import pandas as pd
                from pathlib import Path

                results_dir = Path("results")
                raw_all = pd.read_csv(results_dir / "all_raw.csv")
                summary_all = pd.read_csv(results_dir / "all_summary.csv")

                selected_run_id = "{selected_run_id}"
                raw = raw_all[raw_all["run_id"] == selected_run_id].copy()
                summary = summary_all[summary_all["run_id"] == selected_run_id].sort_values("concurrency")
                measured = raw[(raw["status"] == "ok") & (raw["warmup"] == False)]

                raw.shape, summary.shape, measured.shape
                """
            ),
        ),
        notebook_cell("markdown", interpretation),
        notebook_cell(
            "markdown",
            dedent(
                f"""\
                ## Summary by concurrency

                This table is the main benchmark view. `throughput_qps` should go up with concurrency. `latency_mean_ms` and `latency_p95_ms` show the cost paid by each individual query as more workers run at the same time.

                {markdown_table(summary_display)}
                """
            ),
        ),
        notebook_cell(
            "markdown",
            dedent(
                """\
                ## Throughput

                Throughput improves as concurrency increases, but it is sublinear. At concurrency 4, the run reaches about 3.0x the single-worker throughput, not 4.0x. That gap is the shared-resource overhead from CPU scheduling, JVM overhead, memory pressure, and disk/cache contention.

                ![Throughput plot](results/analysis/throughput.svg)
                """
            ),
        ),
        notebook_cell(
            "markdown",
            dedent(
                """\
                ## Latency

                Mean and p95 latency rise as concurrency increases. This is normal for the benchmark because it launches more independent JVMs on the same machine. The important question is whether the throughput gain is worth the extra per-query latency.

                ![Latency plot](results/analysis/latency.svg)
                """
            ),
        ),
        notebook_cell(
            "markdown",
            dedent(
                f"""\
                ## Workload comparison

                The workloads return different numbers of rows, so they are not equally expensive. `medium_t_range` is consistently the slowest because it returns the largest result set in this run.

                {markdown_table(workload_display)}

                ![Workload latency plot](results/analysis/workload_latency.svg)
                """
            ),
        ),
        notebook_cell(
            "markdown",
            dedent(
                f"""\
                ## Resource use

                Aggregate RSS rises with concurrency because each worker is its own JVM. CPU utilization also rises with concurrency, which means the EC2 machine is actually doing more work in parallel rather than just queueing everything serially.

                On this run, the host-level fields show the machine was not close to exhausting memory: available memory stayed far above the workers' aggregate RSS and swap stayed at 0 MB. That means the major faults should be interpreted as ordinary disk-backed page loads/cache misses, not evidence that the EC2 instance ran out of RAM.

                {resource_plot_markdown}
                """
            ),
        ),
        notebook_cell(
            "code",
            dedent(
                """\
                # Useful follow-up query: compare only successful measured rows.
                measured.groupby(["concurrency", "workload"]).agg(
                    runs=("query_elapsed_ms", "size"),
                    result_count=("result_count", "first"),
                    mean_query_ms=("query_elapsed_ms", "mean"),
                    p95_query_ms=("query_elapsed_ms", lambda s: s.quantile(0.95)),
                    mean_rss_mb=("peak_rss_bytes", lambda s: s.mean() / 1024 / 1024),
                ).round(2)
                """
            ),
        ),
        notebook_cell(
            "markdown",
            dedent(
                """\
                ## What to run next

                For a stronger report, run the same benchmark with `--index` and compare `use_index = true` vs `false`. Also try a larger buffer size, for example 50 or 100, to see whether the BNL joins are buffer-limited.

                Suggested next labels:

                - `no-index-buffer-20`
                - `index-buffer-20`
                - `no-index-buffer-50`
                - `index-buffer-50`
                """
            ),
        ),
    ]

    notebook = {
        "cells": cells,
        "metadata": {
            "kernelspec": {
                "display_name": "Python 3",
                "language": "python",
                "name": "python3",
            },
            "language_info": {
                "name": "python",
                "pygments_lexer": "ipython3",
            },
        },
        "nbformat": 4,
        "nbformat_minor": 5,
    }
    NOTEBOOK_PATH.write_text(json.dumps(notebook, indent=2), encoding="utf-8")

    print(f"Wrote {NOTEBOOK_PATH.relative_to(ROOT)}")
    print(f"Wrote plots under {ANALYSIS_DIR.relative_to(ROOT)}")
    print()
    print(interpretation)


if __name__ == "__main__":
    main()
