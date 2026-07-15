import React from "react";
import { Tag } from "antd";
import { METHOD_COLORS } from "../../../theme/semanticColors.ts";

type MethodBadgeProps = {
  value: unknown;
  minWidth?: number;
};

/** Shared visual style for an operation-method badge, colored by method. Covers
 *  any protocol's method — HTTP (GET/POST/...) and AsyncAPI (PUBLISH/SUBSCRIBE).
 *  Squarish, bold, uppercase, white label — the Swagger method-badge idiom. */
export function methodTagStyle(method: string): React.CSSProperties {
  const color = METHOD_COLORS[method.toUpperCase()] || "#d9d9d9";
  return {
    background: color,
    color: "#fff",
    border: "none",
    borderRadius: 4,
    padding: "1px 8px",
    textAlign: "center",
    // antd's Tag is a flex container, so center the label on the main axis too
    // (textAlign alone leaves it left-aligned in a fixed-width badge).
    justifyContent: "center",
    fontWeight: 600,
    fontSize: 13,
    letterSpacing: 0.3,
  };
}

export const MethodBadge: React.FC<MethodBadgeProps> = (
  props: MethodBadgeProps,
) => {
  const method = props.value as string | undefined;
  if (!method) return "-";

  const displayMethod = method.toUpperCase();

  return (
    <Tag
      className="qip-method-tag"
      style={{
        ...methodTagStyle(displayMethod),
        ...(props.minWidth && { minWidth: props.minWidth }),
      }}
    >
      {displayMethod}
    </Tag>
  );
};
