import { Element } from "../../api/apiTypes.ts";
import React from "react";

const DraggableElement: React.FC<{ element: Element }> = ({ element }) => {
  const onDragStart = (event: React.DragEvent<HTMLDivElement>) => {
    event.dataTransfer.setData("application/reactflow", element.name);
    event.dataTransfer.effectAllowed = "move";
  };

  return (
    <div draggable onDragStart={onDragStart}>
      {element.title}
    </div>
  );
};

export default DraggableElement;
