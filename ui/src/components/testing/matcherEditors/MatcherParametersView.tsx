import React from "react";
import { Typography } from "antd";
import { TestingMatcherParameter } from "../../../api/apiTypes.ts";

export type MatcherParametersViewProps = {
  parameters: TestingMatcherParameter[] | null;
};

/** A single parameter shows its value alone; several show `name: value` per line. */
export const MatcherParametersView: React.FC<MatcherParametersViewProps> = ({
  parameters,
}) => {
  const entries = new Map<string, string[]>();
  for (const parameter of parameters ?? []) {
    const values = entries.get(parameter.name);
    if (values) {
      values.push(parameter.value);
    } else {
      entries.set(parameter.name, [parameter.value]);
    }
  }
  const sorted = [...entries].sort(([a], [b]) => a.localeCompare(b));

  if (sorted.length === 0) {
    return <Typography.Text type="secondary">Not set</Typography.Text>;
  }
  if (sorted.length === 1) {
    return <span>{sorted[0][1].join(", ")}</span>;
  }
  return (
    <span>
      {sorted.map(([name, values]) => (
        <span key={name} style={{ display: "block" }}>
          <Typography.Text type="secondary">{name}: </Typography.Text>
          {values.join(", ")}
        </span>
      ))}
    </span>
  );
};
