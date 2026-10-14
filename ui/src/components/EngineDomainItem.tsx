import React, { ReactNode } from "react";
import { DomainType } from "../api/apiTypes.ts";
import { Space, Tag, TagProps } from "antd";

type EngineDomainItemProps = {
  type: DomainType;
  name: ReactNode;
} & TagProps;

export const EngineDomainItem: React.FC<EngineDomainItemProps> = ({
  name,
  type,
  ...rest
}): ReactNode => {
  return type === DomainType.MICRO ? (
    <Space size={"small"}>
      <Tag {...rest}>micro</Tag>
      <span>{name}</span>
    </Space>
  ) : (
    name
  );
};
