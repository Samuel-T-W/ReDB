import type { ReplayState } from "../../data/replay";
import type { QueryTrace } from "../../types/trace";

export default function StatsPanel({
  running,
  trace,
  started,
}: {
  running: ReplayState["running"];
  trace: QueryTrace;
  started: boolean;
}) {
  const total = trace.summary;
  const show = (v: number) => (started ? v : "—");
  const idle = started ? "" : " idle";
  return (
    <div className="panel">
      <h3>Live stats</h3>
      <div className="stats">
        <div className={`stat${idle}`}>
          <div className="v accent">{show(running.pagesRead)}</div>
          <div className="k">pages read from disk</div>
        </div>
        <div className={`stat${idle}`}>
          <div className="v green">{show(running.bufferHits)}</div>
          <div className="k">buffer hits</div>
        </div>
        <div className={`stat${idle}`}>
          <div className="v amber">{show(running.evictions)}</div>
          <div className="k">evictions</div>
        </div>
        <div className={`stat${idle}`}>
          <div className="v">{show(running.recordsEmitted)}</div>
          <div className="k">rows emitted</div>
        </div>
      </div>
      {started ? (
        <div className="note">
          Final: {total.pagesRead} pages · {total.bufferHits} hits / {total.bufferMisses} misses ·{" "}
          {trace.run.indexed ? `${total.btreeNodeVisits} B+ tree nodes visited · ` : ""}
          {trace.run.wallClockMs} ms wall-clock
        </div>
      ) : (
        <div className="note">Press ▶ Run to execute the query and watch these fill in.</div>
      )}
    </div>
  );
}
