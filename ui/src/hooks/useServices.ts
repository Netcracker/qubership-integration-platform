import { useCallback, useEffect, useState } from "react";
import { api } from "../api/api";
import { IntegrationSystem } from "../api/apiTypes";
import { ListValue } from "../components/table/filter/filter";
import { useNotificationService } from "./useNotificationService";

export const useServiceFilterValues = () => {
  const [services, setServices] = useState<ListValue[]>();
  const notificationService = useNotificationService();

  const getServices = useCallback(async () => {
    try {
      const services = (await api.getServices("all", false)).map(
        (service: IntegrationSystem) => ({
          value: service.id,
          label: service.name,
        }),
      );
      setServices(services);
    } catch (error) {
      notificationService.requestFailed("Failed to load services", error);
    }
  }, [notificationService]);

  useEffect(() => {
    void getServices();
  }, [getServices]);

  return { services };
};

export const useServices = () => {
  const [services, setServices] = useState<IntegrationSystem[]>([]);
  const notificationService = useNotificationService();

  const getServices = useCallback(async () => {
    try {
      const services = await api.getServices("all", true);
      setServices(services);
    } catch (error) {
      notificationService.requestFailed("Failed to load services", error);
    }
  }, [notificationService]);

  useEffect(() => {
    void getServices();
  }, [getServices]);

  return { services };
};
