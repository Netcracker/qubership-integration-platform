import { RuntimeStates } from "../../api/apiTypes.ts";
import { DeploymentRuntimeState } from "./DeploymentRuntimeState.tsx";
import { Flex } from "antd";
import React from "react";

type DeploymentRuntimeStatesProps = {
  service: string;
  timestamp: number;
  runtimeStates: RuntimeStates;
};

export const DeploymentRuntimeStates: React.FC<
  DeploymentRuntimeStatesProps
> = ({ service, timestamp, runtimeStates }) => {
  return (
    <Flex gap="4px 4px" wrap>
      {Object.entries(runtimeStates.states).map(([name, runtimeState]) => (
        <DeploymentRuntimeState
          name={name}
          key={name}
          service={service}
          timestamp={timestamp}
          runtimeState={runtimeState}
        />
      ))}
    </Flex>
  );
};
