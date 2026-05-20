import { api } from "../api/api.ts";
import {
  ActionLog,
  ActionLogResponse,
  ActionLogSearchRequest,
} from "../api/apiTypes.ts";
import {
  InfiniteData,
  InfiniteQueryObserverResult,
  useInfiniteQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { useRef } from "react";
import { useNotificationService } from "./useNotificationService.tsx";
import { Register } from "react-router";

const RANGE_DEFAULT = 1000 * 60 * 60 * 24; // 1 day in ms
const RANGE_MIN = 1000 * 60 * 60 * 6; // 6 hours
const RANGE_MAX = 1000 * 60 * 60 * 48; // 2 days
const CONTENT_SIZE_THRESHOLD = 200;

type RequestState = {
  offset: number;
  range: number;
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

  const requestStateRef = useRef<RequestState>({
    offset: Date.now(),
    range: RANGE_DEFAULT,
    stopped: false,
  });

  const queryClient = useQueryClient();
  const allLogsRef = useRef(new Map<string, ActionLog>());

  const getSortedLogs = () =>
    Array.from(allLogsRef.current.values()).sort(
      (a, b) => b.actionTime - a.actionTime,
    );

  const actionLogsQuery = useInfiniteQuery({
    initialPageParam: Date.now(),
    queryKey: ["actionLogs"],
    queryFn: async () => {
      const state = requestStateRef.current;
      if (state.stopped) {
        return getSortedLogs();
      }

      const requestedRange = state.range;
      const searchRequest: ActionLogSearchRequest = {
        offsetTime: state.offset,
        rangeTime: requestedRange,
      };

      let response: ActionLogResponse;
      try {
        response = await api.loadCatalogActionsLog(searchRequest);
      } catch (err) {
        notificationService.requestFailed(
          "Failed to retrieve action logs from Catalog",
          err,
        );
        state.stopped = true;
        return getSortedLogs();
      }

      const { actionLogs, recordsAfterRange } = response;
      const hasLogs = actionLogs.length > 0;
      const hasMore = recordsAfterRange > 0;
      const logCount = actionLogs.length;

      if (!hasLogs && hasMore) {
        state.range = Math.min(state.range * 2, RANGE_MAX);
      } else if (hasLogs) {
        state.range =
          logCount > CONTENT_SIZE_THRESHOLD ? RANGE_MIN : RANGE_DEFAULT;
      }

      // Move window back by the range used in the request.
      state.offset -= requestedRange;
      if (!hasMore) state.stopped = true;

      actionLogs.forEach((log) => allLogsRef.current.set(log.id, log));

      return getSortedLogs();
    },
    getNextPageParam: () =>
      requestStateRef.current.stopped ? undefined : Date.now(),
  });

  const logsData =
    actionLogsQuery.data?.pages[actionLogsQuery.data.pages.length - 1] ?? [];

  const refresh = async () => {
    allLogsRef.current.clear();
    requestStateRef.current = {
      offset: Date.now(),
      range: RANGE_DEFAULT,
      stopped: false,
    };
    await queryClient.resetQueries({
      queryKey: ["actionLogs"],
      exact: true,
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
