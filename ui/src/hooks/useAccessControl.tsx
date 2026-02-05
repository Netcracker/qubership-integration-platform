import { api } from "../api/api.ts";
import {
  AccessControlResponse,
  AccessControlSearchRequest,
  AccessControlUpdateRequest,
  AccessControlBulkDeployRequest,
} from "../api/apiTypes.ts";
import { useCallback, useEffect, useState } from "react";
import { useNotificationService } from "./useNotificationService.tsx";

export const useAccessControl = () => {
  const [isLoading, setIsLoading] = useState(false);
  const [accessControlData, setAccessControlData] =
    useState<AccessControlResponse>();
  const notificationService = useNotificationService();

  const getAccessControl = useCallback(async () => {
    try {
      setIsLoading(true);
      const searchRequest: AccessControlSearchRequest = {
        offset: 0,
        limit: 30,
        filters: [],
      };
      const responseData =
        await api.loadHttpTriggerAccessControl(searchRequest);
      setAccessControlData(responseData);
    } catch (error) {
      notificationService.requestFailed(
        "Failed to load Http Trigger's Access Control",
        error,
      );
    } finally {
      setIsLoading(false);
    }
  }, [notificationService]);

  const updateAccessControl = useCallback(
    async (searchRequest: AccessControlUpdateRequest[]) => {
      try {
        const elementChange =
          await api.updateHttpTriggerAccessControl(searchRequest);
        setAccessControlData(elementChange);
      } catch (error) {
        notificationService.requestFailed(
          "Failed to update Http Trigger's Access Control",
          error,
        );
      } finally {
        setIsLoading(false);
      }
    },
    [notificationService],
  );

  const bulkDeployAccessControl = useCallback(
    async (searchRequest: AccessControlBulkDeployRequest[]) => {
      try {
        const bulkDeployResponse =
          await api.bulkDeployChainsAccessControl(searchRequest);
        setAccessControlData(bulkDeployResponse);
      } catch (error) {
        notificationService.requestFailed(
          "Failed to bulk deploy chains",
          error,
        );
      } finally {
        setIsLoading(false);
      }
    },
    [notificationService],
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
  };
};
