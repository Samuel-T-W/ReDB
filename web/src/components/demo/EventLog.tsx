import { useEffect, useRef } from "react";
import type { TraceEvent } from "../../types/trace";

function category(type: string): string {
  const head = type.split(".")[0];
  if (head === "buffer") return "buffer";
  if (head === "btree") return "btree";
  if (head === "bnl") return "bnl";
  if (head === "filter") return "filter";
  if (head === "scan") return "scan";
  if (head === "query") return "result";
  return "operator";
}

function describe(e: TraceEvent): string {
  if (e.page) return `${e.page.fileId} p${e.page.pageId}`;
  if (e.btree?.nodeType) return `${e.btree.nodeType.toLowerCase()} #${e.btree.nodePageId ?? ""}`;
  if (e.join?.key) return `key ${e.join.key}`;
  if (e.result) return `${e.result.fields.title} — ${e.result.fields.name}`;
  if (e.frame?.evictedPage) return `→ ${e.frame.evictedPage.fileId} p${e.frame.evictedPage.pageId}`;
  if (e.message) return e.message;
  return e.operatorId ?? "";
}

export default function EventLog({
  events,
  cursor,
}: {
  events: TraceEvent[];
  cursor: number;
}) {
  const curRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    curRef.current?.scrollIntoView({ block: "nearest" });
  }, [cursor]);

  return (
    <div className="panel">
      <h3>Event log</h3>
      <div className="eventlog">
        {events.map((e, i) => (
          <div
            key={e.seq}
            ref={i === cursor ? curRef : undefined}
            className={`evt${i === cursor ? " cur" : ""}`}
            style={{ opacity: i > cursor ? 0.35 : 1 }}
          >
            <span className="eseq">{e.seq}</span>
            <span className={`etype ${category(e.type)}`}>{e.type}</span>
            <span className="emsg">{describe(e)}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
