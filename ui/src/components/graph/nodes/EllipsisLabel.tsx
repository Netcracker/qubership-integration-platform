import React, { useRef } from "react";

export function EllipsisLabel({
  text,
  style,
  className,
}: {
  text: string;
  style?: React.CSSProperties;
  className?: string;
}) {
  const Comp = "span";
  const ref = useRef<HTMLElement>(null);

  return (
    <Comp
      ref={ref}
      className={className}
      style={{
        display: "block",
        whiteSpace: "nowrap",
        overflow: "hidden",
        textOverflow: "ellipsis",
        minWidth: 0,
        maxWidth: "100%",
        ...style,
      }}
    >
      {text}
    </Comp>
  );
}
