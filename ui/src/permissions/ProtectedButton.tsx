import { RequiredPermissions } from "./types.ts";
import { TooltipProps } from "antd/es/tooltip";
import { ButtonProps } from "antd/es/button/button";
import React, { ReactNode } from "react";
import { Require } from "./Require.tsx";
import { Button, Tooltip } from "antd";
import { IconName, OverridableIcon } from "../icons/IconProvider.tsx";

export type OnDenied = "hide" | "disable";

type ButtonWithTooltipProps = {
  tooltipProps: TooltipProps;
  buttonProps: ButtonProps & { iconName?: IconName };
};

const ButtonWithTooltip: React.FC<ButtonWithTooltipProps> = ({
  tooltipProps,
  buttonProps: { iconName, icon, ...restButtonProps },
}): ReactNode => {
  return (
    <Tooltip {...tooltipProps}>
      <Button
        icon={iconName ? <OverridableIcon name={iconName} /> : icon}
        {...restButtonProps}
      />
    </Tooltip>
  );
};

export type ProtectedButtonProps = ButtonWithTooltipProps & {
  require: RequiredPermissions;
  onDenied?: OnDenied;
};

export const ProtectedButton: React.FC<ProtectedButtonProps> = ({
  require,
  onDenied = "hide",
  tooltipProps,
  buttonProps,
}): ReactNode => {
  return (
    <Require
      permissions={require}
      fallback={
        onDenied === "disable" ? (
          <ButtonWithTooltip
            buttonProps={{ ...buttonProps, disabled: true }}
            tooltipProps={tooltipProps}
          />
        ) : null
      }
    >
      <ButtonWithTooltip
        buttonProps={buttonProps}
        tooltipProps={tooltipProps}
      />
    </Require>
  );
};
