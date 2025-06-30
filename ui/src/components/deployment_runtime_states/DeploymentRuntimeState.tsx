import { Tag, Tooltip } from "antd";
import { RuntimeState } from "../../api/apiTypes.ts";
import { useModalsContext } from "../../Modals.tsx";
import React from "react";
import { ErrorDetails } from "../modal/ErrorDetails.tsx";

type DeploymentRuntimeStateProps = {
  name: string;
  service: string;
  timestamp: number;
  runtimeState: RuntimeState;
};

export const DeploymentRuntimeState: React.FC<DeploymentRuntimeStateProps> = ({
  name,
  service,
  timestamp,
  runtimeState,
}) => {
  const { showModal } = useModalsContext();

  const onClick = () => {
    if (runtimeState.error || runtimeState.stacktrace) {
      showModal({
        component: (
          <ErrorDetails
            service={service}
            timestamp={timestamp}
            message={runtimeState.error}
            stacktrace={runtimeState.stacktrace}
          />
        ),
      });
    }
  };

  const color = getDeploymentStatusColor(runtimeState.status);
  const tag = (
    <Tag onClick={onClick} color={color} key={name}>
      {name}
    </Tag>
  );

  return runtimeState.error || runtimeState.stacktrace ? (
    <Tooltip title={runtimeState.error ?? runtimeState.stacktrace}>
      {tag}
    </Tooltip>
  ) : (
    tag
  );
};

function getDeploymentStatusColor(status: string): string {
  switch (status) {
    case "DEPLOYED":
      return "green";
    case "PROCESSING":
      return "blue";
    case "FAILED":
      return "red";
    case "REMOVED":
      return "orange";
    default:
      return "#888888";
  }
}
