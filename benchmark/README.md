## `btree/` range-query benchmarks

`benchmark/btree/` holds JUnit test classes (`MovieIDRangeQueryPerformanceTest`,
`TitleRangeQueryPerformanceTest`, and their `Pinned*` variants) that time
table-scan vs. B+ tree index-scan range queries and write timing CSVs under
`report/`. They live outside `test/` on purpose so `mvn test` does not compile
or run them — they were never meant as CI correctness gates, only as tooling
to regenerate report figures.

To run one, temporarily copy it back into `test/btree/` and invoke it directly:

```bash
cp benchmark/btree/MovieIDRangeQueryPerformanceTest.java test/btree/
mvn test -Dtest=MovieIDRangeQueryPerformanceTest
rm test/btree/MovieIDRangeQueryPerformanceTest.java
```

# ReDB concurrent query benchmark

This benchmark runs the existing `run_query` plan with multiple isolated JVM
processes. Each process gets its own working directory so ReDB's fixed temporary
file names cannot collide. The heap and index data files are shared read-only.

The benchmark recreates the `BufferManager` for every query, matching the
current command-line behavior. ReDB itself remains single-threaded; concurrency
here means multiple independent database processes competing for the same
machine resources.

## Prerequisites

Create the database files and compile the project:

```bash
./run.sh pre_process
mvn compile
```

## Run

Normal benchmark run:

```bash
python3 benchmark/run_benchmark.py \
  --concurrency 1,2,4 \
  --repetitions 5 \
  --warmups 1 \
  --buffer-size 20 \
  --run-label "something about the run you want to mention"
```

For repeat runs after compiling, skip the Maven build step:

```bash
python3 benchmark/run_benchmark.py \
  --concurrency 1,2,4 \
  --repetitions 5 \
  --warmups 1 \
  --buffer-size 20 \
  --skip-build
```

Edit `benchmark/workload.csv` to change the title ranges. Its columns are:

```text
name,start_range,end_range
```

Useful options:

```text
--index                 use the title-index path
--java-xmx 1g           cap each worker JVM's maximum heap
--memory-sample-ms 50   OS memory sampling interval
--output-dir            choose the result directory
--run-label             attach a human-readable label to output rows
```

`-Xmx` limits the Java heap, not total process or machine memory. Use a VM,
container, or cgroup when a strict total-memory limit is required.

For analysis workflows, the default behavior keeps one growing CSV instead of
one file per benchmark invocation. Every run appends to stable aggregate files:

```bash
python3 benchmark/run_benchmark.py \
  --concurrency 1,2,4 \
  --repetitions 5 \
  --warmups 1 \
  --buffer-size 20 \
  --skip-build \
  --run-label no-index-buffer-20
```

The default output writes:

- `all_raw.csv`: one row per query process with `run_id`.
- `all_summary.csv`: one row per concurrency level with `run_id` and config
  columns such as `buffer_size`, `use_index`, `repetitions`, and `java_xmx`.
- `all_metadata.jsonl`: one JSON object per benchmark invocation.

The `run_id` is always the run's UTC timestamp.

New benchmark versions may append additional columns to the aggregate CSVs.
Existing rows are preserved and older runs show blank values for columns that
did not exist when they were recorded.

## Results

Runs only update the aggregate files above.

`query_elapsed_ms` is measured inside the already-started JVM around the full
query call, including creation of the buffer manager and query plan.
`process_wall_ms` also includes JVM startup and shutdown. Group throughput uses
the wall-clock makespan, so it reflects end-to-end concurrent execution.

`peak_rss_bytes` is sampled process resident memory and includes the JVM heap,
native JVM memory, code cache, and thread stacks. It does not include the
machine's filesystem cache. `aggregate_peak_rss_mb` is the largest sampled sum
across all simultaneously active query workers.

`jvm_heap_pool_peak_sum_bytes` is the sum of each heap memory pool's reported
peak. Those pool peaks may occur at slightly different times, so treat this as
a JVM heap high-water indicator rather than an exact simultaneous total.

Linux reports per-worker swap and minor/major page faults through `/proc`.
macOS reports resident memory and page faults through `ps`. System swap-in and
swap-out deltas are recorded where the OS exposes them. A summary sets
`memory_pressure_detected=true` when worker swap is present or system swap
activity occurs during the measured group. The value is blank when swap
measurement is unavailable. Runs with memory pressure should normally be
excluded from CPU/storage performance comparisons.

RSS and page-fault counters are sampled, so very short-lived peaks between
samples can be missed. Reduce `--memory-sample-ms` for finer resolution, while
recognizing that aggressive sampling adds benchmark overhead.

The summary records attempted, successful, and failed per-PID memory samples.
A sample is successful only when RSS, minor faults, and major faults are all
available. Available values from incomplete samples still contribute to the
corresponding memory and page-fault metrics.

The summary also records host-level context for each measured concurrency
group:

- `host_cpu_count`: logical CPU count visible to the OS.
- `host_memory_total_mb`: machine memory.
- `host_memory_available_min_mb`: lowest sampled available memory.
- `host_memory_available_mean_mb`: average sampled available memory.
- `host_swap_used_max_mb`: highest sampled swap usage.
- `host_cpu_utilization_mean_pct`: average whole-machine CPU utilization.
- `host_cpu_utilization_max_pct`: peak sampled whole-machine CPU utilization.
- `host_loadavg_1m_max`: highest sampled 1-minute load average.
- `host_samples_attempted` / `host_samples_successful`: host sampler coverage.

On Linux these values come from `/proc/stat`, `/proc/meminfo`, and
`/proc/loadavg`. They help distinguish true memory pressure from ordinary
file-backed page faults and filesystem cache misses.
