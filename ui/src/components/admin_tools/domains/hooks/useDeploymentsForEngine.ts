import { useState, useEffect, useCallback } from "react";
import { api } from "../../../../api/api.ts";
import {
  Event,
  EngineUpdateResponse,
  ChainDeployment,
  DeploymentUpdate,
} from "../../../../api/apiTypes.ts";
import { ObjectType, EventActionType } from "../../../../api/apiTypes.ts";
import { useError, type ErrorState } from "./useError";
import { useEventContext } from "../../../notifications/contexts/EventContext.tsx";

export const useDeploymentsForEngine = (
  domainName: string | null,
  host: string | null,
) => {
  const [deployments, setDeployments] = useState<ChainDeployment[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const { error, setError, clearError } = useError();
  const { subscribe } = useEventContext();

  const fetchDeployments = useCallback(async () => {
    if (!domainName || !host) {
      setDeployments([]);
      return [];
    }
    setIsLoading(true);
    clearError();
    try {
      const data = await api.getDeploymentsByEngine(domainName, host);
      setDeployments(data as ChainDeployment[]);
      return data as ChainDeployment[];
    } catch (err) {
      setError(err as ErrorState);
      setDeployments([]);
      return [];
    } finally {
      setIsLoading(false);
    }
  }, [domainName, host, clearError, setError]);

  useEffect(() => {
    fetchDeployments();
  }, [fetchDeployments]);

  useEffect(() => {
    if (!domainName || !host) {
      setDeployments([]);
      clearError();
      return;
    }

    const unsubscribe = subscribe(ObjectType.DEPLOYMENT, (event: Event) => {
      const data: DeploymentUpdate = event.data as DeploymentUpdate;
      if (data && data.engineHost === host && data.domain === domainName) {
        fetchDeployments().then((newDeployments) => {
          setDeployments((current) => {
            if (!Array.isArray(newDeployments)) return current;
            const currentById = Object.fromEntries(
              current.map((d) => [d.id, d]),
            );
            const updated = newDeployments.map((newD) => {
              const old = currentById[newD.id];
              if (old) {
                return { ...old, runtime: newD.state?.status };
              }
              return newD;
            });
            return updated;
          });
        });
      }
    });

    const unsubscribeEngines = subscribe(ObjectType.ENGINE, (event: Event) => {
      console.log("Received engine event:", {
        eventId: event.id,
        eventTime: event.time,
        eventData: event.data,
      });

      const engineEventData = event.data as EngineUpdateResponse;

      if (
        engineEventData &&
        engineEventData.domainName === domainName &&
        engineEventData.host === host
      ) {
        console.log("Processing engine event for engine:", {
          engineHost: host,
          domain: domainName,
          actionType: engineEventData.actionType,
        });

        switch (engineEventData.actionType) {
          case EventActionType.DELETED:
            console.log("Selected engine was deleted. Clearing deployments.");
            setDeployments([]);
            clearError();
            break;
          case EventActionType.MODIFIED:
            console.log("Selected engine was modified.");
            fetchDeployments();
            break;
          case EventActionType.ADDED:
            console.log("Engine was added");
            break;
          case EventActionType.UNKNOWN:
          default:
            console.log("Received unknown engine event action type.");
            break;
        }
      } else {
        console.log("Skipping engine event - not for this engine:", {
          eventEngineHost: engineEventData?.host,
          eventDomain: engineEventData?.domainName,
          currentEngineHost: host,
          currentDomain: domainName,
        });
      }
    });

    return () => {
      unsubscribe();
      unsubscribeEngines();
    };
  }, [domainName, host, subscribe, clearError, fetchDeployments]);

  const retry = useCallback(() => {
    fetchDeployments();
  }, [fetchDeployments]);

  return { deployments, isLoading, error, retry };
};
