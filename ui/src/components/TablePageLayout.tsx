import React, { type ReactNode } from "react";
import { Flex } from "antd";

type TablePageLayoutProps = {
  children: ReactNode;
};

export const TablePageLayout: React.FC<TablePageLayoutProps> = ({
  children,
}) => (
  // minHeight: 0 lets the layout shrink to the space left by a sibling header in
  // a flex-column parent (admin pages), instead of taking the full height and
  // overflowing it; the inner flex-table scrolls. Inert in row-flex parents.
  // overflow: hidden clips the flex-table's ~1px sub-pixel rounding so it can't
  // bubble up to the app-shell scroll as a stray scrollbar (chain tabs).
  <Flex
    vertical
    gap={8}
    style={{ height: "100%", minHeight: 0, minWidth: 0, overflow: "hidden" }}
  >
    {children}
  </Flex>
);
