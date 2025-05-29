import {Deployment, Event} from "../api/apiTypes.ts";

export function getDeploymentFromEvent(deploymentUpdateEvent: Event){

    // @ts-ignore
    const updatedDeployment: Deployment = {
        id: deploymentUpdateEvent.data.id,
        chainId: deploymentUpdateEvent.data.chainId,
        snapshotId: deploymentUpdateEvent.data.snapshotId,
        name: "",
        domain: deploymentUpdateEvent.data.domain,
        createdWhen: deploymentUpdateEvent.data.createdWhen,
        runtime: {
            states: {
                [String(deploymentUpdateEvent.data.engineHost)]: {
                    status: deploymentUpdateEvent.data.state.status,
                    suspended: deploymentUpdateEvent.data.state.suspended,
                    error: "",
                    stacktrace: ""
                }
            }
        }
    }

    return updatedDeployment;
}


export function mergeDeployment(oldD: Deployment, newD: Deployment): Deployment {
    return {
        ...oldD,
        ...newD,
        createdBy: newD.createdBy ?? oldD.createdBy,
        domain: newD.domain ?? oldD.domain,
        name: newD.name ?? oldD.name,
        snapshotId: newD.snapshotId ?? oldD.snapshotId,
        serviceName: newD.serviceName ?? oldD.serviceName,
        createdWhen: newD.createdWhen ?? oldD.createdWhen,
        runtime: newD.runtime ?? oldD.runtime,
    };
}
