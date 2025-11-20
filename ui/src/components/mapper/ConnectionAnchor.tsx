import React, { forwardRef, useEffect, useState } from "react";
import styles from "./ConnectionAnchor.module.css";
import { Tooltip, TooltipProps } from "antd";
import { OverridableIcon } from "../../icons/IconProvider.tsx";

export type ConnectionAnchorProps = React.HTMLAttributes<HTMLElement> & {
  tooltipTitle?: TooltipProps["title"];
  tooltipPlacement?: TooltipProps["placement"];
  invalid?: boolean;
  connected: boolean;
  required?: boolean;
  showSettingIcon?: boolean;
  onClick?: (connected: boolean) => void;
};

export const ConnectionAnchor = forwardRef<
  HTMLDivElement,
  ConnectionAnchorProps
>(
  (
    {
      invalid,
      connected,
      required,
      onClick,
      tooltipPlacement,
      tooltipTitle,
      showSettingIcon,
      ...props
    },
    ref,
  ) => {
    const [className, setClassName] = useState<string>(
      `${styles["connection-anchor"]} ${styles["disconnected"]}`,
    );

    useEffect(() => {
      const classNames: (keyof typeof styles)[] = [
        styles["connection-anchor"],
        styles[connected ? "connected" : "disconnected"],
      ];
      if (invalid) {
        classNames.push(styles["invalid"]);
      }
      if (required) {
        classNames.push(styles["required"]);
      }
      setClassName(classNames.join(" "));
    }, [invalid, connected, required]);

    return (
      <Tooltip title={tooltipTitle ?? ""} placement={tooltipPlacement}>
        <div
          ref={ref}
          className={className}
          {...props}
          onClick={() => onClick?.(connected)}
        >
          {showSettingIcon ? (
            <OverridableIcon name="settings" />
          ) : (
            <div className={styles["inner-circle"]}></div>
          )}
        </div>
      </Tooltip>
    );
  },
);

ConnectionAnchor.displayName = "ConnectionAnchor";
