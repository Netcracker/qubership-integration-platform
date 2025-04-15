import { Handle, Position } from "@xyflow/react";

export const DefaultNode = ({ data }: any) => {
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
