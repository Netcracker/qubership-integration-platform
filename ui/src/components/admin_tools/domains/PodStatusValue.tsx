import { Tag } from "antd";
import React from "react";

export type PodStatusValueProps = {
  ready: boolean;
};

function getStatusText(ready: boolean): string {
  return ready ? "Ready" : "Not Ready";
}

function getStatusColor(ready: boolean) {
  return ready ? "green" : "gold";
}

export const PodStatusValue: React.FC<PodStatusValueProps> = ({
  ready,
}) => {
  return (
    <Tag color={getStatusColor(ready)} variant={"outlined"}>
      {getStatusText(ready)}
    </Tag>
  );
};
