// Generates a QueryTrace that conforms to the Java trace schema, without the
// engine. The numbers are invented but internally consistent and respond to the
// controls (range width, buffer size, index on/off), so the demo behaves the way
// the real engine will. Swap this out for committed engine traces later.

import {
  type QueryTrace,
  type TraceEvent,
  type TraceEventType,
  type TracePlanNode,
} from "../types/trace";
import { CURRENT_SCHEMA_VERSION } from "../types/traceSchema";

export interface TraceParams {
  start: string;
  end: string;
  bufferSize: number;
  indexed: boolean;
}

const MOVIES = "title.csv";
const WORKEDON = "workedon.csv";
const PEOPLE = "name.csv";
const TITLE_INDEX = "title.idx";

// Deterministic PRNG (mulberry32) so the same params always render the same run.
function rng(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function hashSeed(p: TraceParams): number {
  const s = `${p.start}|${p.end}|${p.bufferSize}|${p.indexed}`;
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
}

// Wider [start,end] → more matching movies. Bounded so the event log stays watchable.
function matchCount(p: TraceParams, rand: () => number): number {
  const span = Math.abs(p.end.localeCompare(p.start)) + p.end.length;
  const base = 4 + Math.floor(rand() * 6) + Math.min(span, 6);
  return Math.max(3, Math.min(12, base));
}

function buildPlan(indexed: boolean): TracePlanNode {
  const moviesAccess: TracePlanNode = indexed
    ? {
        id: "movies-index",
        type: "INDEX_SCAN",
        label: "Index Scan: Movies",
        detail: "B+ tree range on title",
        children: [],
      }
    : {
        id: "movies-sigma",
        type: "SELECTION",
        label: "σ  title ∈ [start, end]",
        detail: "filter over full scan",
        children: [
          {
            id: "movies-scan",
            type: "SCAN",
            label: "Scan: Movies",
            detail: "title.csv",
            children: [],
          },
        ],
      };

  const workedOnPipe: TracePlanNode = {
    id: "wo-pi",
    type: "MATERIALIZE",
    label: "π  (movieId, personId)",
    detail: "materialized temp file",
    children: [
      {
        id: "wo-sigma",
        type: "SELECTION",
        label: "σ  category = 'director'",
        children: [
          { id: "wo-scan", type: "SCAN", label: "Scan: WorkedOn", detail: "workedon.csv", children: [] },
        ],
      },
    ],
  };

  const innerJoin: TracePlanNode = {
    id: "join-movies-wo",
    type: "BNL_JOIN",
    label: "BNL ⋈  Movies.movieId = WorkedOn.movieId",
    children: [moviesAccess, workedOnPipe],
  };

  const peopleScan: TracePlanNode = {
    id: "people-scan",
    type: "SCAN",
    label: "Scan: People",
    detail: "name.csv",
    children: [],
  };

  const outerJoin: TracePlanNode = {
    id: "join-wo-people",
    type: "BNL_JOIN",
    label: "BNL ⋈  WorkedOn.personId = People.personId",
    children: [innerJoin, peopleScan],
  };

  return {
    id: "project",
    type: "PROJECT",
    label: "π  (title, name)",
    children: [outerJoin],
  };
}

interface Ctx {
  events: TraceEvent[];
  seq: number;
  t: number;
  pool: Pool;
}

interface PoolFrame {
  fileId: string;
  pageId: number;
}

// Simpler inline pool kept in Ctx (records eviction order via array index).
class Pool {
  frames: (PoolFrame | null)[];
  order: number[] = [];
  map = new Map<string, number>();
  freeList: number[];
  evictions = 0;
  hits = 0;
  misses = 0;
  pagesRead = 0;

  constructor(size: number) {
    this.frames = new Array(size).fill(null);
    this.freeList = Array.from({ length: size }, (_, i) => i);
  }
}

function keyOf(fileId: string, pageId: number): string {
  return `${fileId}#${pageId}`;
}

function emit(ctx: Ctx, type: TraceEventType, extra: Partial<TraceEvent> = {}): void {
  ctx.t += 1; // monotonic step; real timeMs is spread across the run at the end
  ctx.events.push({ seq: ctx.seq++, timeMs: ctx.t, type, ...extra });
}

// Touch a page through the pool, emitting hit/miss/load/evict events.
function touch(ctx: Ctx, operatorId: string, fileId: string, pageId: number): void {
  const pool = ctx.pool;
  const k = keyOf(fileId, pageId);
  const have = pool.map.get(k);
  if (have !== undefined) {
    pool.hits++;
    // move to most-recently-used
    pool.order = pool.order.filter((f) => f !== have);
    pool.order.push(have);
    emit(ctx, "buffer.hit", {
      operatorId,
      page: { fileId, pageId },
      frame: { frameId: have },
    });
    return;
  }

  pool.misses++;
  pool.pagesRead++;
  emit(ctx, "buffer.miss", { operatorId, page: { fileId, pageId } });

  let frameId: number;
  if (pool.freeList.length > 0) {
    frameId = pool.freeList.shift()!;
  } else {
    // evict LRU (front of order)
    frameId = pool.order.shift()!;
    const victim = pool.frames[frameId]!;
    pool.map.delete(keyOf(victim.fileId, victim.pageId));
    pool.evictions++;
    emit(ctx, "buffer.evict", {
      operatorId,
      frame: { frameId, evictedPage: { fileId: victim.fileId, pageId: victim.pageId } },
    });
  }

  pool.frames[frameId] = { fileId, pageId };
  pool.map.set(k, frameId);
  pool.order.push(frameId);
  emit(ctx, "buffer.page_load", {
    operatorId,
    page: { fileId, pageId },
    frame: { frameId, pinCount: 1, dirty: false },
  });
}

export function generateTrace(params: TraceParams): QueryTrace {
  const rand = rng(hashSeed(params));
  const matches = matchCount(params, rand);

  const ctx: Ctx = { events: [], seq: 0, t: 0, pool: new Pool(params.bufferSize) };

  emit(ctx, "operator.open", { operatorId: "project", message: "open query plan" });

  // --- Access Movies (indexed range scan vs full scan + filter) ---
  let btreeNodeVisits = 0;
  let recordsExamined = 0;
  const matchedMoviePages: number[] = [];

  if (params.indexed) {
    emit(ctx, "btree.search_begin", {
      operatorId: "movies-index",
      btree: { indexFileId: TITLE_INDEX, rangeStart: params.start, rangeEnd: params.end },
    });
    // root -> internal -> leaf descent
    const descent = [
      { nodeType: "INTERNAL" as const, pageId: 0 },
      { nodeType: "INTERNAL" as const, pageId: 2 + Math.floor(rand() * 3) },
      { nodeType: "LEAF" as const, pageId: 12 + Math.floor(rand() * 4) },
    ];
    for (const node of descent) {
      touch(ctx, "movies-index", TITLE_INDEX, node.pageId);
      btreeNodeVisits++;
      emit(ctx, "btree.node_visit", {
        operatorId: "movies-index",
        btree: { indexFileId: TITLE_INDEX, nodePageId: node.pageId, nodeType: node.nodeType },
      });
    }
    emit(ctx, "btree.range_leaf_begin", {
      operatorId: "movies-index",
      btree: { indexFileId: TITLE_INDEX, rangeStart: params.start, rangeEnd: params.end },
    });
    for (let i = 0; i < matches; i++) {
      const moviePage = 3 + Math.floor(rand() * 30);
      matchedMoviePages.push(moviePage);
      recordsExamined++;
      emit(ctx, "btree.range_emit", {
        operatorId: "movies-index",
        recordId: { pageId: moviePage, slotId: Math.floor(rand() * 40) },
      });
      touch(ctx, "movies-index", MOVIES, moviePage);
    }
    emit(ctx, "btree.search_end", { operatorId: "movies-index" });
  } else {
    const moviePages = 34;
    emit(ctx, "operator.open", { operatorId: "movies-scan" });
    for (let pageId = 0; pageId < moviePages; pageId++) {
      emit(ctx, "scan.page_begin", { operatorId: "movies-scan", page: { fileId: MOVIES, pageId } });
      touch(ctx, "movies-scan", MOVIES, pageId);
      const recordsOnPage = 38;
      recordsExamined += recordsOnPage;
      // a handful of pages contain matching titles
      const pageMatches = matchedMoviePages.length < matches && rand() < matches / moviePages;
      if (pageMatches) {
        matchedMoviePages.push(pageId);
        emit(ctx, "filter.pass", {
          operatorId: "movies-sigma",
          page: { fileId: MOVIES, pageId },
          recordId: { pageId, slotId: Math.floor(rand() * recordsOnPage) },
        });
      } else {
        emit(ctx, "filter.reject", { operatorId: "movies-sigma", page: { fileId: MOVIES, pageId } });
      }
      emit(ctx, "scan.page_end", { operatorId: "movies-scan", page: { fileId: MOVIES, pageId } });
    }
    // ensure we hit the target match count
    while (matchedMoviePages.length < matches) {
      matchedMoviePages.push(Math.floor(rand() * moviePages));
    }
  }

  // --- Materialize WorkedOn σ(category='director') π(movieId, personId) ---
  emit(ctx, "operator.open", { operatorId: "wo-sigma", message: "materialize director credits" });
  const woPages = 18;
  for (let pageId = 0; pageId < woPages; pageId++) {
    touch(ctx, "wo-scan", WORKEDON, pageId);
    recordsExamined += 30;
    if (rand() < 0.5) {
      emit(ctx, "filter.pass", { operatorId: "wo-sigma", page: { fileId: WORKEDON, pageId } });
    }
  }

  // --- BNL join: Movies ⋈ WorkedOn ---
  const matchedPeople: { personId: string; movieRow: number }[] = [];
  const innerBlocks = Math.max(1, Math.ceil(matchedMoviePages.length / Math.max(1, params.bufferSize - 2)));
  let blockId = 0;
  for (let b = 0; b < innerBlocks; b++) {
    emit(ctx, "bnl.block_begin", {
      operatorId: "join-movies-wo",
      join: { blockId, side: "LEFT" },
    });
    emit(ctx, "bnl.hash_build", {
      operatorId: "join-movies-wo",
      join: { blockId, side: "LEFT", matches: matchedMoviePages.length },
    });
    const probes = 3 + Math.floor(rand() * 3);
    for (let pr = 0; pr < probes; pr++) {
      const key = `tt${1000000 + Math.floor(rand() * 8999999)}`;
      const hit = rand() < 0.6;
      emit(ctx, "bnl.probe", { operatorId: "join-movies-wo", join: { blockId, side: "RIGHT", key } });
      if (hit) {
        emit(ctx, "bnl.match", { operatorId: "join-movies-wo", join: { blockId, key, matches: 1 } });
        matchedPeople.push({ personId: `nm${1000000 + Math.floor(rand() * 8999999)}`, movieRow: pr });
      }
    }
    emit(ctx, "bnl.block_end", { operatorId: "join-movies-wo", join: { blockId } });
    blockId++;
  }

  // --- BNL join: (Movies⋈WorkedOn) ⋈ People, then project + emit results ---
  const peoplePages = 26;
  emit(ctx, "bnl.block_begin", { operatorId: "join-wo-people", join: { blockId, side: "RIGHT" } });
  for (let pageId = 0; pageId < Math.min(peoplePages, 8 + params.bufferSize); pageId++) {
    touch(ctx, "people-scan", PEOPLE, pageId);
  }
  emit(ctx, "bnl.hash_build", { operatorId: "join-wo-people", join: { blockId, side: "RIGHT" } });

  const SAMPLE_TITLES = [
    "The Quiet Frame",
    "Index of Shadows",
    "Buffer Overflow",
    "Leaf Node Lullaby",
    "Sequential Hearts",
    "The Eviction Notice",
    "Pinned",
    "B-Tree Blues",
    "Page Fault",
    "Director's Cut",
    "Range Scan Romance",
    "Tuple Trouble",
  ];
  const SAMPLE_NAMES = [
    "Ada Pageman",
    "Bram Cursor",
    "Cora Leaf",
    "Drew Pivot",
    "Esme Frame",
    "Finn Buffer",
    "Greta Slot",
    "Hank Heap",
    "Ivy Record",
    "Jonas Pin",
    "Kira Evict",
    "Lou Probe",
  ];

  const resultRows = Math.min(matches, matchedPeople.length || matches);
  let ordinal = 0;
  for (let i = 0; i < resultRows; i++) {
    const title = SAMPLE_TITLES[i % SAMPLE_TITLES.length];
    const name = SAMPLE_NAMES[(i * 5 + 3) % SAMPLE_NAMES.length];
    emit(ctx, "operator.next", { operatorId: "project" });
    emit(ctx, "operator.emit", { operatorId: "project" });
    emit(ctx, "query.result", {
      operatorId: "project",
      result: { ordinal: ordinal++, fields: { title, name } },
    });
  }
  emit(ctx, "bnl.block_end", { operatorId: "join-wo-people", join: { blockId } });

  emit(ctx, "operator.close", { operatorId: "project" });
  emit(ctx, "query.complete", { message: `${resultRows} rows` });

  // Spread timeMs across the run so the scrubber feels like wall-clock time.
  const wallClockMs = 40 + ctx.pool.pagesRead * 3 + ctx.events.length;
  const span = ctx.events.length || 1;
  ctx.events.forEach((e, i) => {
    e.timeMs = Math.round((i / span) * wallClockMs);
  });

  return {
    schemaVersion: CURRENT_SCHEMA_VERSION,
    run: {
      id: `run-${hashSeed(params).toString(16)}`,
      command: "run_query",
      startedAt: new Date().toISOString(),
      range: { start: params.start, end: params.end },
      bufferSize: params.bufferSize,
      indexed: params.indexed,
      wallClockMs,
    },
    summary: {
      pagesRead: ctx.pool.pagesRead,
      bufferHits: ctx.pool.hits,
      bufferMisses: ctx.pool.misses,
      evictions: ctx.pool.evictions,
      recordsExamined,
      recordsEmitted: resultRows,
      operatorNextCalls: resultRows,
      btreeNodeVisits,
    },
    plan: buildPlan(params.indexed),
    tables: {
      [MOVIES]: { fileId: MOVIES, recordSize: 43, recordCount: 1300 },
      [WORKEDON]: { fileId: WORKEDON, recordSize: 39, recordCount: 540 },
      [PEOPLE]: { fileId: PEOPLE, recordSize: 115, recordCount: 780 },
    },
    events: ctx.events,
  };
}
