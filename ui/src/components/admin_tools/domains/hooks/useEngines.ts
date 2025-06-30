import { useState, useEffect, useCallback } from "react";
import { api } from "../../../../api/api.ts";
import type {
  Engine,
  Event,
  EngineUpdateResponse,
} from "../../../../api/apiTypes.ts";
import { useError, type ErrorState } from "./useError";
import { useEventContext } from "../../../notifications/contexts/EventContext.tsx";
import { ObjectType, EventActionType } from "../../../../api/apiTypes.ts";

export const useEngines = (domainName: string | null) => {
  const [engines, setEngines] = useState<Engine[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const { error, setError, clearError } = useError();
  const { subscribe } = useEventContext();

  const fetchEngines = useCallback(
    async (name: string) => {
      setIsLoading(true);
      clearError();
      try {
        const data = await api.getEnginesByDomain(name);
        setEngines(data);
      } catch (err) {
        setError(err as ErrorState);
        setEngines([]);
      } finally {
        setIsLoading(false);
      }
    },
    [clearError, setError],
  );

  useEffect(() => {
    if (!domainName) {
      setEngines([]);
      clearError();
      return;
    }

    void fetchEngines(domainName);

    const unsubscribe = subscribe(ObjectType.ENGINE, (event: Event) => {
      const engineEventData = event.data as EngineUpdateResponse;

      if (engineEventData && engineEventData.domainName === domainName) {
        switch (engineEventData.actionType) {
          case EventActionType.ADDED:
          case EventActionType.MODIFIED:
          case EventActionType.DELETED:
            void fetchEngines(domainName);
            break;
          default:
            break;
        }
      }
    });

    return () => {
      unsubscribe();
    };
  }, [domainName, fetchEngines, clearError, subscribe]);

  const retry = useCallback(async () => {
    if (domainName) {
      await fetchEngines(domainName);
    }
  }, [domainName, fetchEngines]);

  return { engines, isLoading, error, retry };
};
