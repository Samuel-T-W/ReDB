import type { TracePlanNode } from "../../types/trace";

function Node({ node, activeId }: { node: TracePlanNode; activeId?: string }) {
  const active = node.id === activeId;
  return (
    <li>
      <div className={`plan-node ptype-${node.type}${active ? " active" : ""}`}>
        <div className="ptype-tag">{node.type.replace("_", " ").toLowerCase()}</div>
        <div className="plabel">{node.label}</div>
        {node.detail && <div className="pdetail">{node.detail}</div>}
      </div>
      {node.children.length > 0 && (
        <ul>
          {node.children.map((child) => (
            <Node key={child.id} node={child} activeId={activeId} />
          ))}
        </ul>
      )}
    </li>
  );
}

export default function PlanTree({
  plan,
  activeId,
}: {
  plan: TracePlanNode;
  activeId?: string;
}) {
  return (
    <div className="panel">
      <h3>Query plan</h3>
      <div className="tree-scroll">
        <ul className="tree">
          <Node node={plan} activeId={activeId} />
        </ul>
      </div>
    </div>
  );
}
