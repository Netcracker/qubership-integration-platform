import React from "react";
import { Tag } from "antd";
import { METHOD_COLORS } from "../../../theme/semanticColors.ts";

type HttpMethodProps = {
  value: unknown;
  width?: number;
};

/** Shared visual style for an HTTP-method badge, colored by method (GET/POST/...).
 *  Squarish, bold, uppercase, white label — the Swagger/OpenAPI method-badge idiom. */
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

export const HttpMethod: React.FC<HttpMethodProps> = (
  props: HttpMethodProps,
) => {
  const method = props.value as string | undefined;
  if (!method) return "-";

  const displayMethod = method.toUpperCase();

  return (
    <Tag
      className="qip-method-tag"
      style={{
        ...methodTagStyle(displayMethod),
        ...(props.width && { width: props.width }),
      }}
    >
      {displayMethod}
    </Tag>
  );
};
