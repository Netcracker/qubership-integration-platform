import React from "react";
import { Tag } from "antd";

export const EnabledTag: React.FC<{ enabled: boolean }> = ({ enabled }) => (
  <Tag color={enabled ? "green" : "default"}>
    {enabled ? "Enabled" : "Disabled"}
  </Tag>
);

export const ReadinessTag: React.FC<{ ready: boolean }> = ({ ready }) => (
  <Tag color={ready ? "blue" : "warning"}>{ready ? "Ready" : "Incomplete"}</Tag>
);
