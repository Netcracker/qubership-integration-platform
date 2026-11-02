import React, { type ReactNode } from "react";
import { Flex, Typography } from "antd";
import commonStyles from "./CommonStyle.module.css";

const { Title } = Typography;

type AdminToolsHeaderProps = {
  title: string;
  toolbar?: ReactNode;
};

export const AdminToolsHeader: React.FC<AdminToolsHeaderProps> = ({
  title,
  toolbar,
}) => (
  <Flex className={commonStyles.header}>
    <Title level={4} className={commonStyles.title}>
      {title}
    </Title>
    {toolbar}
  </Flex>
);
