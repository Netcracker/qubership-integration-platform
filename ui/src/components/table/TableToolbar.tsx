import { Flex } from "antd";
import type { FC, ReactNode } from "react";

export type TableToolbarProps = {
  leading?: ReactNode;
  middle?: ReactNode;
  trailing?: ReactNode;
};

export const TableToolbar: FC<TableToolbarProps> = ({
  leading,
  middle,
  trailing,
}) => (
  <Flex
    align="center"
    gap={8}
    wrap="wrap"
    justify="space-between"
    style={{ flex: "none", width: "100%", paddingInline: 8 }}
  >
    <Flex
      align="center"
      gap={8}
      wrap="wrap"
      style={{ flex: "1 1 auto", minWidth: 0 }}
    >
      {leading}
      {middle}
    </Flex>
    {trailing ? (
      <Flex
        align="center"
        gap={8}
        wrap="wrap"
        style={{ flexShrink: 0, marginLeft: "auto" }}
      >
        {trailing}
      </Flex>
    ) : null}
  </Flex>
);
