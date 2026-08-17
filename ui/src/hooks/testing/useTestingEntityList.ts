import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api } from "../../api/api";
import {
  Element,
  EndpointMock,
  TestCaseRunView,
  TestCaseView,
  TestingFilter,
  TestingFilterCondition,
  TestingListOptions,
  TestingSelectionSpecification,
  TestingSortOrder,
  TestsRunView,
} from "../../api/apiTypes";
import { EntityFilterModel } from "../../components/table/filter/filterTypes";
import { flattenElements } from "../../components/testing/testingElements";
import {
  buildTestingFilters,
  ENDPOINT_MOCKS_SORT_FIELDS,
  NamedEntity,
  TESTING_CHAIN_FEATURE,
  TESTING_CHAIN_NAME_COLUMN,
  TESTING_ELEMENT_NAME_COLUMN,
  TEST_CASES_SORT_FIELDS,
  TEST_CASE_RUNS_SORT_FIELDS,
  TESTS_RUNS_SORT_FIELDS,
} from "../filter/useTestingFilter";
import { useNotificationService } from "../useNotificationService";
import { downloadFile } from "../../misc/download-utils";

/** One testing list: how to read it, what it may be sorted by, how it is scoped. */
export type TestingListSource<T> = {
  /** Plural noun the notifications name, such as "test cases". */
  entityName: string;
  list(
    specification: TestingSelectionSpecification,
    options?: TestingListOptions,
  ): Promise<T[]>;
  listIds(specification: TestingSelectionSpecification): Promise<string[]>;
  exportEntities(ids: string[]): Promise<File>;
  sortFields: readonly string[];
  /** Whether any column or filter of this list names an element. */
  usesElementNames?: boolean;
};

export const testCasesListSource: TestingListSource<TestCaseView> = {
  entityName: "test cases",
  list: (specification, options) => api.getTestCases(specification, options),
  listIds: (specification) => api.getTestCaseIds(specification),
  exportEntities: (ids) => api.exportTestCases(ids),
  sortFields: TEST_CASES_SORT_FIELDS,
  usesElementNames: true,
};

export const endpointMocksListSource: TestingListSource<EndpointMock> = {
  entityName: "endpoint mocks",
  list: (specification, options) =>
    api.getEndpointMocks(specification, options),
  listIds: (specification) => api.getEndpointMockIds(specification),
  exportEntities: (ids) => api.exportEndpointMocks(ids),
  sortFields: ENDPOINT_MOCKS_SORT_FIELDS,
  usesElementNames: true,
};

export const testsRunsListSource: TestingListSource<TestsRunView> = {
  entityName: "test runs",
  list: (specification, options) => api.getTestsRuns(specification, options),
  listIds: (specification) => api.getTestsRunIds(specification),
  exportEntities: (ids) => api.exportTestsRuns(ids),
  sortFields: TESTS_RUNS_SORT_FIELDS,
};

export const testCaseRunsListSource: TestingListSource<TestCaseRunView> = {
  entityName: "test case runs",
  list: (specification, options) => api.getTestCaseRuns(specification, options),
  listIds: (specification) => api.getTestCaseRunIds(specification),
  exportEntities: (ids) => api.exportTestCaseRuns(ids),
  sortFields: TEST_CASE_RUNS_SORT_FIELDS,
};

export type UseTestingEntityListOptions<T> = {
  source: TestingListSource<T>;
  chainId?: string;
  filters: EntityFilterModel[];
  searchString?: string;
  sortBy?: string;
  sortOrder?: TestingSortOrder;
  /** Scope the route fixes, such as the run a drill-down belongs to. Memoize it. */
  scopeFilters?: TestingFilter[];
};

export type TestingEntityList<T> = {
  items: T[];
  isLoading: boolean;
  allLoaded: boolean;
  loadMore: () => void;
  refresh: () => void;
  getChainName: (chainId: string | null | undefined) => string;
  getElementName: (elementId: string | null | undefined) => string;
  resolveTargetIds: (
    selectedIds: string[],
    selectAll: boolean,
  ) => Promise<string[]>;
  exportEntities: (ids: string[]) => Promise<void>;
};

const NO_NAMES: NamedEntity[] = [];

function toNamedElements(elements: Element[]): NamedEntity[] {
  return flattenElements(elements).map((element) => ({
    id: element.id,
    name: element.name,
  }));
}

function toNameMap(entities: NamedEntity[]): Map<string, string> {
  return new Map(entities.map((entity) => [entity.id, entity.name]));
}

/**
 * The list behind every testing screen: it assembles the selection, pages
 * through it by offset, resolves the targets of a selection reaching past the
 * loaded page, and holds the names the chain and element cells display.
 *
 * Names come from one request each — the chains of the whole installation, or
 * the elements of the chain in scope. There is no cross-chain element lookup,
 * so an element of another chain keeps its id.
 */
export function useTestingEntityList<T extends { id: string }>({
  source,
  chainId,
  filters,
  searchString,
  sortBy,
  sortOrder,
  scopeFilters,
}: UseTestingEntityListOptions<T>): TestingEntityList<T> {
  const notificationService = useNotificationService();
  const [items, setItems] = useState<T[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [allLoaded, setAllLoaded] = useState(false);
  const [chains, setChains] = useState<NamedEntity[]>([]);
  const [elements, setElements] = useState<NamedEntity[]>([]);
  const itemsRef = useRef<T[]>([]);

  useEffect(() => {
    itemsRef.current = items;
  }, [items]);

  useEffect(() => {
    let canceled = false;
    const loadNames = async () => {
      try {
        if (chainId) {
          if (!source.usesElementNames) {
            return;
          }
          const chainElements = await api.getElements(chainId);
          if (!canceled) {
            setElements(toNamedElements(chainElements));
          }
        } else {
          const allChains = await api.getChains();
          if (!canceled) {
            setChains(
              allChains.map((chain) => ({ id: chain.id, name: chain.name })),
            );
          }
        }
      } catch (error) {
        notificationService.requestFailed("Failed to resolve names", error);
      }
    };
    void loadNames();
    return () => {
      canceled = true;
    };
  }, [chainId, source.usesElementNames, notificationService]);

  // Names reach the selection only through a filter written against them, so a
  // list without one is not refetched when the name caches arrive.
  const chainsForFilters = useMemo(
    () =>
      filters.some((filter) => filter.column === TESTING_CHAIN_NAME_COLUMN)
        ? chains
        : NO_NAMES,
    [filters, chains],
  );

  const elementsForFilters = useMemo(
    () =>
      filters.some((filter) => filter.column === TESTING_ELEMENT_NAME_COLUMN)
        ? elements
        : NO_NAMES,
    [filters, elements],
  );

  const selection = useMemo(
    () =>
      buildTestingFilters(filters, {
        chains: chainsForFilters,
        elements: elementsForFilters,
      }),
    [filters, chainsForFilters, elementsForFilters],
  );

  const specification = useMemo<
    TestingSelectionSpecification | undefined
  >(() => {
    if (selection.isEmpty) {
      return undefined;
    }
    const allFilters: TestingFilter[] = [...(scopeFilters ?? [])];
    if (chainId) {
      allFilters.push({
        feature: TESTING_CHAIN_FEATURE,
        condition: TestingFilterCondition.IS,
        values: [chainId],
      });
    }
    allFilters.push(...selection.filters);
    return {
      ...(searchString ? { searchText: searchString } : {}),
      ...(allFilters.length > 0 ? { filters: allFilters } : {}),
    };
  }, [selection, scopeFilters, chainId, searchString]);

  const listOptions = useMemo<Omit<TestingListOptions, "offset">>(
    () =>
      sortBy && source.sortFields.includes(sortBy)
        ? { sortBy, sortOrder: sortOrder ?? TestingSortOrder.ASC }
        : {},
    [sortBy, sortOrder, source.sortFields],
  );

  // Search is not debounced, so several pages can be in flight at once. Only the
  // newest request may write, which also keeps a resolved request from writing
  // after the screen is gone.
  const requestGenerationRef = useRef(0);
  useEffect(
    () => () => {
      requestGenerationRef.current += 1;
    },
    [],
  );

  const fetchPage = useCallback(
    async (offset: number, replace: boolean) => {
      const generation = ++requestGenerationRef.current;
      const isCurrent = () => generation === requestGenerationRef.current;
      if (!specification) {
        setItems([]);
        setAllLoaded(true);
        setIsLoading(false);
        return;
      }
      setIsLoading(true);
      try {
        const page = await source.list(specification, {
          offset,
          ...listOptions,
        });
        if (!isCurrent()) {
          return;
        }
        setItems((previous) => (replace ? page : [...previous, ...page]));
        setAllLoaded(page.length === 0);
      } catch (error) {
        if (!isCurrent()) {
          return;
        }
        notificationService.requestFailed(
          `Failed to load ${source.entityName}`,
          error,
        );
        setAllLoaded(true);
      } finally {
        if (isCurrent()) {
          setIsLoading(false);
        }
      }
    },
    [specification, listOptions, source, notificationService],
  );

  useEffect(() => {
    void fetchPage(0, true);
  }, [fetchPage]);

  const loadMore = useCallback(() => {
    void fetchPage(itemsRef.current.length, false);
  }, [fetchPage]);

  const refresh = useCallback(() => {
    void fetchPage(0, true);
  }, [fetchPage]);

  // A selection reaching past the loaded page is resolved by the service under
  // the same filters, so it covers rows no request has returned yet.
  const resolveTargetIds = useCallback(
    async (selectedIds: string[], selectAll: boolean): Promise<string[]> => {
      if (!selectAll) {
        return selectedIds;
      }
      if (!specification) {
        return [];
      }
      return source.listIds(specification);
    },
    [specification, source],
  );

  const exportEntities = useCallback(
    async (ids: string[]) => {
      try {
        downloadFile(await source.exportEntities(ids));
      } catch (error) {
        notificationService.requestFailed(
          `Failed to export ${source.entityName}`,
          error,
        );
      }
    },
    [source, notificationService],
  );

  const chainNames = useMemo(() => toNameMap(chains), [chains]);
  const elementNames = useMemo(() => toNameMap(elements), [elements]);

  const getChainName = useCallback(
    (id: string | null | undefined) => (id ? (chainNames.get(id) ?? id) : ""),
    [chainNames],
  );

  const getElementName = useCallback(
    (id: string | null | undefined) => (id ? (elementNames.get(id) ?? id) : ""),
    [elementNames],
  );

  return {
    items,
    isLoading,
    allLoaded,
    loadMore,
    refresh,
    getChainName,
    getElementName,
    resolveTargetIds,
    exportEntities,
  };
}
