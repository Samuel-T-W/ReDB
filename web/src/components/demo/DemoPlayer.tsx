import { useEffect, useMemo, useRef, useState } from "react";
import { generateTrace } from "../../data/generateTrace";
import { loadTrace } from "../../data/loadTrace";
import { replay } from "../../data/replay";
import { RANGE_PRESETS } from "../../data/presets";
import type { QueryTrace } from "../../types/trace";
import Controls, { type DemoSettings } from "./Controls";
import PlanTree from "./PlanTree";
import BufferPool from "./BufferPool";
import StatsPanel from "./StatsPanel";
import EventLog from "./EventLog";

const STEP_MS = 280;
const DEFAULT_SETTINGS: DemoSettings = {
  presetId: "mid",
  bufferSize: 5,
  indexed: true,
};

function paramsFor(settings: DemoSettings) {
  const preset = RANGE_PRESETS.find((p) => p.id === settings.presetId) ?? RANGE_PRESETS[0];
  return {
    start: preset.start,
    end: preset.end,
    bufferSize: settings.bufferSize,
    indexed: settings.indexed,
  };
}

export default function DemoPlayer() {
  const [settings, setSettings] = useState<DemoSettings>(DEFAULT_SETTINGS);
  const [cursor, setCursor] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [trace, setTrace] = useState<QueryTrace>(() => generateTrace(paramsFor(DEFAULT_SETTINGS)));
  const timer = useRef<number | null>(null);
  const initialLoad = useRef(true);

  const traceParams = useMemo(() => paramsFor(settings), [settings]);

  useEffect(() => {
    let cancelled = false;
    if (initialLoad.current) {
      initialLoad.current = false;
    } else {
      setTrace(generateTrace(traceParams));
    }
    loadTrace(traceParams).then(({ trace: loaded }) => {
      if (!cancelled) {
        setTrace(loaded);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [traceParams]);

  const lastIndex = trace.events.length - 1;

  // Reset playback whenever the trace changes.
  useEffect(() => {
    setCursor(0);
    setPlaying(false);
  }, [trace]);

  useEffect(() => {
    if (!playing) return;
    timer.current = window.setInterval(() => {
      setCursor((c) => {
        if (c >= lastIndex) {
          setPlaying(false);
          return c;
        }
        return c + 1;
      });
    }, STEP_MS);
    return () => {
      if (timer.current) window.clearInterval(timer.current);
    };
  }, [playing, lastIndex]);

  const state = useMemo(() => replay(trace, cursor), [trace, cursor]);

  const atEnd = cursor >= lastIndex;

  function handleSettings(next: DemoSettings) {
    setSettings(next);
  }

  function togglePlay() {
    if (atEnd) {
      setCursor(0);
      setPlaying(true);
    } else {
      setPlaying((p) => !p);
    }
  }

  return (
    <div>
      <div className="demo-grid">
        <div style={{ display: "flex", flexDirection: "column", gap: 18 }}>
          <Controls settings={settings} onChange={handleSettings} disabled={playing} />
          <PlanTree plan={trace.plan} activeId={state.activeOperatorId} />
        </div>
        <div style={{ display: "flex", flexDirection: "column", gap: 18 }}>
          <StatsPanel running={state.running} trace={trace} />
          <BufferPool
            frames={state.frames}
            touchedFrameId={state.touchedFrameId}
            evictingFrameId={state.evictingFrameId}
          />
          <EventLog events={trace.events} cursor={cursor} />
        </div>
      </div>

      <div className="transport">
        <button className="btn primary" onClick={togglePlay}>
          {playing ? "❚❚ Pause" : atEnd ? "↻ Replay" : "▶ Run"}
        </button>
        <button
          className="btn"
          onClick={() => {
            setPlaying(false);
            setCursor((c) => Math.max(0, c - 1));
          }}
          disabled={cursor === 0}
        >
          ◀ Step
        </button>
        <button
          className="btn"
          onClick={() => {
            setPlaying(false);
            setCursor((c) => Math.min(lastIndex, c + 1));
          }}
          disabled={atEnd}
        >
          Step ▶
        </button>
        <input
          className="scrubber"
          type="range"
          min={0}
          max={lastIndex}
          value={cursor}
          onChange={(e) => {
            setPlaying(false);
            setCursor(Number(e.target.value));
          }}
        />
        <span className="tcount">
          {cursor + 1} / {trace.events.length} events
        </span>
      </div>

      {state.results.length > 0 && (
        <div className="panel results">
          <h3>Results so far · {state.results.length} rows</h3>
          <table>
            <thead>
              <tr>
                <th>title</th>
                <th>name</th>
              </tr>
            </thead>
            <tbody>
              {state.results.map((r, i) => (
                <tr key={i}>
                  <td>{r.title}</td>
                  <td>{r.name}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
