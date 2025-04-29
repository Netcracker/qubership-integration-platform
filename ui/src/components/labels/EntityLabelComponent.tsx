import { Tag } from "antd";
import React from "react";
import { EntityLabel } from "../../api/apiTypes.ts";

export const EntityLabelComponent: React.FC<EntityLabel> = (
  label: EntityLabel,
) => {
  const color = label.technical ? "blue" : "default";
  return (
    <Tag color={color} key={label?.name}>
      {label?.name}
    </Tag>
  );
};
