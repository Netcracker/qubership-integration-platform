import { ChildElement, LibraryElement } from "../../api/apiTypes.ts";
import React from "react";

interface DraggableElementProps {
  element: LibraryElement;
}

export function isElement(obj: unknown): obj is LibraryElement {
  return typeof obj === "object" && obj !== null && "title" in obj;
}

export function isChildElement(obj: unknown): obj is ChildElement {
  return typeof obj === "object" && obj !== null && "name" in obj;
}

const DraggableElement: React.FC<DraggableElementProps> = ({ element }) => {
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
