import React from "react";
import { Flex } from "antd";
import { ProtectedButton, ProtectedButtonProps } from "../permissions/ProtectedButton.tsx";

type ChainHeaderToolbarProps = {
  buttons: ProtectedButtonProps[];
  gap?: number;
};

export const ChainHeaderToolbar: React.FC<ChainHeaderToolbarProps> = ({
  buttons,
  gap = 4,
}) => (
  <Flex align="center" gap={gap}>
    {buttons.map((props, i) => (
      <ProtectedButton key={i} {...props} />
    ))}
  </Flex>
);
