import type { ReplayState } from "../../data/replay";
import type { QueryTrace } from "../../types/trace";

export default function StatsPanel({
  running,
  trace,
}: {
  running: ReplayState["running"];
  trace: QueryTrace;
}) {
  const total = trace.summary;
  return (
    <div className="panel">
      <h3>Live stats</h3>
      <div className="stats">
        <div className="stat">
          <div className="v accent">{running.pagesRead}</div>
          <div className="k">pages read from disk</div>
        </div>
        <div className="stat">
          <div className="v green">{running.bufferHits}</div>
          <div className="k">buffer hits</div>
        </div>
        <div className="stat">
          <div className="v amber">{running.evictions}</div>
          <div className="k">evictions</div>
        </div>
        <div className="stat">
          <div className="v">{running.recordsEmitted}</div>
          <div className="k">rows emitted</div>
        </div>
      </div>
      <div className="note">
        Final: {total.pagesRead} pages · {total.bufferHits} hits / {total.bufferMisses} misses ·{" "}
        {trace.run.indexed ? `${total.btreeNodeVisits} B+ tree nodes visited · ` : ""}
        {trace.run.wallClockMs} ms wall-clock
      </div>
    </div>
  );
}
