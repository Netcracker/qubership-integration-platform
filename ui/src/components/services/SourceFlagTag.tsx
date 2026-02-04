import React from "react";
import { Tag } from "antd";
import { getSemanticColor } from "../../theme/semanticColors";

const displayNameOverrides: Record<string, string> = {
  grpc: "gRPC",
  graphql: "GraphQL",
  asyncapi: "AsyncAPI",
};

function capitalize(str: string) {
  if (!str) return "";
  return str.charAt(0).toUpperCase() + str.slice(1).toLowerCase();
}

export const SourceFlagTag: React.FC<{
  source?: string;
  toUpperCase?: boolean;
}> = ({ source, toUpperCase }) => {
  if (!source) return null;
  const key = source.toLowerCase();
  const color = getSemanticColor(key);
  const label = toUpperCase
    ? source.toUpperCase()
    : displayNameOverrides[key] || capitalize(source);
  return (
    <Tag
      style={{
        background: color,
        color: "rgba(0, 0, 0, 0.88)",
        borderRadius: 12,
        border: "none",
      }}
    >
      {label}
    </Tag>
  );
};
