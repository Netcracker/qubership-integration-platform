import { useEffect, useState } from "react";
import { Element } from "../api/apiTypes.ts";
import { api } from "../api/api.ts";
import { notification } from "antd";

export const useLibraryElement = (elementType?: string) => {
  const [libraryElement, setLibraryElement] = useState<Element>();

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
      notification.error({
        message: "Request failed",
        description: "Failed to load element from library",
      });
    }
  };

  return { libraryElement };
};