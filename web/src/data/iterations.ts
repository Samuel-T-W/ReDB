// One entry per iteration of the engine. Implemented iterations get their own
// page with a live demo; planned ones are listed on the Planned work page and
// shown as a placeholder if you slide onto them.

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
  concurrency: string;
  throughput: string;
  latency: string;
  rss: string;
}

export interface PerfData {
  blurb: string;
  headline: string;
  highlights: PerfHighlight[];
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
        "Notebook-backed benchmark summary from the latest committed concurrency run: 45 measured queries, 0 failures, buffer size 20, no index, on an 8-core host with 15.7 GB RAM.",
      headline:
        "Concurrency improves total throughput, but each query gets slower as independent JVMs compete for CPU, memory, and storage on the same machine.",
      highlights: [
        { label: "Throughput at c4", value: "0.348 qps", tone: "accent" },
        { label: "Speedup vs c1", value: "2.98x", tone: "green" },
        { label: "Mean latency at c4", value: "10,322 ms", tone: "amber" },
        { label: "Peak RSS at c4", value: "2,011 MB", tone: "accent" },
      ],
      rows: [
        { concurrency: "1", throughput: "0.117 qps", latency: "8,480 ms", rss: "544 MB" },
        { concurrency: "2", throughput: "0.209 qps", latency: "9,071 ms", rss: "998 MB" },
        { concurrency: "4", throughput: "0.348 qps", latency: "10,322 ms", rss: "2,011 MB" },
      ],
      takeaways: [
        "Throughput climbs to roughly 3.0x at concurrency 4, but that falls short of ideal 4.0x scaling because the JVMs share one machine.",
        "Latency rises 21.7% from concurrency 1 to 4, which is expected here because each worker is still single-threaded.",
        "The slowest workload in this run is medium_t_range at 13,714 ms mean query time under concurrency 4.",
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
    tagline: "Make the storage engine safe under concurrent access.",
    status: "planned",
    explanation: [
      {
        title: "Why",
        body: "The current engine is single-threaded. This iteration introduces concurrent access without corrupting shared state.",
      },
    ],
    plannedSummary:
      "Latch the buffer pool and page-id allocation, then move toward concurrent B+ tree operations (latch coupling / crabbing). Page-id allocation latching has an early prototype.",
  },
  {
    id: 3,
    version: "v3",
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
];

export function getIteration(id: number): Iteration | undefined {
  return ITERATIONS.find((it) => it.id === id);
}

export const LATEST_IMPLEMENTED = [...ITERATIONS]
  .reverse()
  .find((it) => it.status === "implemented")!;
