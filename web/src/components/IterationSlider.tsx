import { useNavigate } from "react-router-dom";
import { ITERATIONS } from "../data/iterations";

export default function IterationSlider({ currentId }: { currentId: number }) {
  const navigate = useNavigate();
  const min = ITERATIONS[0].id;
  const max = ITERATIONS[ITERATIONS.length - 1].id;
  const range = max - min;

  return (
    <div className="iter-slider">
      <div className="iter-slider-rail">
        <input
          type="range"
          min={min}
          max={max}
          step={1}
          value={currentId}
          onChange={(e) => navigate(`/iteration/${e.target.value}`)}
          aria-label="Select iteration"
        />
      </div>
      <div className="iter-ticks">
        {ITERATIONS.map((it) => (
          <button
            key={it.id}
            className={`iter-tick${it.id === currentId ? " active" : ""}`}
            onClick={() => navigate(`/iteration/${it.id}`)}
            style={{
              left: `${range === 0 ? 0 : ((it.id - min) / range) * 100}%`,
            }}
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
