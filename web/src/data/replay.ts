// Replays a trace's event log up to a cursor and returns the state the UI draws:
// buffer-pool contents, active operator, running counters, and emitted rows.
import type { QueryTrace, TraceEvent } from "../types/trace";

export interface FrameView {
  fileId: string;
  pageId: number;
  pinCount?: number;
  dirty?: boolean;
}

export interface ReplayState {
  activeOperatorId?: string;
  frames: (FrameView | null)[];
  touchedFrameId?: number;
  evictingFrameId?: number;
  running: {
    pagesRead: number;
    bufferHits: number;
    bufferMisses: number;
    evictions: number;
    recordsEmitted: number;
    btreeNodeVisits: number;
  };
  results: { title: string; name: string }[];
  lastEvent?: TraceEvent;
}

export function replay(trace: QueryTrace, cursor: number): ReplayState {
  const frames: (FrameView | null)[] = new Array(trace.run.bufferSize).fill(null);
  const state: ReplayState = {
    frames,
    running: {
      pagesRead: 0,
      bufferHits: 0,
      bufferMisses: 0,
      evictions: 0,
      recordsEmitted: 0,
      btreeNodeVisits: 0,
    },
    results: [],
  };

  const upTo = Math.min(cursor, trace.events.length - 1);
  for (let i = 0; i <= upTo; i++) {
    const e = trace.events[i];
    state.lastEvent = e;
    state.touchedFrameId = undefined;
    state.evictingFrameId = undefined;

    if (e.operatorId) state.activeOperatorId = e.operatorId;

    switch (e.type) {
      case "buffer.hit": {
        state.running.bufferHits++;
        if (e.frame) state.touchedFrameId = e.frame.frameId;
        break;
      }
      case "buffer.miss":
        state.running.bufferMisses++;
        state.running.pagesRead++;
        break;
      case "buffer.evict": {
        state.running.evictions++;
        if (e.frame) {
          state.evictingFrameId = e.frame.frameId;
          frames[e.frame.frameId] = null;
        }
        break;
      }
      case "buffer.page_load": {
        if (e.frame && e.page) {
          frames[e.frame.frameId] = {
            fileId: e.page.fileId,
            pageId: e.page.pageId,
            pinCount: e.frame.pinCount,
            dirty: e.frame.dirty,
          };
          state.touchedFrameId = e.frame.frameId;
        }
        break;
      }
      case "btree.node_visit":
        state.running.btreeNodeVisits++;
        break;
      case "query.result":
        if (e.result) {
          state.running.recordsEmitted++;
          state.results.push({
            title: e.result.fields.title ?? "",
            name: e.result.fields.name ?? "",
          });
        }
        break;
    }
  }

  return state;
}
