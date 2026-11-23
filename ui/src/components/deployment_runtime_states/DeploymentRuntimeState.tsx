import { Badge, BadgeProps, Flex, Space, Tooltip, Typography } from "antd";
import { DeploymentStatus, RuntimeState } from "../../api/apiTypes.ts";
import { useModalsContext } from "../../Modals.tsx";
import React from "react";
import { ErrorDetails } from "../modal/ErrorDetails.tsx";
import { capitalize } from "../../misc/format-utils.ts";
import { OverridableIcon } from "../../icons/IconProvider.tsx";

type DeploymentRuntimeStateProps = {
  name: string;
  service: string;
  timestamp: number;
  runtimeState: RuntimeState;
};

function getBadgeStatus(status: DeploymentStatus): BadgeProps["status"] {
  switch (status) {
    case DeploymentStatus.PROCESSING:
      return "processing";
    case DeploymentStatus.FAILED:
      return "error";
    case DeploymentStatus.DEPLOYED:
      return "success";
    case DeploymentStatus.REMOVED:
      return "warning";
    default:
      return "default";
  }
}

export const DeploymentRuntimeState: React.FC<DeploymentRuntimeStateProps> = ({
  name,
  service,
  timestamp,
  runtimeState,
}) => {
  const { showModal } = useModalsContext();

  const hasDetails = Boolean(runtimeState.error || runtimeState.stacktrace);
  const status = runtimeState.status;

  const label = capitalize(name);

  const openDetails = () => {
    if (!hasDetails) return;
    showModal({
      component: (
        <ErrorDetails
          service={service}
          timestamp={timestamp}
          message={runtimeState.error}
          stacktrace={runtimeState.stacktrace}
          status={status}
        />
      ),
    });
  };

  const badgeStatus = getBadgeStatus(status);

  return (
    <Space>
      <Badge status={badgeStatus} text={label} />
      {hasDetails && (
        <Tooltip
          title={
            <Flex vertical gap={8}>
              <Typography.Paragraph ellipsis={{rows: 5}}>
                {runtimeState.error || runtimeState.stacktrace}
              </Typography.Paragraph>
              <Typography.Link onClick={openDetails}>
                Show details
              </Typography.Link>
            </Flex>
          }
        >
          <OverridableIcon name={"deploymentStatusWarning"} />
        </Tooltip>
      )}
    </Space>
  );
};
