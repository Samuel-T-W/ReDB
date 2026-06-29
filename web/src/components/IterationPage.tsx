import { useState } from "react";
import { Link, Navigate, useParams } from "react-router-dom";
import { getIteration, LATEST_IMPLEMENTED } from "../data/iterations";
import IterationSlider from "./IterationSlider";
import DemoPlayer from "./demo/DemoPlayer";
import SidePanel from "./SidePanel";

type Reveal = "none" | "explain" | "perf";

export default function IterationPage() {
  const { id } = useParams();
  const iteration = getIteration(Number(id));
  const [reveal, setReveal] = useState<Reveal>("none");

  if (!iteration) {
    return <Navigate to={`/iteration/${LATEST_IMPLEMENTED.id}`} replace />;
  }

  function toggle(panel: Reveal) {
    setReveal((current) => (current === panel ? "none" : panel));
  }

  return (
    <div className="app-shell">
      <nav className="topbar">
        <Link to={`/iteration/${LATEST_IMPLEMENTED.id}`} className="brand">
          ReDB
        </Link>
        <IterationSlider currentId={iteration.id} />
        <Link to="/planned" className="topbar-link">
          Planned work →
        </Link>
      </nav>

      <header className="iter-header">
        <div className="iter-title-row">
          <span className="iter-ver">{iteration.version}</span>
          <h1>{iteration.name}</h1>
          <span className={`status-badge ${iteration.status}`}>{iteration.status}</span>
        </div>
        <p className="iter-tagline">{iteration.tagline}</p>
        <div className="iter-actions">
          <button
            className={`btn action-btn${reveal === "explain" ? " active" : ""}`}
            onClick={() => toggle("explain")}
          >
            How it works
          </button>
          {iteration.performance && (
            <button
              className={`btn action-btn${reveal === "perf" ? " active" : ""}`}
              onClick={() => toggle("perf")}
            >
              Performance
            </button>
          )}
          <a
            className="btn action-btn"
            href="https://github.com/Samuel-T-W/ReDB"
            target="_blank"
            rel="noreferrer"
          >
            Source ↗
          </a>
        </div>
      </header>

      <main className="iter-main">
        <div className="iter-body">
          <div className="iter-content">
            {iteration.status === "implemented" ? (
              <DemoPlayer />
            ) : (
              <div className="planned-placeholder">
                <h2>Not built yet</h2>
                <p>
                  {iteration.plannedSummary ??
                    "This iteration is on the roadmap. The live demo appears here once it ships."}
                </p>
                <Link to="/planned" className="btn primary">
                  See planned work
                </Link>
              </div>
            )}
          </div>

          {reveal === "explain" && (
            <SidePanel
              key="explain"
              title={`How it works · ${iteration.version}`}
              onClose={() => setReveal("none")}
            >
              <div className="explain-list">
                {iteration.explanation.map((s) => (
                  <div className="explain-card" key={s.title}>
                    <h4>{s.title}</h4>
                    <p>{s.body}</p>
                  </div>
                ))}
              </div>
            </SidePanel>
          )}

          {reveal === "perf" && iteration.performance && (
            <SidePanel
              key="perf"
              title={`Performance · ${iteration.version}`}
              onClose={() => setReveal("none")}
            >
              <p className="perf-blurb">{iteration.performance.blurb}</p>
              <p className="perf-headline">{iteration.performance.headline}</p>
              <div className="perf-highlights">
                {iteration.performance.highlights.map((item) => (
                  <div className="perf-highlight" key={item.label}>
                    <div className={`perf-highlight-value ${item.tone ?? "accent"}`}>{item.value}</div>
                    <div className="perf-highlight-label">{item.label}</div>
                  </div>
                ))}
              </div>
              <table className="perf-table">
                <thead>
                  <tr>
                    <th>concurrency</th>
                    <th>throughput</th>
                    <th>mean latency</th>
                    <th>peak rss</th>
                  </tr>
                </thead>
                <tbody>
                  {iteration.performance.rows.map((r) => (
                    <tr key={r.concurrency}>
                      <td>{r.concurrency}</td>
                      <td>{r.throughput}</td>
                      <td>{r.latency}</td>
                      <td>{r.rss}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <ul className="perf-takeaways">
                {iteration.performance.takeaways.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
              <a
                className="btn perf-link"
                href={iteration.performance.analysisHref}
                target="_blank"
                rel="noreferrer"
              >
                {iteration.performance.analysisLabel} ↗
              </a>
              <p className="note">{iteration.performance.note}</p>
            </SidePanel>
          )}
        </div>
      </main>
    </div>
  );
}
