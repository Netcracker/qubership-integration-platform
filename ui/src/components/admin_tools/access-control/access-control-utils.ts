import { getCumulativeStatus } from "../../deployment_runtime_states/DeploymentsCumulativeState.tsx";
import { AccessControl } from "../../../api/apiTypes.ts";

export function hasUnsavedChanges(data: AccessControl): boolean {
  return (
    data.unsavedChanges &&
    getCumulativeStatus(new Set(data.deploymentStatus)) === "DEPLOYED"
  );
}
