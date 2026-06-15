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
```

`-Xmx` limits the Java heap, not total process or machine memory. Use a VM,
container, or cgroup when a strict total-memory limit is required.

## Results

Every run creates three timestamped files under `benchmark/results/`:

- `*_raw.csv`: one row per query, including engine latency, process wall time,
  CPU time, JVM heap usage, sampled peak resident memory, page faults, result
  count, and failure details.
- `*_summary.csv`: throughput, latency, CPU utilization, per-worker peak memory,
  aggregate peak worker memory, page faults, and swap activity by concurrency.
- `*_metadata.json`: benchmark configuration, Git commit, Java version, and
  machine metadata.

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
