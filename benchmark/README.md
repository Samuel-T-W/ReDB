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

# Shared-engine concurrent benchmark

The shared-engine benchmark runs the tracked 12-query `benchmark/concurrency_workload.csv` against one `QueryEngine` JVM for each fixed concurrency configuration.
The fixed matrix is `(max_concurrent, clients, buffer_size) = (1, 1, 20), (2, 2, 40), (4, 4, 80)` with one warmup, five measured repetitions, and no index.

Create the database files, then run the harness from anywhere inside the repository:

```bash
./run.sh pre_process
python3 benchmark/run_shared_engine_benchmark.py
```

Use `--skip-build` after compiling, `--workload <path>` to supply another manifest with the same exact CSV header, or `--output-dir <new-path>` to choose the run directory.
The output directory must not already exist.
By default, each run uses a new UTC identifier under `benchmark/results/shared-engine/`.

```text
benchmark/results/shared-engine/<UTC-run-id>/
├── workload.csv
├── queries.csv
├── repetitions.csv
├── summary.csv
├── metadata.json
├── concurrency-1-buffer-20/
│   ├── engine.metrics
│   ├── engine.stderr.log
│   └── resources.json
├── concurrency-2-buffer-40/
│   ├── engine.metrics
│   ├── engine.stderr.log
│   └── resources.json
└── concurrency-4-buffer-80/
    ├── engine.metrics
    ├── engine.stderr.log
    └── resources.json
```

`workload.csv` is the exact copied manifest used by the run.
Each `engine.metrics` contains the Java line protocol, each `engine.stderr.log` preserves Java diagnostics, and each `resources.json` contains sampled process and host resource summaries.
`queries.csv` contains one row per measured query, `repetitions.csv` contains one row per configuration repetition, and `summary.csv` contains one row per configuration.
`metadata.json` records repository, Java, platform, and build provenance after the full run succeeds.

# Historical multi-JVM results

The pre-shared-engine result artifacts and analysis figures are preserved under `benchmark/results/legacy-multi-jvm/`.
They were copied byte-for-byte from the pre-existing ignored local results and added to Git tracking; they were not regenerated or modified by this work.
They remain historical evidence from the isolated multi-JVM harness described below and are not controlled shared-engine results.

# Running benchmarks on the EC2 machine

Recorded benchmark numbers come from a dedicated EC2 instance, not from a laptop.
Local macOS runs are useful for smoke-testing the harness, but their numbers must never be compared against Linux results, because the machine differs.

## The machine

| Field | Value |
| --- | --- |
| Name tag | benchmarking machine |
| Instance ID | `i-078521131c52e1d0a` |
| Region | `us-east-1` |
| Instance type | `c7i.2xlarge` (8 vCPU, 16 GiB) |
| AMI | `ubuntu/images/hvm-ssd-gp3/ubuntu-resolute-26.0` |
| SSH user | `ubuntu` |
| Private IPv4 | `172.31.24.16` |
| Repository path | `/home/ubuntu/ReDB` |
| Security group | `sg-0047e40a49cd59dc2` (`launch-wizard-1`) |

The private IP survives stop and start, so it is the reliable way to identify the instance.
No Elastic IP is attached, so every start assigns a different public IP.
The root volume is EBS gp3, so the repository and the generated data files survive a stop.

The SSH key is `Benchmark ec2 key.pem`, kept in the repository root.
It is excluded by `.gitignore` and must never be committed.
Keep the local copy at mode `400` or OpenSSH will refuse it.

## 1. Start the instance

Start it from the EC2 console, or with the CLI once your credentials are valid.

```bash
aws ec2 start-instances --instance-ids i-078521131c52e1d0a --region us-east-1
aws ec2 wait instance-running --instance-ids i-078521131c52e1d0a --region us-east-1
aws ec2 describe-instances --instance-ids i-078521131c52e1d0a --region us-east-1 \
  --query 'Reservations[].Instances[].PublicDnsName' --output text
```

Wait for both status checks to pass before connecting.

## 2. Let your current IP through the security group

The inbound SSH rule on `sg-0047e40a49cd59dc2` is pinned to a single address.
A home or office IP usually changes between sessions, so an unreachable host is far more often a stale rule than a broken instance.

```bash
curl -s https://checkip.amazonaws.com
aws ec2 authorize-security-group-ingress --group-id sg-0047e40a49cd59dc2 \
  --protocol tcp --port 22 --cidr "$(curl -s https://checkip.amazonaws.com)/32" --region us-east-1
```

Revoke stale rules with `revoke-security-group-ingress` so the group does not accumulate dead addresses.

## 3. Connect

```bash
ssh -i "Benchmark ec2 key.pem" ubuntu@<public-dns>
```

## 4. Sync the repository and build

```bash
cd /home/ubuntu/ReDB
git fetch && git checkout <branch> && git pull
mvn -q compile
ls data/
```

If `data/` is missing the heap and index files, rebuild them with `./run.sh pre_process`.
That step is slow, so avoid deleting the volume between sessions.

## 5. Install the Python dependencies

```bash
pip3 install pandas matplotlib jupyter
```

## 6. Run both harnesses

The comparison needs one run from each harness, produced on the same machine.
Both runners default to the same tracked `benchmark/concurrency_workload.csv` and to matching aggregate buffer capacity, so run them with their defaults and do not override the workload or buffer options.

```bash
python3 benchmark/run_benchmark.py --skip-build
python3 benchmark/run_shared_engine_benchmark.py --skip-build
```

The legacy runner appends to `benchmark/results/legacy-multi-jvm/runs/`.
The shared runner writes a new UTC-stamped directory under `benchmark/results/shared-engine/`.

Archived results directly under `benchmark/results/legacy-multi-jvm/` predate these aligned defaults and are not valid notebook input.
Always generate a fresh legacy run rather than reusing them.

## 7. Execute the comparison notebook

Set the input paths in the first code cell of `benchmark/shared_engine_comparison.ipynb` to the two directories produced above, then execute it.

```bash
cd /home/ubuntu/ReDB/benchmark
jupyter nbconvert --to notebook --execute shared_engine_comparison.ipynb \
  --output shared_engine_comparison_executed.ipynb
```

To work interactively instead, forward the port from your machine with `ssh -i "Benchmark ec2 key.pem" -L 8888:localhost:8888 ubuntu@<public-dns>` and start `jupyter notebook --no-browser --port=8888` on the instance.

## 8. Copy the results back

```bash
scp -i "Benchmark ec2 key.pem" \
  ubuntu@<public-dns>:/home/ubuntu/ReDB/benchmark/shared_engine_comparison_executed.ipynb .
```

## 9. Stop the instance

```bash
aws ec2 stop-instances --instance-ids i-078521131c52e1d0a --region us-east-1
```

A `c7i.2xlarge` bills by the second while running, so leaving it up is the expensive failure mode.
Stopping preserves the volume, so the next session resumes from step 1.

# Memory-budgeted full-IMDb benchmark

Use this workflow, not the default matrix above, whenever the question is about buffer pool behaviour: eviction policy, replacement, pin/unpin cost, or lock contention.

`benchmark/run_shared_engine_benchmark.py` hardcodes `MATRIX = ((1,1,20),(2,2,40),(4,4,80))`, so its pool is 20 to 80 frames against a multi-gigabyte table.
Those runs are dominated by sequential page I/O and BNL join CPU, measured at roughly 120 s per query.
Any buffer-pool change is a rounding error at that query length, so the result measures the workload rather than the code.

`benchmark/redb_bench.sh` exists for the opposite regime.
It sizes one shared pool to fill a simulated small machine, then runs the full IMDb dataset against it, so the pool is saturated and eviction runs continuously.

## Two checkouts, two volumes

The instance carries two independent working copies, and `df -h /` shows only the first.

| Path | Volume | Dataset |
| --- | --- | --- |
| `/home/ubuntu/ReDB` | root, 6.7 GB | compact CSVs, ~475k titles |
| `/data/ReDB` | `/dev/nvme1n1`, 98 GB | full IMDb snapshot, 12.7M titles |

Run `df -h` with no argument, or the `/data` volume is invisible.

The `.db` files in `/home/ubuntu/ReDB` date from June and predate the current record layout.
Using them fails with `Offset or length out of bounds`.
Regenerate with `./run.sh pre_process`, which takes about 20 seconds on the compact CSVs.

`/data/ReDB` holds the full heap files at the current layout, about 12.4 GB total.
They are reusable by any branch whose `PreProcessor` schema matches (`movieId` 10, `title` 482, `category` 20, `name` 105), so confirm the schema before spending an hour regenerating them.

## Running it

Always start with the budget, which executes nothing:

```bash
/data/ReDB/benchmark/redb_bench.sh --dry-run
```

For a 4 GiB simulated machine this prints `-Xmx3796m` and 388710 frames, a pool of about 1.59 GB against a 12.4 GB working set.
That ratio is the point: the working set cannot fit, so the replacer is on the hot path.

```bash
cd /data/ReDB
mvn -q compile
setsid nohup ./benchmark/redb_bench.sh --mode shared > ~/bench.log 2>&1 < /dev/null &
```

`setsid nohup` is required, not tidiness.
The benchmark outlives any single SSH session, and a client that disconnects or is backgrounded takes the remote JVM down with it, leaving query output but no `engine.metrics`.

`redb_bench.sh` needs `sudo` for `systemd-run` and for dropping caches, so it cannot run under a non-interactive key without passwordless sudo.

Budget roughly 33, 28, and 21 minutes for shared concurrency 1, 2, and 4, about 83 minutes for the sweep.
`--mode both` also runs the legacy multi-JVM path and roughly doubles that.

## Reference numbers

From the archived 2026-08-16 run, 4 GiB cgroup, swap disabled, caches dropped, 3 repetitions:

| clients | mean makespan | read I/Os |
| --- | --- | --- |
| 1 | 393 s | 27,625,995 |
| 2 | 326 s | 18,543,333 |
| 4 | 224 s | 9,460,671 |

Read I/O falling as clients rise is the shared pool working, since concurrent queries reuse each other's resident pages.
Treat these as the baseline shape to reproduce before trusting a new run.

The archived legacy and shared runs are not directly comparable to each other: the shared side used a 4 GiB cgroup and 3 repetitions while the legacy side used its Python harness with 5.
Comparisons between branches are sound as long as both sides use this driver with identical flags.

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
- `all_summary.csv`: one row per measured repetition with `run_id` and config
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
