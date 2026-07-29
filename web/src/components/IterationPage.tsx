import { useState } from "react";
import { Link, Navigate, useParams } from "react-router-dom";
import { getIteration, LATEST_IMPLEMENTED } from "../data/iterations";
import IterationSlider from "./IterationSlider";
import DemoPlayer from "./demo/DemoPlayer";
import ThemeToggle from "./ThemeToggle";

type Tab = "sim" | "explain" | "perf";

export default function IterationPage() {
  const { id } = useParams();
  const iteration = getIteration(Number(id));
  const [tab, setTab] = useState<Tab>("sim");

  if (!iteration) {
    return <Navigate to={`/iteration/${LATEST_IMPLEMENTED.id}`} replace />;
  }

  const tabs: { id: Tab; label: string }[] = [
    { id: "sim", label: "Simulation" },
    { id: "explain", label: "How it works" },
    ...(iteration.performance ? [{ id: "perf" as const, label: "Performance" }] : []),
  ];

  return (
    <div className="app-shell">
      <nav className="topbar">
        <Link to="/" className="brand">
          ReDB
        </Link>
        <IterationSlider currentId={iteration.id} />
        <div className="topbar-right">
          <Link to="/" state={{ scrollTo: "roadmap" }} className="topbar-link">
            Planned work →
          </Link>
          <ThemeToggle />
        </div>
      </nav>

      <header className="iter-header">
        <div className="iter-title-row">
          <span className="iter-ver">{iteration.version}</span>
          <h1>{iteration.name}</h1>
          <span className={`status-badge ${iteration.status}`}>{iteration.status}</span>
        </div>
        <p className="iter-tagline">{iteration.tagline}</p>
        <div className="iter-actions">
          <div className="iter-tabs" role="tablist" aria-label="Iteration view">
            {tabs.map((t) => (
              <button
                key={t.id}
                role="tab"
                id={`tab-${t.id}`}
                aria-selected={tab === t.id}
                aria-controls={`panel-${t.id}`}
                className={`iter-tab${tab === t.id ? " on" : ""}`}
                onClick={() => setTab(t.id)}
              >
                {t.label}
              </button>
            ))}
          </div>
          <a
            className="btn action-btn iter-source"
            href="https://github.com/Samuel-T-W/ReDB"
            target="_blank"
            rel="noreferrer"
          >
            Source ↗
          </a>
        </div>
      </header>

      <main className="iter-main">
        {/* Every panel stays mounted so switching tabs never resets a replay
            that is part-way through. */}
        <div id="panel-sim" role="tabpanel" aria-labelledby="tab-sim" hidden={tab !== "sim"}>
          {iteration.status === "implemented" ? (
            <DemoPlayer />
          ) : (
            <div className="planned-placeholder">
              <h2>Not built yet</h2>
              <p>
                {iteration.plannedSummary ??
                  "This iteration is on the roadmap. The simulation appears here once it ships."}
              </p>
              <Link to="/" state={{ scrollTo: "roadmap" }} className="btn primary">
                See planned work
              </Link>
            </div>
          )}
        </div>

        <div
          id="panel-explain"
          role="tabpanel"
          aria-labelledby="tab-explain"
          hidden={tab !== "explain"}
        >
          <div className="explain-list">
            {iteration.explanation.map((s) => (
              <div className="explain-card" key={s.title}>
                <h4>{s.title}</h4>
                <p>{s.body}</p>
              </div>
            ))}
          </div>
        </div>

        {iteration.performance && (
          <div id="panel-perf" role="tabpanel" aria-labelledby="tab-perf" hidden={tab !== "perf"}>
            <p className="perf-headline">{iteration.performance.headline}</p>
            <p className="perf-blurb">{iteration.performance.blurb}</p>
            <div className="perf-highlights">
              {iteration.performance.highlights.map((item) => (
                <div className="perf-highlight" key={item.label}>
                  <div className={`perf-highlight-value ${item.tone ?? "accent"}`}>
                    {item.value}
                  </div>
                  <div className="perf-highlight-label">{item.label}</div>
                </div>
              ))}
            </div>
            <div className="perf-split">
              <section className="panel">
                <h3>By worker count</h3>
                <table className="perf-table">
                  <thead>
                    <tr>
                      <th>workers</th>
                      <th>throughput</th>
                      <th>mean latency</th>
                      <th>peak memory</th>
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
                <p className="note">{iteration.performance.note}</p>
              </section>
              <section className="panel">
                <h3>Takeaways</h3>
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
              </section>
            </div>
          </div>
        )}
      </main>
    </div>
  );
}
