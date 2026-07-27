import { LibraryElement } from "../../api/apiTypes.ts";
import React from "react";
import styles from "./DraggableElement.module.css";

interface DraggableElementProps {
  element: LibraryElement;
}

const DraggableElement: React.FC<DraggableElementProps> = ({ element }) => {
  const onDragStart = (event: React.DragEvent<HTMLDivElement>) => {
    event.dataTransfer.setData("application/reactflow", element.name);
    event.dataTransfer.effectAllowed = "move";
  };

  return (
    <>
      <div draggable onDragStart={onDragStart} className={styles.draggableContainer} />
      <div>{element.title}</div>
    </>
  );
};

export default DraggableElement;
