import type { FrameView } from "../../data/replay";

export default function BufferPool({
  frames,
  touchedFrameId,
  evictingFrameId,
}: {
  frames: (FrameView | null)[];
  touchedFrameId?: number;
  evictingFrameId?: number;
}) {
  return (
    <div className="panel">
      <h3>Buffer pool · {frames.length} frames</h3>
      <div className="frames">
        {frames.map((f, i) => {
          if (i === evictingFrameId) {
            return (
              <div key={i} className="frame evicting">
                <div className="ffile">evicting…</div>
              </div>
            );
          }
          if (!f) {
            return (
              <div key={i} className="frame empty">
                free
              </div>
            );
          }
          const cls = i === touchedFrameId ? "frame touched" : "frame";
          return (
            <div key={i} className={cls}>
              <div className="ffile">{f.fileId}</div>
              <div className="fpage">p{f.pageId}</div>
              <div className="fmeta">
                frame {i}
                {f.pinCount != null ? ` · pin ${f.pinCount}` : ""}
                {f.dirty ? " · dirty" : ""}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
