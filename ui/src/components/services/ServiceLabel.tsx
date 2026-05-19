import React from "react";
import { Tag } from "antd";

export interface ContextServiceTagProps {
  label: string | React.ReactNode;
  onClose?: (e?: React.MouseEvent<HTMLElement>) => void;
  isTech: boolean;
}

export const ContextServiceTag: React.FC<ContextServiceTagProps> = ({
  label,
  onClose,
  isTech,
}) => {
  return (
    <Tag
      color={isTech ? "blue" : "default"}
      closable={!isTech}
      onClose={onClose}
      style={{ marginRight: 4 }}
    >
      {label}
    </Tag>
  );
};
