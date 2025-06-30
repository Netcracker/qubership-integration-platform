import { useCallback, useEffect, useState } from "react";
import { ElementFilter } from "../api/apiTypes"
import { api } from "../api/api";
import { useNotificationService } from "./useNotificationService";

export const useElementTypes = () => {
  const [elementTypes, setElementTypes] = useState<ElementFilter[]>([]);
  const notificationService = useNotificationService();

  const getLibraryElement = useCallback(async () => {
    try {
      const types: ElementFilter[] = await api.getElementTypes()
      setElementTypes(types);
    } catch (error) {
      notificationService.requestFailed("Failed to load element types", error);
    }
  }, [notificationService]);

  useEffect(() => {
    void getLibraryElement();
  }, [getLibraryElement]);

  return { elementTypes };
}
