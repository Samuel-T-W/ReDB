#!/usr/bin/env bash
#
# redb_bench.sh - memory-budgeted ReDB benchmark
#
# Runs the same workload two ways inside one simulated small machine:
#   legacy  N separate JVM processes, each with its OWN buffer pool
#   shared  1 JVM, ONE QueryEngine pool divided across N client threads
#
# Every memory number is derived from the constants below. Nothing is
# hand-picked and nothing is rounded to a pretty number.
#
set -euo pipefail

# ---------------------------------------------------------------------------
# THE MEMORY MODEL - these five constants are the whole argument
# ---------------------------------------------------------------------------

# Size of the simulated machine. Enforced by a cgroup, swap disabled.
CGROUP_BYTES=$(( 4 * 1024 * 1024 * 1024 ))

# Non-heap cost of ONE JVM: metaspace, thread stacks, code cache, GC structures.
# This is memory the JVM needs that -Xmx does not cover.
JVM_OVERHEAD_BYTES=$(( 300 * 1024 * 1024 ))

# Fraction of the heap deliberately left empty so GC has room to work.
# At 0 the collector thrashes; this is the one judgement call in the model.
GC_HEADROOM=0.40

# Cost of one buffer frame, in bytes:
#     4096  the page payload itself
#    ~1600  BNL join hash entries (~8 per frame, ~200 B each)
#     ~300  Frame object + PageKey + page-table entry
# The middle term is why a frame is not free at 4096: the join hash table
# grows WITH the buffer pool, on the same heap.
BYTES_PER_FRAME=6144

# ---------------------------------------------------------------------------
# Defaults (override with flags)
# ---------------------------------------------------------------------------
MODE="both"
CONCURRENCY_LEVELS="1 2 4"
REPETITIONS=3
WARMUPS=1
REPO="/data/ReDB"
WORKLOAD="${REPO}/benchmark/workload.csv"
OUTDIR="${REPO}/benchmark/results-budgeted"
DROP_CACHES=1

usage() {
  cat <<EOF
Usage: $0 [--mode legacy|shared|both] [--concurrency "1 2 4"]
          [--repetitions N] [--warmups N] [--outdir DIR] [--no-drop-caches]
          [--dry-run]

  --mode        which engine path to exercise (default: both)
  --dry-run     print the computed memory budget and exit, run nothing
EOF
}

DRY_RUN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --mode)            MODE="$2"; shift 2 ;;
    --concurrency)     CONCURRENCY_LEVELS="$2"; shift 2 ;;
    --repetitions)     REPETITIONS="$2"; shift 2 ;;
    --warmups)         WARMUPS="$2"; shift 2 ;;
    --outdir)          OUTDIR="$2"; shift 2 ;;
    --no-drop-caches)  DROP_CACHES=0; shift ;;
    --dry-run)         DRY_RUN=1; shift ;;
    -h|--help)         usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage; exit 2 ;;
  esac
done

case "$MODE" in legacy|shared|both) ;; *) echo "bad --mode: $MODE" >&2; exit 2 ;; esac

# ---------------------------------------------------------------------------
# budget <jvm_count>
#
# Splits CGROUP_BYTES across jvm_count JVMs and reports, via globals:
#   B_XMX_MB          -Xmx for each JVM, in MB
#   B_FRAMES_PER_JVM  frames each JVM's pool can hold
#   B_FRAMES_TOTAL    frames across the whole box
#
#   heap_each = (cgroup - jvm_count * overhead) / jvm_count
#   usable    = heap_each * (1 - GC_HEADROOM)
#   frames    = usable / BYTES_PER_FRAME        <- floor only, no rounding down
# ---------------------------------------------------------------------------
budget() {
  local jvm_count="$1"
  local total_overhead=$(( JVM_OVERHEAD_BYTES * jvm_count ))
  local heap_pool=$(( CGROUP_BYTES - total_overhead ))

  if [ "$heap_pool" -le 0 ]; then
    echo "ERROR: ${jvm_count} JVMs x $((JVM_OVERHEAD_BYTES/1024/1024))MB overhead exceeds the cgroup" >&2
    exit 1
  fi

  local heap_each=$(( heap_pool / jvm_count ))

  B_XMX_MB=$(( heap_each / 1024 / 1024 ))
  B_FRAMES_PER_JVM=$(awk -v h="$heap_each" -v g="$GC_HEADROOM" -v f="$BYTES_PER_FRAME" \
                       'BEGIN { printf "%d", int(h * (1.0 - g) / f) }')
  B_FRAMES_TOTAL=$(( B_FRAMES_PER_JVM * jvm_count ))
}

drop_caches() {
  [ "$DROP_CACHES" -eq 1 ] || return 0
  sync
  echo 3 | sudo tee /proc/sys/vm/drop_caches > /dev/null
}

# Wrap a command in a cgroup capped at CGROUP_BYTES with swap disabled.
in_cgroup() {
  local unit="$1"; shift
  sudo systemd-run --scope --quiet \
    --unit="$unit" --slice=redb-bench.slice \
    -p MemoryMax=${CGROUP_BYTES} -p MemorySwapMax=0 \
    --uid=ubuntu --gid=ubuntu -E HOME=/home/ubuntu \
    "$@"
}

mkdir -p "$OUTDIR"

echo "=================================================================="
echo " ReDB budgeted benchmark"
echo "=================================================================="
printf ' simulated machine : %d MB\n' $(( CGROUP_BYTES / 1024 / 1024 ))
printf ' JVM overhead each : %d MB\n' $(( JVM_OVERHEAD_BYTES / 1024 / 1024 ))
printf ' GC headroom       : %s of heap\n' "$GC_HEADROOM"
printf ' bytes per frame   : %d\n' "$BYTES_PER_FRAME"
printf ' mode              : %s\n' "$MODE"
echo

echo "Computed budget:"
printf '%-8s %-8s %-10s %-14s %-14s %s\n' MODE CONC JVMS XMX_MB FRAMES/JVM FRAMES_TOTAL
for c in $CONCURRENCY_LEVELS; do
  if [ "$MODE" = "legacy" ] || [ "$MODE" = "both" ]; then
    budget "$c"
    printf '%-8s %-8s %-10s %-14s %-14s %s\n' legacy "$c" "$c" "$B_XMX_MB" "$B_FRAMES_PER_JVM" "$B_FRAMES_TOTAL"
  fi
  if [ "$MODE" = "shared" ] || [ "$MODE" = "both" ]; then
    budget 1
    per_query=$(( B_FRAMES_PER_JVM / c ))
    if [ "$per_query" -lt 3 ]; then
      printf '%-8s %-8s %-10s %-14s %-14s %s\n' shared "$c" 1 "$B_XMX_MB" "$B_FRAMES_PER_JVM" "REJECTED(<3/query)"
    else
      printf '%-8s %-8s %-10s %-14s %-14s %s\n' shared "$c" 1 "$B_XMX_MB" "$B_FRAMES_PER_JVM" "$B_FRAMES_TOTAL"
    fi
  fi
done
echo

if [ "$DRY_RUN" -eq 1 ]; then
  echo "(dry run: nothing executed)"
  exit 0
fi

cd "$REPO"

for c in $CONCURRENCY_LEVELS; do

  # ---- legacy: N processes, each with its own pool -----------------------
  if [ "$MODE" = "legacy" ] || [ "$MODE" = "both" ]; then
    budget "$c"
    label="legacy-c${c}"
    echo ">>> ${label}: ${c} JVM(s), -Xmx${B_XMX_MB}m, ${B_FRAMES_PER_JVM} frames each"
    drop_caches
    start=$(date +%s)
    in_cgroup "redb-${label}" \
      python3 "${REPO}/benchmark/run_benchmark.py" \
        --concurrency "$c" \
        --buffer-size "$B_FRAMES_PER_JVM" \
        --java-xmx "${B_XMX_MB}m" \
        --repetitions "$REPETITIONS" \
        --warmups "$WARMUPS" \
        --workload "$WORKLOAD" \
        --output-dir "$OUTDIR" \
        --skip-build \
        --run-label "$label" \
      2>&1 | tee "${OUTDIR}/${label}.log" || echo "!!! ${label} exited non-zero"
    echo "<<< ${label} took $(( $(date +%s) - start ))s"
    echo
  fi

  # ---- shared: 1 process, 1 pool divided across client threads -----------
  if [ "$MODE" = "shared" ] || [ "$MODE" = "both" ]; then
    budget 1
    per_query=$(( B_FRAMES_PER_JVM / c ))
    label="shared-c${c}"
    if [ "$per_query" -lt 3 ]; then
      echo ">>> ${label}: SKIPPED, per-query budget ${per_query} < 3 frames"
      continue
    fi
    echo ">>> ${label}: 1 JVM, -Xmx${B_XMX_MB}m, ${B_FRAMES_PER_JVM} shared frames (${per_query}/query)"
    drop_caches
    start=$(date +%s)
    in_cgroup "redb-${label}" \
      java -Xmx${B_XMX_MB}m -cp "${REPO}/target/classes" EngineBenchmark \
        --workload "$WORKLOAD" \
        --buffer-size "$B_FRAMES_PER_JVM" \
        --max-concurrent "$c" \
        --clients "$c" \
        --repetitions "$REPETITIONS" \
        --warmups "$WARMUPS" \
        --output-dir "$OUTDIR" \
      2>&1 | tee "${OUTDIR}/${label}.log" || echo "!!! ${label} exited non-zero"
    echo "<<< ${label} took $(( $(date +%s) - start ))s"
    echo
  fi

done

echo "=================================================================="
echo "Done. Results in ${OUTDIR}"
ls -1 "$OUTDIR"
