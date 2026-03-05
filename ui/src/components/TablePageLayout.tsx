import React, { type ReactNode } from "react";
import { Flex } from "antd";

type TablePageLayoutProps = {
  children: ReactNode;
};

export const TablePageLayout: React.FC<TablePageLayoutProps> = ({
  children,
}) => (
  <Flex vertical gap={8} style={{ height: "100%" }}>
    {children}
  </Flex>
);
