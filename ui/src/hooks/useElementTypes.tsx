import { useCallback, useEffect, useState } from "react";
import { ElementFilter } from "../api/apiTypes";
import { api } from "../api/api";
import { useNotificationService } from "./useNotificationService";
import { ListValue } from "../components/table/filter/filter";

export const useElementTypes = () => {
  const [elementTypes, setElementTypes] = useState<ElementFilter[]>([]);
  const notificationService = useNotificationService();

  const getLibraryElement = useCallback(async () => {
    try {
      const types: ElementFilter[] = await api.getElementTypes();
      if (!Array.isArray(types)) {
        setElementTypes([]);
        return;
      }
      setElementTypes(types);
    } catch (error) {
      notificationService.requestFailed("Failed to load element types", error);
      setElementTypes([]);
    }
  }, [notificationService]);

  useEffect(() => {
    void getLibraryElement();
  }, [getLibraryElement]);

  const buildFilterValues = useCallback((): ListValue[] => {
    if (!Array.isArray(elementTypes)) {
      return [];
    }
    try {
      return elementTypes.map((item) => ({
        value: item.elementType,
        label: `${item.elementTitle} (${item.elementType})`,
      }));
    } catch {
      return [];
    }
  }, [elementTypes]);

  return {
    elementTypes: Array.isArray(elementTypes) ? elementTypes : [],
    buildFilterValues: buildFilterValues,
  };
};
