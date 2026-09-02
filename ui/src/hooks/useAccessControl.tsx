import { api } from "../api/api.ts";
import {
  AccessControlResponse,
  AccessControlSearchRequest,
  AccessControlUpdateRequest,
  AccessControlBulkDeployRequest,
} from "../api/apiTypes.ts";
import { EntityFilterModel } from "../components/table/filter/filterTypes";
import { useCallback, useEffect, useRef, useState } from "react";
import { useNotificationService } from "./useNotificationService.tsx";

const PAGE_SIZE = 30;

export const useAccessControl = (filters: EntityFilterModel[] = []) => {
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

  const updateAccessControl = useCallback(
    async (searchRequest: AccessControlUpdateRequest[]) => {
      try {
        await api.updateHttpTriggerAccessControl(searchRequest);
      } finally {
        setIsLoading(false);
      }
    },
    [],
  );

  const bulkDeployAccessControl = useCallback(
    async (searchRequest: AccessControlBulkDeployRequest[]) => {
      try {
        await api.bulkDeployChainsAccessControl(searchRequest);
      } finally {
        setIsLoading(false);
      }
    },
    [],
  );

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
