// One entry per iteration of the engine. Implemented iterations get their own
// page with a query simulation; planned ones are listed in the home page's
// roadmap section and shown as a placeholder if you slide onto them.

export interface ExplanationSection {
  title: string;
  body: string;
}

export interface PerfHighlight {
  label: string;
  value: string;
  tone?: "accent" | "green" | "amber";
}

export interface PerfRow {
  cells: string[];
}

export interface PerfData {
  blurb: string;
  headline: string;
  highlights: PerfHighlight[];
  tableTitle: string;
  columns: string[];
  rows: PerfRow[];
  takeaways: string[];
  note: string;
  analysisHref: string;
  analysisLabel: string;
}

export interface Iteration {
  id: number;
  version: string;
  name: string;
  tagline: string;
  status: "implemented" | "planned";
  explanation: ExplanationSection[];
  performance?: PerfData;
  plannedSummary?: string;
}

export const ITERATIONS: Iteration[] = [
  {
    id: 1,
    version: "v1",
    name: "Single-threaded engine",
    tagline:
      "Page storage, an LRU buffer pool, a B+ tree index, and a pull-based query executor — built from scratch in Java.",
    status: "implemented",
    explanation: [
      {
        title: "Pages on disk",
        body: "Every table is a heap file of fixed 4 KB pages. A page holds a record count plus fixed-length record slots — the engine reads and writes whole pages, never loose rows.",
      },
      {
        title: "Buffer pool",
        body: "A fixed set of frames caches hot pages in memory. On a miss the page is read from disk; when the pool is full the least-recently-used unpinned page is evicted to make room.",
      },
      {
        title: "B+ tree index",
        body: "An optional B+ tree on title turns a range query into a root→internal→leaf descent plus a walk along the linked leaves — touching only matching pages instead of scanning the whole table.",
      },
      {
        title: "Query execution",
        body: "Operators pull rows from their children via open / next / close. Block nested-loop joins stage a block of one relation in a hash table, then probe it with the other.",
      },
    ],
    performance: {
      blurb:
        "How this was measured: each worker is a separate ReDB process running the same query - the directors of movies whose title falls in a given range - over ~1.3M IMDB titles. ReDB is single-threaded, so concurrency here means spinning up several of those processes at once: first 1, then 2, then 4, all competing for one 8-core, 15.7 GB machine. At each level every title range runs five times over, and the figures below are the averages across those repetitions. Buffer size 20, no index.",
      headline:
        "Running more processes in parallel raises total throughput, but each individual query gets slower as the independent JVMs fight over CPU, memory, and storage on the same machine.",
      highlights: [
        { label: "Throughput at 4 workers", value: "0.348 qps", tone: "accent" },
        { label: "Speedup vs 1 worker", value: "2.98x", tone: "green" },
        { label: "Mean latency at 4 workers", value: "10,322 ms", tone: "amber" },
        { label: "Peak memory at 4 workers", value: "2,011 MB", tone: "accent" },
      ],
      tableTitle: "By worker count",
      columns: ["workers", "throughput", "mean latency", "peak memory"],
      rows: [
        { cells: ["1", "0.117 qps", "8,480 ms", "544 MB"] },
        { cells: ["2", "0.209 qps", "9,071 ms", "998 MB"] },
        { cells: ["4", "0.348 qps", "10,322 ms", "2,011 MB"] },
      ],
      takeaways: [
        "At 4 workers throughput reaches roughly 3.0x a single worker, short of the ideal 4.0x because the four JVMs share one machine.",
        "Mean latency rises 21.7% going from 1 worker to 4, which is expected here since each worker is still single-threaded.",
        "The heaviest title range, medium_t_range, averages 13,714 ms per query at 4 workers - the largest result set in this run.",
      ],
      note: "Source: benchmark/benchmark_analysis.ipynb in this repo. The notebook also records host memory headroom and shows swap staying at 0 MB during the latest run.",
      analysisHref:
        "https://github.com/Samuel-T-W/ReDB/blob/main/benchmark/benchmark_analysis.ipynb",
      analysisLabel: "Open benchmark analysis notebook",
    },
  },
  {
    id: 2,
    version: "v2",
    name: "Concurrency",
    tagline:
      "Many queries share one buffer pool, with lock-free cache hits, clock eviction, per-query frame budgets, and sequential read-ahead.",
    status: "implemented",
    explanation: [
      {
        title: "One engine, many queries",
        body: "A QueryEngine owns a single shared buffer pool and admits queries through a semaphore. Each admitted query gets a frame budget of at least 32 MiB (8,192 pages), and every pin is charged to it, so concurrent queries can never overrun the pool. Extra queries wait in line instead of failing. Because the engine is read-only, B+ tree splits only happen at build time, so no latch coupling is needed while queries run.",
      },
      {
        title: "Lock-free cache hits",
        body: "Every frame carries an atomic state word. A cache hit pins the page with a compare-and-swap and returns a versioned page handle, and unpinning that handle takes no lock either. With 8 threads hammering a resident working set, the old key-based path took the global lock 40,000 times with 4,066 contentions; the handle path took it zero times.",
      },
      {
        title: "Clock replacement",
        body: "The LRU list needed the global lock on every access just to reorder itself. A clock hand sweeping per-frame reference bits replaces it. A free-frame counter lets a full pool skip straight to eviction: without it, every miss swept all 388,710 frames, and one benchmark query went from 255 s to over 1,500 s.",
      },
      {
        title: "Disk I/O outside the lock",
        body: "Cache misses and dirty-page flushes run outside any lock. Threads racing for the same cold page share one disk read, and a page that is mid-flush is never re-read stale. Each file keeps one FileChannel open for positional reads instead of reopening it per page, which cut a local full-table scan from 20.65 s to 3.20 s.",
      },
      {
        title: "Sequential read-ahead",
        body: "A sequential scan keeps 32 pages (128 KiB) in flight on a shared four-thread prefetch pool. A prefetched page stays unpinned and is charged to the query's budget until it is used or evicted. The query reserves a capped slice of its budget for read-ahead before sizing its join blocks, so prefetching can never starve a join of frames.",
      },
    ],
    performance: {
      blurb:
        "How this was measured: one JVM runs one shared QueryEngine with a 388,710-frame buffer pool inside a 4 GiB memory limit, on an 8-core EC2 c7i.2xlarge with a gp3 disk. One, two, or four clients run the directors-in-a-title-range query at the same time over the IMDb data, with no index and the OS page cache dropped first. Each figure is the mean of 3 repetitions after 1 warmup. The same benchmark was run on three builds, so the columns show the engine improving over time.",
      headline:
        "Adding clients used to slow every query down. With lock-free hits, clock eviction, and read-ahead, a query running beside three others now takes 115 s, down from 224 s on the first shared-pool build, and barely more than a query running alone.",
      highlights: [
        { label: "Mean latency at 4 clients", value: "115.2 s", tone: "accent" },
        { label: "vs first shared build at 4 clients", value: "-49%", tone: "green" },
        { label: "Read-ahead gain at 4 clients", value: "-16.8%", tone: "green" },
        { label: "Latency growth, 1 to 4 clients", value: "+6%", tone: "amber" },
      ],
      tableTitle: "By client count",
      columns: ["clients", "latest", "before read-ahead", "first shared build", "makespan"],
      rows: [
        { cells: ["1", "108.7 s", "121.5 s", "131.5 s", "326 s"] },
        { cells: ["2", "111.8 s", "130.4 s", "173.0 s", "222 s"] },
        { cells: ["4", "115.2 s", "138.4 s", "224.0 s", "115 s"] },
      ],
      takeaways: [
        "On the first shared build, four clients made each query 71% slower than one. Now the gap is 6%, so concurrent queries barely get in each other's way.",
        "Read-ahead alone cut mean latency 10.5% at one client and 16.8% at four. Row counts were identical, no pins were left behind, and disk reads were unchanged at one and two clients and 1.9% higher at four.",
        "The lock-free hit path is proven with counters rather than latency, because these queries are dominated by misses: on a resident working set at 8 threads, the global lock went from 40,000 acquisitions to zero.",
        "Under the same memory limit, the shared pool used to trail four separate processes by 13.6% at four clients. At 115.2 s it now beats their old 121.8 s, though that baseline has not been rerun.",
        "The persistent FileChannel speedup was measured locally and is not part of these EC2 numbers.",
      ],
      note: "Latest = main with read-ahead (Oct 14). Before read-ahead = main with the lock-free buffer pool (Sep 2). First shared build = the original shared QueryEngine (Aug 16). Makespan is the wall time for the whole run at that client count. Raw results live on the benchmark machine.",
      analysisHref: "https://github.com/Samuel-T-W/ReDB/pull/41",
      analysisLabel: "Read the read-ahead PR and benchmark",
    },
  },
  {
    id: 3,
    version: "v3",
    name: "General read-only query planner",
    tagline: "Replace the fixed BNL plan with a cost-based planner over a real (if limited) query surface.",
    status: "planned",
    explanation: [
      {
        title: "Why",
        body: "The engine currently runs one hardcoded 3-table plan. This iteration reuses an existing SQL parser (a limited clause subset, not a from-scratch grammar) and adds a planner that picks access path (seq scan vs. B+ tree) and join order/algorithm by cost, instead of following one fixed shape.",
      },
    ],
    plannedSummary:
      "Foundational for everything after it: the self-describing catalog and the interactive query box both need general query support to exist first.",
  },
  {
    id: 4,
    version: "v4",
    name: "Self-describing catalog",
    tagline: "Store table schemas inside the engine instead of in compiled code.",
    status: "planned",
    explanation: [
      {
        title: "Why",
        body: "Schemas are currently hardcoded constants. A self-describing catalog lets tables be created and described at runtime.",
      },
    ],
    plannedSummary:
      "Move table schemas out of compiled constants into a system catalog stored as a heap file in the engine itself (SQLite-style fixed bootstrap page), and separate logical table name from on-disk file name.",
  },
  {
    id: 5,
    version: "v5",
    name: "Interactive query box",
    tagline: "Let visitors run their own queries against the planner, live, on the website.",
    status: "planned",
    explanation: [
      {
        title: "Why",
        body: "Replaying a fixed preset trace only shows one query. Once the general planner exists, visitors can type their own query (within the supported clause subset) and watch it get planned and traced in real time.",
      },
    ],
    plannedSummary:
      "Depends on v3's general query planner. Replaces the current trace-replay-only demo with a real query input.",
  },
];

export function getIteration(id: number): Iteration | undefined {
  return ITERATIONS.find((it) => it.id === id);
}

export const LATEST_IMPLEMENTED = [...ITERATIONS]
  .reverse()
  .find((it) => it.status === "implemented")!;
