import React, { useEffect, useState } from "react";
import { Deployment } from "../../api/apiTypes.ts";
import { Badge, BadgeProps, Spin } from "antd";
import { api } from "../../api/api.ts";
import { capitalize } from "../../misc/format-utils.ts";
import { useDeployments } from "../../hooks/useDeployments.tsx";

type DeploymentsCumulativeStateProps = {
  chainId: string;
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

function getDeploymentBadgeStatus(status: string): BadgeProps["status"] {
  switch (status) {
    case "DRAFT":
      return "default";
    case "PROCESSING":
      return "processing";
    case "FAILED":
      return "error";
    case "DEPLOYED":
      return "success";
    case "WARNING":
      return "warning";
    default:
      return "default";
  }
}

export const DeploymentsCumulativeState: React.FC<
  DeploymentsCumulativeStateProps
> = ({ chainId }) => {
  const [status, setStatus] = useState<string>("DRAFT");
  const [badgeStatus, setBadgeStatus] = useState<BadgeProps["status"]>("default");
  const { isLoading, deployments, setDeployments , setIsLoading} = useDeployments(chainId);

  useEffect(() => {
    getDeployments().then((d) => setDeployments(d ?? []));
  }, [chainId]);

  useEffect(() => {
    setStatus(getDeploymentsStatus(deployments ?? []));
  }, [deployments]);

  useEffect(() => {
    setBadgeStatus(getDeploymentBadgeStatus(status));
  }, [status]);

  const getDeployments = async () => {
    if (!chainId) {
      return;
    }
    setIsLoading(true);
    try {
      return api.getDeployments(chainId);
    } finally {
      setIsLoading(false);
    }
  };

  return isLoading ? <Spin /> : <Badge status={badgeStatus} text={capitalize(status)} />;
};
