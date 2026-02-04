import { LibraryElement, LibraryData } from "../api/apiTypes.ts";
import React, { createContext, ReactNode, useContext, useMemo } from "react";
import { api } from "../api/api.ts";
import { useNotificationService } from "../hooks/useNotificationService.tsx";
import { useQuery } from "@tanstack/react-query";

export type LibraryContextData = {
  libraryData: LibraryData | undefined;
  libraryElements: LibraryElement[] | null;
  isLibraryLoading: boolean;
};

const LibraryContext = createContext<LibraryContextData | undefined>(undefined);

export const LibraryProvider: React.FC<{ children: ReactNode }> = ({
  children,
}) => {
  const notificationService = useNotificationService();

  const {
    data: libraryData,
    isLoading: isLibraryLoading,
    error,
  } = useQuery<LibraryData, Error>({
    queryKey: ["library"],
    queryFn: async () => {
      return await api.getLibrary();
    },
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    refetchOnMount: false,
  });

  if (error) {
    notificationService.requestFailed("Failed to load library elements", error);
  }

  const libraryElements: LibraryElement[] = useMemo(() => {
    if (!libraryData) return [];

    const elements: LibraryElement[] = [];
    libraryData?.groups.forEach((group) => {
      group.elements.forEach((element: LibraryElement) => {
        //TODO check if it necessary
        if (element.deprecated || element.unsupported) return;

        elements.push(element);
      });
    });
    Object.values(libraryData.childElements).forEach(
      (element: LibraryElement) => {
        //TODO check if it necessary
        if (element.deprecated || element.unsupported) return;

        elements.push(element);
      },
    );
    return elements;
  }, [libraryData]);

  return (
    <LibraryContext.Provider
      value={{ libraryData, libraryElements, isLibraryLoading }}
    >
      {children}
    </LibraryContext.Provider>
  );
};

export const useLibraryContext = () => {
  const context = useContext(LibraryContext);
  if (!context) {
    throw new Error("useLibraryContext must be used within a ChainProvider");
  }
  return context;
};
