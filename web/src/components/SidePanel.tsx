import type { ReactNode } from "react";

export default function SidePanel({
  title,
  onClose,
  children,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
}) {
  return (
    <>
      <div className="drawer-backdrop" onClick={onClose} />
      <aside className="side-panel" role="dialog" aria-label={title}>
        <div className="side-head">
          <h3>{title}</h3>
          <button className="side-close" onClick={onClose} aria-label="Close panel">
            ✕
          </button>
        </div>
        <div className="side-body">{children}</div>
      </aside>
    </>
  );
}
