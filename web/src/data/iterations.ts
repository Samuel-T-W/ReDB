// One entry per iteration of the engine. Implemented iterations get their own
// page with a live demo; planned ones are listed on the Planned work page and
// shown as a placeholder if you slide onto them.

export interface ExplanationSection {
  title: string;
  body: string;
}

export interface PerfRow {
  label: string;
  scan: string;
  index: string;
}

export interface PerfData {
  blurb: string;
  rows: PerfRow[];
  note: string;
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
        "Range query over ~1.3M IMDB title rows: directors of movies whose title falls in a range. Full sequential scan vs. B+ tree range access, buffer pool = 8 frames.",
      rows: [
        { label: "Pages read from disk", scan: "1,041", index: "37" },
        { label: "Query latency (warm)", scan: "612 ms", index: "41 ms" },
        { label: "Buffer hit rate", scan: "18%", index: "73%" },
        { label: "Records examined", scan: "1.3M", index: "12" },
      ],
      note: "Representative numbers for this iteration. Live engine benchmarks replace these once the trace pipeline lands.",
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
