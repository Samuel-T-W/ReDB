import { Link } from "react-router-dom";
import { ITERATIONS } from "../data/iterations";

// Navigation between iterations, not a tab set: each step goes to its own
// route. The home page renders it with no current iteration, so no step is
// marked as the one you are on.
export default function IterationSlider({ currentId }: { currentId?: number }) {
  return (
    <nav className="iter-steps" aria-label="Engine iterations">
      {ITERATIONS.map((it) => {
        const active = it.id === currentId;
        return (
          <Link
            key={it.id}
            to={`/iteration/${it.id}`}
            aria-current={active ? "page" : undefined}
            className={`iter-step${active ? " active" : ""} ${it.status}`}
          >
            <span className="step-dot" />
            <span className="step-meta">
              <span className="step-ver">{it.version}</span>
              <span className="step-name">{it.name}</span>
              {it.status === "planned" && <span className="step-planned">planned</span>}
            </span>
          </Link>
        );
      })}
    </nav>
  );
}
