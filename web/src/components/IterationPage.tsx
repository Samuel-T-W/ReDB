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
  // Sliding between iterations keeps this component mounted, so the chosen view
  // is remembered against the iteration it was chosen on. Landing on a
  // different one opens on that iteration's own first view rather than
  // inheriting a tab from the last.
  const [chosen, setChosen] = useState<{ iterationId: number; tab: Tab } | null>(null);

  if (!iteration) {
    return <Navigate to={`/iteration/${LATEST_IMPLEMENTED.id}`} replace />;
  }

  // A planned iteration has nothing to simulate and no numbers, so it is left
  // with How it works alone — and a lone tab is a dead control, so the strip
  // only appears once there is more than one view to switch between.
  const views: { id: Tab; label: string }[] = [
    ...(iteration.status === "implemented" ? [{ id: "sim" as const, label: "Simulation" }] : []),
    { id: "explain", label: "How it works" },
    ...(iteration.performance ? [{ id: "perf" as const, label: "Performance" }] : []),
  ];
  const tabbed = views.length > 1;
  const active =
    chosen?.iterationId === iteration.id && views.some((v) => v.id === chosen.tab)
      ? chosen.tab
      : views[0].id;

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
          {tabbed && (
            <div className="iter-tabs" role="tablist" aria-label="Iteration view">
              {views.map((v) => (
                <button
                  key={v.id}
                  role="tab"
                  id={`tab-${v.id}`}
                  aria-selected={active === v.id}
                  aria-controls={`panel-${v.id}`}
                  className={`iter-tab${active === v.id ? " on" : ""}`}
                  onClick={() => setChosen({ iterationId: iteration.id, tab: v.id })}
                >
                  {v.label}
                </button>
              ))}
            </div>
          )}
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
        {iteration.status === "implemented" && (
          <div id="panel-sim" role="tabpanel" aria-labelledby="tab-sim" hidden={active !== "sim"}>
            <DemoPlayer />
          </div>
        )}

        <div
          id="panel-explain"
          role={tabbed ? "tabpanel" : undefined}
          aria-labelledby={tabbed ? "tab-explain" : undefined}
          hidden={active !== "explain"}
        >
          {iteration.status === "planned" && (
            <div className="planned-note">
              <p>
                {iteration.plannedSummary ??
                  "This iteration is on the roadmap. The simulation appears here once it ships."}
              </p>
              <Link to="/" state={{ scrollTo: "roadmap" }} className="btn">
                See the roadmap →
              </Link>
            </div>
          )}
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
          <div id="panel-perf" role="tabpanel" aria-labelledby="tab-perf" hidden={active !== "perf"}>
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
