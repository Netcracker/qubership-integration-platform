import { useCallback, useEffect, useState } from "react";
import { LibraryElement } from "../api/apiTypes.ts";
import { api } from "../api/api.ts";
import { useNotificationService } from "./useNotificationService.tsx";

export const useLibraryElement = (elementType?: string) => {
  const [isLoading, setIsLoading] = useState(false);
  const [libraryElement, setLibraryElement] = useState<LibraryElement>();
  const notificationService = useNotificationService();

  const getLibraryElement = useCallback(
    async (elementType: string) => {
      setIsLoading(true);
      try {
        return await api.getLibraryElementByType(elementType);
      } catch (error) {
        notificationService.requestFailed(
          "Failed to load element from library",
          error,
        );
        return undefined;
      } finally {
        setIsLoading(false);
      }
    },
    [notificationService],
  );

  useEffect(() => {
    if (!elementType) return;
    void getLibraryElement(elementType).then(setLibraryElement);
  }, [elementType, getLibraryElement]);

  return { isLoading, libraryElement };
};
