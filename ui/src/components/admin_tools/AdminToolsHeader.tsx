import React, { type ReactNode } from "react";
import { Flex, Typography } from "antd";
import { OverridableIcon, type IconName } from "../../icons/IconProvider.tsx";
import commonStyles from "./CommonStyle.module.css";

const { Title } = Typography;

type AdminToolsHeaderProps = {
  title: string;
  iconName: IconName;
  toolbar?: ReactNode;
};

export const AdminToolsHeader: React.FC<AdminToolsHeaderProps> = ({
  title,
  iconName,
  toolbar,
}) => (
  <Flex className={commonStyles.header}>
    <Title level={4} className={commonStyles.title}>
      <OverridableIcon name={iconName} className={commonStyles.icon} />
      {title}
    </Title>
    {toolbar}
  </Flex>
);
