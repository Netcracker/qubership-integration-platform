import { Handle, Node, Position } from "@xyflow/react";
import { ChainGraphNodeData } from "../../../hooks/graph/useChainGraph.tsx";

export const DefaultNode = ({ data }: Node<ChainGraphNodeData>) => {
  const isHorizontal = data.direction === "RIGHT";

  return (
    <>
      {isHorizontal ? (
        <>
          <Handle type="target" position={Position.Left} />
          <div>{data.label}</div>
          <Handle type="source" position={Position.Right} />
        </>
      ) : (
        <>
          <Handle type="target" position={Position.Top} />
          <div>{data.label}</div>
          <Handle type="source" position={Position.Bottom} />
        </>
      )}
    </>
  );
};
