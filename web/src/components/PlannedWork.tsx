import { Link } from "react-router-dom";
import { ITERATIONS, LATEST_IMPLEMENTED } from "../data/iterations";

export default function PlannedWork() {
  return (
    <div className="app-shell">
      <nav className="topbar">
        <Link to={`/iteration/${LATEST_IMPLEMENTED.id}`} className="brand">
          ReDB
        </Link>
        <Link to={`/iteration/${LATEST_IMPLEMENTED.id}`} className="topbar-link">
          ← Back to demo
        </Link>
      </nav>

      <header className="iter-header">
        <span className="eyebrow">Roadmap</span>
        <h1>Planned work</h1>
        <p className="iter-tagline">
          ReDB started as a UMass CS 645 storage-engine project and continues as a personal
          learning project. Each iteration adds a layer you can watch in the demo.
        </p>
      </header>

      <main className="iter-main">
        <ol className="planned-list">
          {ITERATIONS.map((it) => (
            <li className={`planned-item ${it.status}`} key={it.id}>
              <div className="pi-marker">
                <span className="dot" />
              </div>
              <div className="pi-body">
                <div className="pi-head">
                  <span className="pi-ver">{it.version}</span>
                  <h3>{it.name}</h3>
                  <span className={`status-badge ${it.status}`}>{it.status}</span>
                </div>
                <p>{it.plannedSummary ?? it.tagline}</p>
                {it.status === "implemented" ? (
                  <Link to={`/iteration/${it.id}`} className="btn primary">
                    Open live demo →
                  </Link>
                ) : (
                  <Link to={`/iteration/${it.id}`} className="btn">
                    Preview page
                  </Link>
                )}
              </div>
            </li>
          ))}
        </ol>
      </main>
    </div>
  );
}
