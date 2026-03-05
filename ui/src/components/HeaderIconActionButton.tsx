import React, { type CSSProperties, type ReactNode } from "react";
import { Button, type ButtonProps, Tooltip, type TooltipProps } from "antd";
import { OverridableIcon } from "../icons/IconProvider.tsx";

type HeaderIconActionButtonProps = {
  title: ReactNode;
  iconName?: string;
  iconNode?: ReactNode;
  onClick?: ButtonProps["onClick"];
  disabled?: boolean;
  danger?: boolean;
  type?: ButtonProps["type"];
  size?: ButtonProps["size"];
  className?: string;
  style?: CSSProperties;
  placement?: TooltipProps["placement"];
};

export const HeaderIconActionButton: React.FC<HeaderIconActionButtonProps> = ({
  title,
  iconName,
  iconNode,
  onClick,
  disabled,
  danger,
  type,
  size,
  className,
  style,
  placement = "bottom",
}) => {
  const icon =
    iconNode ?? (iconName ? <OverridableIcon name={iconName} /> : null);

  return (
    <Tooltip title={title} placement={placement}>
      <Button
        icon={icon}
        onClick={onClick}
        disabled={disabled}
        danger={danger}
        type={type}
        size={size}
        className={className}
        style={style}
      />
    </Tooltip>
  );
};
