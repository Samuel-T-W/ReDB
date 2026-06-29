import { useNavigate } from "react-router-dom";
import { ITERATIONS } from "../data/iterations";

export default function IterationSlider({ currentId }: { currentId: number }) {
  const navigate = useNavigate();

  return (
    <div className="iter-steps" role="tablist" aria-label="Select iteration">
      {ITERATIONS.map((it) => {
        const active = it.id === currentId;
        return (
          <button
            key={it.id}
            role="tab"
            aria-selected={active}
            className={`iter-step${active ? " active" : ""} ${it.status}`}
            onClick={() => navigate(`/iteration/${it.id}`)}
          >
            <span className="step-dot" />
            <span className="step-meta">
              <span className="step-ver">{it.version}</span>
              <span className="step-name">{it.name}</span>
              {it.status === "planned" && <span className="step-planned">planned</span>}
            </span>
          </button>
        );
      })}
    </div>
  );
}
