import React from "react";
import { Tag } from "antd";
import {
  getSemanticColor,
  PROTOCOL_COLORS,
  SOURCE_FLAG_COLORS,
} from "../../../theme/semanticColors";

function capitalize(str: string) {
  if (!str) return "";
  return str.charAt(0).toUpperCase() + str.slice(1).toLowerCase();
}

export const SourceFlagTag: React.FC<{
  source?: string;
  kind?: "source" | "protocol";
}> = ({ source, kind }) => {
  if (!source) return null;
  const key = source.toLowerCase();
  const isProtocol = kind === "protocol";
  // Protocol tags always render lowercase with a fixed per-protocol color.
  const color = isProtocol
    ? (PROTOCOL_COLORS[key] ?? getSemanticColor(key))
    : (SOURCE_FLAG_COLORS[key] ?? getSemanticColor(key));
  const label = isProtocol ? key : capitalize(source);
  return (
    <Tag
      className="qip-solid-tag"
      style={{
        background: color,
        color: "#fff",
        border: "none",
      }}
    >
      {label}
    </Tag>
  );
};
