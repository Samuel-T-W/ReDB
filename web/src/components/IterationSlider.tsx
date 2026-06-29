import { useNavigate } from "react-router-dom";
import { ITERATIONS } from "../data/iterations";

export default function IterationSlider({ currentId }: { currentId: number }) {
  const navigate = useNavigate();
  const min = ITERATIONS[0].id;
  const max = ITERATIONS[ITERATIONS.length - 1].id;

  return (
    <div className="iter-slider">
      <input
        type="range"
        min={min}
        max={max}
        step={1}
        value={currentId}
        onChange={(e) => navigate(`/iteration/${e.target.value}`)}
        aria-label="Select iteration"
      />
      <div className="iter-ticks">
        {ITERATIONS.map((it) => (
          <button
            key={it.id}
            className={`iter-tick${it.id === currentId ? " active" : ""}`}
            onClick={() => navigate(`/iteration/${it.id}`)}
          >
            <span className="tv">{it.version}</span>
            <span className="tn">{it.name}</span>
            {it.status === "planned" && <span className="tplanned">planned</span>}
          </button>
        ))}
      </div>
    </div>
  );
}
