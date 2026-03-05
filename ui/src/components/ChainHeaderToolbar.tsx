import React, { type ReactNode } from "react";
import { Flex } from "antd";
import { HeaderIconActionButton } from "./HeaderIconActionButton.tsx";

export type ChainHeaderToolbarButton = {
  title: ReactNode;
  iconName?: string;
  iconNode?: ReactNode;
  onClick: () => void;
  disabled?: boolean;
  type?: "primary" | "default";
};

type ChainHeaderToolbarProps = {
  buttons: ChainHeaderToolbarButton[];
  gap?: number;
};

export const ChainHeaderToolbar: React.FC<ChainHeaderToolbarProps> = ({
  buttons,
  gap = 4,
}) => (
  <Flex align="center" gap={gap}>
    {buttons.map((btn, i) => (
      <HeaderIconActionButton
        key={i}
        title={btn.title}
        iconName={btn.iconName}
        iconNode={btn.iconNode}
        onClick={btn.onClick}
        disabled={btn.disabled}
        type={btn.type}
      />
    ))}
  </Flex>
);
