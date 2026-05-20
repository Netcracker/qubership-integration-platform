import { useCallback } from "react";
import { documentationService } from "../services/documentation/documentationService";
import type {
  HighlightSegment,
  SearchResult,
} from "../services/documentation/documentationTypes";

export const useDocumentation = () => {
  const openElementDoc = useCallback((elementType: string) => {
    void documentationService.openChainElementDocumentation(elementType);
  }, []);

  const openContextDoc = useCallback(() => {
    documentationService.openContextDocumentation();
  }, []);

  const openPage = useCallback((path: string) => {
    documentationService.openPage(path);
  }, []);

  const search = useCallback(async (query: string): Promise<SearchResult[]> => {
    return documentationService.search(query);
  }, []);

  const getSearchDetail = useCallback(
    async (ref: number, query: string, terms?: string[]): Promise<string[]> => {
      return documentationService.getSearchDetail(ref, query, terms);
    },
    [],
  );

  const getSearchDetailSegments = useCallback(
    async (
      ref: number,
      query: string,
      terms?: string[],
    ): Promise<HighlightSegment[][]> => {
      return documentationService.getSearchDetailSegments(ref, query, terms);
    },
    [],
  );

  const loadPaths = useCallback(() => {
    return documentationService.loadPaths();
  }, []);

  const loadNames = useCallback(() => {
    return documentationService.loadNames();
  }, []);

  const loadTOC = useCallback(() => {
    return documentationService.loadTOC();
  }, []);

  return {
    openElementDoc,
    openContextDoc,
    openPage,
    search,
    getSearchDetail,
    getSearchDetailSegments,
    loadPaths,
    loadNames,
    loadTOC,
  };
};
