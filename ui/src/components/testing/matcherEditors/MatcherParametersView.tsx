import React from "react";
import { Typography } from "antd";
import { TestingNamedParameter } from "../../../api/apiTypes.ts";

export type MatcherParametersViewProps = {
  parameters: TestingNamedParameter[] | null;
};

/** A long value would run into the next column, so every line is cut to the cell width. */
const LINE_STYLE: React.CSSProperties = {
  display: "block",
  overflow: "hidden",
  textOverflow: "ellipsis",
  whiteSpace: "nowrap",
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
    const value = sorted[0][1].join(", ");
    return (
      <span style={LINE_STYLE} title={value}>
        {value}
      </span>
    );
  }
  return (
    <span style={{ display: "block", minWidth: 0 }}>
      {sorted.map(([name, values]) => {
        const line = `${name}: ${values.join(", ")}`;
        return (
          <span key={name} style={LINE_STYLE} title={line}>
            {line}
          </span>
        );
      })}
    </span>
  );
};
