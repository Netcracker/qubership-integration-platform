import React, { useEffect, useState } from "react";
import { Deployment } from "../../api/apiTypes.ts";
import { Badge, BadgeProps } from "antd";

type DeploymentsCumulativeStateProps = {
  deployments: Deployment[];
};

function getDeploymentsStatus(deployments: Deployment[]): string {
  const statuses = new Set<string>(
    deployments
      ?.flatMap((deployment) => Object.values(deployment.runtime?.states ?? {}))
      .map((runtimeState) => runtimeState.status.toUpperCase()),
  );
  if (statuses.size === 0) {
    return "DRAFT";
  } else if (statuses.size === 1) {
    return statuses.values().next().value ?? "DRAFT";
  } else {
    for (const i of ["PROCESSING", "FAILED", "WARNING", "DEPLOYED"]) {
      if (statuses.has(i)) {
        return i;
      }
    }
    return "DRAFT";
  }
}

export const DeploymentsCumulativeState: React.FC<
  DeploymentsCumulativeStateProps
> = ({ deployments }) => {
  const [status, setStatus] = useState<string>("DRAFT");
  const [color, setColor] = useState<BadgeProps["color"]>("green");

  useEffect(() => {
    setStatus(getDeploymentsStatus(deployments));
  }, [deployments]);
  return <Badge color={color} text={status} />;
};
