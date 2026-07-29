import { useEffect, useRef } from "react";
import { Link, useLocation } from "react-router-dom";
import { ITERATIONS, LATEST_IMPLEMENTED } from "../data/iterations";
import ThemeToggle from "./ThemeToggle";

// What the engine is reaching for, as opposed to what any one iteration ships.
const AIMS = [
  {
    n: "01",
    title: "Read-only, on purpose",
    body: "No transactions, no recovery, no live inserts or updates. Dropping the write path is what buys the depth: the work goes further into how a read query gets planned, executed, and paid for in page I/O, instead of spreading thin across a whole database.",
  },
  {
    n: "02",
    title: "General queries, not one fixed plan",
    body: "Today the engine runs a single hardcoded three-table block nested-loop plan. The target is a real query surface: a SQL parser over a limited clause subset feeding a cost-based planner that picks the access path - sequential scan or B+ tree - and the join order itself.",
  },
  {
    n: "03",
    title: "Built from scratch in Java",
    body: "Pages and record slots, the buffer pool and its LRU eviction, the B+ tree and its splits, and the pull-based open / next / close operators are all hand-written. Nothing underneath is borrowed from an existing database.",
  },
  {
    n: "04",
    title: "Writes exist, but only at build time",
    body: "B+ tree insert and the heap-file loader are construction-time tooling: pre_process uses them to turn CSVs into tables and indexes before any query runs. No query can reach them.",
  },
];

const BADGES = [
  "Java 21",
  "4 KB pages",
  "LRU buffer pool",
  "B+ tree index",
  "block nested-loop joins",
  "no database dependencies",
];

export default function Home() {
  const location = useLocation();
  const roadmap = useRef<HTMLElement>(null);

  // Other pages link here with state instead of a fragment, since hash routing
  // already owns the URL fragment.
  useEffect(() => {
    const state = location.state as { scrollTo?: string } | null;
    if (state?.scrollTo === "roadmap") {
      roadmap.current?.scrollIntoView?.({ behavior: "smooth", block: "start" });
    }
  }, [location.state]);

  return (
    <div className="app-shell">
      <nav className="topbar">
        <Link to="/" className="brand">
          ReDB
        </Link>
        <div className="topbar-right">
          <Link to={`/iteration/${LATEST_IMPLEMENTED.id}`} className="topbar-link">
            Live demo →
          </Link>
          <ThemeToggle />
        </div>
      </nav>

      <header className="hero home-hero">
        <p className="eyebrow">Storage + query engine · Java</p>
        <h1>A read-only query engine, built from scratch.</h1>
        <p className="lede">
          ReDB stores tables as 4 KB pages, caches them in its own buffer pool, indexes them with a
          B+ tree, and runs queries through pull-based operators - all written by hand in Java. It
          is deliberately scoped read-only, so the work goes deep on query planning and execution
          performance rather than wide on transactions and recovery.
        </p>
        <div className="cta">
          <Link className="btn primary" to={`/iteration/${LATEST_IMPLEMENTED.id}`}>
            Open the live demo →
          </Link>
          <a
            className="btn"
            href="https://github.com/Samuel-T-W/ReDB"
            target="_blank"
            rel="noreferrer"
          >
            Source ↗
          </a>
        </div>
        <div className="badge-row">
          {BADGES.map((b) => (
            <span className="badge" key={b}>
              {b}
            </span>
          ))}
        </div>
      </header>

      <main className="iter-main">
        <section className="home-section" aria-labelledby="aim-heading">
          <p className="eyebrow">The aim</p>
          <h2 id="aim-heading">What ReDB is trying to be</h2>
          <p className="section-sub">
            A general read-only query database: you hand it a query, it decides how to answer it,
            and every layer it uses to get there is visible. Building the read path properly is a
            deeper problem than it looks, and it is the whole project.
          </p>
          <div className="cards">
            {AIMS.map((aim) => (
              <div className="card" key={aim.n}>
                <div className="n">{aim.n}</div>
                <h3>{aim.title}</h3>
                <p>{aim.body}</p>
              </div>
            ))}
          </div>
        </section>

        <section className="home-section" ref={roadmap} aria-labelledby="roadmap-heading">
          <p className="eyebrow">Roadmap</p>
          <h2 id="roadmap-heading">Planned work</h2>
          <p className="section-sub">
            ReDB started as a UMass CS 645 storage-engine project and continues as a personal
            learning project. Each iteration adds a layer you can watch in the demo.
          </p>
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
        </section>
      </main>
    </div>
  );
}
