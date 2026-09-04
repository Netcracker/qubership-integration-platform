import { api } from "../api/api.ts";
import {
  AccessControlResponse,
  AccessControlSearchRequest,
  AccessControlUpdateRequest,
} from "../api/apiTypes.ts";
import { EntityFilterModel } from "../components/table/filter/filterTypes";
import { useCallback, useEffect, useRef, useState } from "react";
import { useNotificationService } from "./useNotificationService.tsx";

const PAGE_SIZE = 30;

/**
 * One array for every caller that names no filters. A fresh [] would be a new reference on each
 * render, and the fetch callback depends on it, so the hook would reload without end.
 */
const NO_FILTERS: EntityFilterModel[] = [];

export const useAccessControl = (filters: EntityFilterModel[] = NO_FILTERS) => {
  const [isLoading, setIsLoading] = useState(false);
  const [accessControlData, setAccessControlData] =
    useState<AccessControlResponse>();
  const [allDataLoaded, setAllDataLoaded] = useState(false);
  const offsetRef = useRef(0);
  const notificationService = useNotificationService();

  const fetchAccessControl = useCallback(
    async (currentOffset: number, append: boolean) => {
      try {
        setIsLoading(true);
        const searchRequest: AccessControlSearchRequest = {
          offset: currentOffset,
          limit: PAGE_SIZE,
          filters,
        };
        const responseData =
          await api.loadHttpTriggerAccessControl(searchRequest);

        setAccessControlData((prev) => {
          if (append && prev?.roles) {
            return {
              ...responseData,
              roles: [...prev.roles, ...responseData.roles],
            };
          }
          return responseData;
        });

        offsetRef.current = currentOffset + responseData.roles.length;

        if (responseData.roles.length < PAGE_SIZE) {
          setAllDataLoaded(true);
        }
      } catch (error) {
        notificationService.requestFailed(
          "Failed to load Http Trigger's Access Control",
          error,
        );
      } finally {
        setIsLoading(false);
      }
    },
    [notificationService, filters],
  );

  const getAccessControl = useCallback(async () => {
    offsetRef.current = 0;
    setAllDataLoaded(false);
    await fetchAccessControl(0, false);
  }, [fetchAccessControl]);

  const loadMore = useCallback(async () => {
    if (!allDataLoaded && !isLoading) {
      await fetchAccessControl(offsetRef.current, true);
    }
  }, [allDataLoaded, isLoading, fetchAccessControl]);

  // Neither of these raises isLoading: it belongs to the table's own fetch, and clearing it here
  // would let an in-flight loadMore run a second time on the same offset.
  const updateAccessControl = useCallback(
    async (searchRequest: AccessControlUpdateRequest[]) => {
      await api.updateHttpTriggerAccessControl(searchRequest);
    },
    [],
  );

  const bulkDeployAccessControl = useCallback(async (chainIds: string[]) => {
    await api.bulkDeployChainsAccessControl(chainIds);
  }, []);

  useEffect(() => {
    void getAccessControl();
  }, [getAccessControl]);

  return {
    isLoading,
    accessControlData,
    setAccessControlData,
    getAccessControl,
    updateAccessControl,
    bulkDeployAccessControl,
    loadMore,
    allDataLoaded,
  };
};
