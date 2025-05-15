import { Flex } from "antd";
import React from "react";
import { EntityLabel } from "../../api/apiTypes.ts";
import { EntityLabelComponent } from "./EntityLabelComponent.tsx";

type EntityLabelsProps = {
  labels: EntityLabel[];
};

export const EntityLabels: React.FC<EntityLabelsProps> = ({ labels }) => {
  return (
    <Flex gap="4px 4px" wrap>
      {labels?.map((label, index) => (
        <EntityLabelComponent
          name={label.name}
          technical={label.technical}
          key={index}
        />
      ))}
    </Flex>
  );
};
