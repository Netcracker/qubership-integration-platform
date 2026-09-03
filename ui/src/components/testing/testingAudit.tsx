import React from "react";
import { Typography } from "antd";
import { formatTimestamp, PLACEHOLDER } from "../../misc/format-utils.ts";

export const EMPTY = (
  <Typography.Text type="secondary">{PLACEHOLDER}</Typography.Text>
);

/** Renders an audit pair as "when by whom", dropping the author when there is none. */
export function formatAudit(
  user: string | null,
  timestamp: string | null,
): React.ReactNode {
  if (!timestamp) {
    return EMPTY;
  }
  const author = user ? ` by ${user}` : "";
  return `${formatTimestamp(timestamp)}${author}`;
}
