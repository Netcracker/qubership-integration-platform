import { api } from "../api/api.ts";
import { ActionLog, ActionLogFilterRequest } from "../api/apiTypes.ts";
import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useMemo, useEffect } from "react";
import { useNotificationService } from "./useNotificationService.tsx";

const PAGE_SIZE = 20;

type ActionLogPage = {
  logs: ActionLog[];
  nextOffset: number;
};

function sortLogsByActionTime(logs: ActionLog[]): ActionLog[] {
  return [...logs].sort((a, b) => b.actionTime - a.actionTime);
}

export const useActionLog = (
  filters: ActionLogFilterRequest[] = [],
): {
  fetchNextPage: () => Promise<void>;
  logsData: ActionLog[];
  hasNextPage: boolean;
  isFetching: boolean;
  isLoading: boolean;
  refresh: () => Promise<void>;
} => {
  const notificationService = useNotificationService();
  const queryClient = useQueryClient();

   useEffect(() => {
     void refresh();
   }, []);

  const actionLogsQuery = useInfiniteQuery({
    queryKey: ["actionLogs", filters],
    initialPageParam: 0,
    queryFn: async ({ pageParam }): Promise<ActionLogPage> => {
      try {
        const response = await api.loadCatalogActionsLogV2({
          offset: pageParam,
          limit: PAGE_SIZE,
          filters,
        });
        return {
          logs: response.actionLogs,
          nextOffset: response.offset,
        };
      } catch (err) {
        notificationService.requestFailed(
          "Failed to retrieve action logs from Catalog",
          err,
        );
        return { logs: [], nextOffset: pageParam };
      }
    },
    getNextPageParam: (lastPage) =>
      lastPage.logs.length < PAGE_SIZE ? undefined : lastPage.nextOffset,
  });

  const logsData = useMemo(
    () =>
      sortLogsByActionTime(
        actionLogsQuery.data?.pages.flatMap((page) => page.logs) ?? [],
      ),
    [actionLogsQuery.data],
  );

  const refresh = useCallback(async () => {
    await queryClient.resetQueries({ queryKey: ["actionLogs", filters] });
  }, [queryClient, filters]);

  return {
    logsData,
    fetchNextPage: async () => {
      await actionLogsQuery.fetchNextPage();
    },
    hasNextPage: actionLogsQuery.hasNextPage,
    isFetching: actionLogsQuery.isFetching,
    isLoading: actionLogsQuery.isLoading,
    refresh,
  };
};
