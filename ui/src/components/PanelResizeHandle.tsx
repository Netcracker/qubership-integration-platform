import React, { useCallback, useEffect, useRef, useState } from "react";
import styles from "./PanelResizeHandle.module.css";

type ResizeDirection = "left" | "right";

export type PanelResizeHandleProps = {
  direction: ResizeDirection;
  onResize: (deltaX: number) => void;
  onResizeEnd?: () => void;
};

export const PanelResizeHandle: React.FC<PanelResizeHandleProps> = ({
  direction,
  onResize,
  onResizeEnd,
}) => {
  const [isDragging, setIsDragging] = useState(false);
  const lastX = useRef(0);

  const handleMouseDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    setIsDragging(true);
    lastX.current = e.clientX;
  }, []);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      const step = 10;
      if (e.key === "ArrowLeft") {
        e.preventDefault();
        onResize(direction === "right" ? step : -step);
      } else if (e.key === "ArrowRight") {
        e.preventDefault();
        onResize(direction === "right" ? -step : step);
      }
    },
    [direction, onResize],
  );

  useEffect(() => {
    if (!isDragging) return;

    const handleMouseMove = (e: MouseEvent) => {
      const deltaX = e.clientX - lastX.current;
      lastX.current = e.clientX;
      const effectiveDelta = direction === "right" ? -deltaX : deltaX;
      onResize(effectiveDelta);
    };

    const handleMouseUp = () => {
      setIsDragging(false);
      onResizeEnd?.();
    };

    document.addEventListener("mousemove", handleMouseMove);
    document.addEventListener("mouseup", handleMouseUp);
    document.body.style.cursor = "col-resize";
    document.body.style.userSelect = "none";

    return () => {
      document.removeEventListener("mousemove", handleMouseMove);
      document.removeEventListener("mouseup", handleMouseUp);
      document.body.style.cursor = "";
      document.body.style.userSelect = "";
    };
  }, [isDragging, direction, onResize, onResizeEnd]);

  return (
    <button
      type="button"
      aria-label="Resize panel"
      className={`${styles.handle} ${isDragging ? styles.dragging : ""}`}
      onMouseDown={handleMouseDown}
      onKeyDown={handleKeyDown}
    >
      <span className={styles.line} aria-hidden />
    </button>
  );
};
