import { api } from "../api/api.ts";
import { ActionLog } from "../api/apiTypes.ts";
import {
  InfiniteData,
  InfiniteQueryObserverResult,
  useInfiniteQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { useRef, useEffect } from "react";
import { useNotificationService } from "./useNotificationService.tsx";
import { Register } from "react-router";

const PAGE_SIZE = 100;

type RequestState = {
  rowOffset: number;
  stopped: boolean;
};

export const useActionLog = (): {
  fetchNextPage: () => Promise<
    InfiniteQueryObserverResult<
      InfiniteData<ActionLog[]>,
      Register extends {
        defaultError: infer TError;
      }
        ? TError
        : Error
    >
  >;
  logsData: ActionLog[];
  hasNextPage: boolean;
  isFetching: boolean;
  isLoading: boolean;
  refresh: () => Promise<void>;
} => {
  const notificationService = useNotificationService();

  useEffect(() => {
    void refresh();
  }, []);

  const requestStateRef = useRef<RequestState>({
    rowOffset: 0,
    stopped: false,
  });

  const queryClient = useQueryClient();
  const allLogsRef = useRef(new Map<string, ActionLog>());

  const getSortedLogs = () =>
    Array.from(allLogsRef.current.values()).sort(
      (a, b) => b.actionTime - a.actionTime,
    );

  const actionLogsQuery = useInfiniteQuery({
    initialPageParam: 0,
    queryKey: ["actionLogs"],
    queryFn: async () => {
      const state = requestStateRef.current;
      if (state.stopped) {
        return getSortedLogs();
      }

      try {
        const response = await api.loadCatalogActionsLogV2({
          offset: state.rowOffset,
          limit: PAGE_SIZE,
          filters: [],
        });

        response.actionLogs.forEach((log) =>
          allLogsRef.current.set(log.id, log),
        );

        state.rowOffset = response.offset;
        if (response.actionLogs.length < PAGE_SIZE) {
          state.stopped = true;
        }
      } catch (err) {
        notificationService.requestFailed(
          "Failed to retrieve action logs from Catalog",
          err,
        );
        state.stopped = true;
      }

      return getSortedLogs();
    },
    getNextPageParam: () =>
      requestStateRef.current.stopped ? undefined : requestStateRef.current.rowOffset,
  });

  const logsData =
    actionLogsQuery.data?.pages[actionLogsQuery.data.pages.length - 1] ?? [];

  const refresh = async () => {
    allLogsRef.current.clear();
    requestStateRef.current = {
      rowOffset: 0,
      stopped: false,
    };
    await queryClient.resetQueries({
      queryKey: ["actionLogs"],
      exact: true
    });
  };

  return {
    logsData,
    fetchNextPage: () => actionLogsQuery.fetchNextPage(),
    hasNextPage: actionLogsQuery.hasNextPage,
    isFetching: actionLogsQuery.isFetching,
    isLoading: actionLogsQuery.isLoading,
    refresh,
  };
};
