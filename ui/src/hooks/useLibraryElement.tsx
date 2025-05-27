import { useEffect, useState } from "react";
import { Element } from "../api/apiTypes.ts";
import { api } from "../api/api.ts";
import { useNotificationService } from "./useNotificationService.tsx";

export const useLibraryElement = (elementType?: string) => {
  const [libraryElement, setLibraryElement] = useState<Element>();
  const notificationService = useNotificationService();

  useEffect(() => {
    if (!elementType) return;
    getLibraryElement(elementType);
  }, []);

  const getLibraryElement = async (elementType: string) => {
    try {
      const libraryElement = await api.getLibraryElementByType(elementType);
      console.log(libraryElement);
      setLibraryElement(libraryElement);
    } catch (error) {
      notificationService.requestFailed("Failed to load element from library", error);
    }
  };

  return { libraryElement };
};