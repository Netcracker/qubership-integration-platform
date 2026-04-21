import React from "react";
import { Tag, TagProps } from "antd";
import { capitalize } from "../../misc/format-utils.ts";
import { OverridableIcon } from "../../icons/IconProvider.tsx";

type DeploymentStatusTagProps = Omit<TagProps, "color" | "icon" | "children"> & {
  status: string;
  text?: React.ReactNode;
};

type StatusVisuals = {
  color: string;
  icon: React.ReactNode;
};

export function getDeploymentStatusVisuals(status: string): StatusVisuals {
  switch (status.toUpperCase()) {
    case "DEPLOYED":
      return {
        color: "success",
        icon: <OverridableIcon name="deploymentStatusDeployed" />,
      };
    case "PROCESSING":
      return {
        color: "processing",
        icon: <OverridableIcon name="deploymentStatusProcessing" spin />,
      };
    case "FAILED":
      return {
        color: "error",
        icon: <OverridableIcon name="deploymentStatusFailed" />,
      };
    case "WARNING":
      return {
        color: "warning",
        icon: <OverridableIcon name="deploymentStatusWarning" />,
      };
    case "REMOVED":
      return {
        color: "default",
        icon: <OverridableIcon name="deploymentStatusRemoved" />,
      };
    case "DRAFT":
    default:
      return {
        color: "default",
        icon: <OverridableIcon name="deploymentStatusDraft" />,
      };
  }
}

export const DeploymentStatusTag = React.forwardRef<
  HTMLSpanElement,
  DeploymentStatusTagProps
>(({ status, text, style, ...rest }, ref) => {
  const { color, icon } = getDeploymentStatusVisuals(status);
  return (
    <Tag
      ref={ref}
      icon={icon}
      color={color}
      style={{
        marginInlineEnd: 0,
        fontSize: 13,
        lineHeight: "22px",
        padding: "2px 10px",
        display: "inline-flex",
        alignItems: "center",
        gap: 6,
        ...style,
      }}
      {...rest}
    >
      {text ?? capitalize(status)}
    </Tag>
  );
});

DeploymentStatusTag.displayName = "DeploymentStatusTag";
