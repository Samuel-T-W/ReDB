import { BUFFER_SIZES, RANGE_PRESETS } from "../../data/presets";

export interface DemoSettings {
  presetId: string;
  bufferSize: number;
  indexed: boolean;
}

export default function Controls({
  settings,
  onChange,
  disabled,
}: {
  settings: DemoSettings;
  onChange: (next: DemoSettings) => void;
  disabled: boolean;
}) {
  return (
    <div className="panel">
      <h3>Run query</h3>
      <div className="controls">
        <div className="control-group">
          <label>title range</label>
          <div className="chips">
            {RANGE_PRESETS.map((p) => (
              <button
                key={p.id}
                className={`chip${settings.presetId === p.id ? " active" : ""}`}
                onClick={() => onChange({ ...settings, presetId: p.id })}
                disabled={disabled}
                title={p.hint}
              >
                {p.label}
              </button>
            ))}
          </div>
        </div>

        <div className="control-group">
          <label>buffer size (frames)</label>
          <div className="chips">
            {BUFFER_SIZES.map((b) => (
              <button
                key={b}
                className={`chip${settings.bufferSize === b ? " active" : ""}`}
                onClick={() => onChange({ ...settings, bufferSize: b })}
                disabled={disabled}
              >
                {b}
              </button>
            ))}
          </div>
        </div>

        <div className="control-group">
          <label>access method</label>
          <div className="toggle">
            <button
              className={!settings.indexed ? "on" : ""}
              onClick={() => onChange({ ...settings, indexed: false })}
              disabled={disabled}
            >
              full scan
            </button>
            <button
              className={settings.indexed ? "on" : ""}
              onClick={() => onChange({ ...settings, indexed: true })}
              disabled={disabled}
            >
              B+ tree index
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
