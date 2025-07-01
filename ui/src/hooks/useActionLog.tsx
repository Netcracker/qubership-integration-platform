import { api } from "../api/api.ts";
import {ActionLog, ActionLogResponse, ActionLogSearchRequest, RestApiError} from "../api/apiTypes.ts";
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

type ActionLogSourcePair = {
    sourceName: string,
    fn: (searchRequest: ActionLogSearchRequest) => Promise<ActionLogResponse | RestApiError>,
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

  const logSources: ActionLogSourcePair[] = [
      {
          sourceName: "Catalog",
          fn: (searchRequest) => api.loadCatalogActionsLog(searchRequest)
      },
      {
          sourceName: "Variables Management",
          fn: (searchRequest) => api.loadVariablesManagementActionsLog(searchRequest)
      },
  ];

  const sourceRequestStateRef = useRef(
    logSources.map(() => ({
      offset: Date.now(),
      range: RANGE_DEFAULT,
      stopped: false,
    })),
  );

  const queryClient = useQueryClient();

  const allLogsRef = useRef(new Map<string, ActionLog>());

  const actionLogsQuery = useInfiniteQuery({
    initialPageParam: Date.now(),
    queryKey: ["actionLogs"],
    queryFn: async () => {
      const requests = logSources.map((pair, i) => {
        const sourceRequestState = sourceRequestStateRef.current[i];
        if (sourceRequestState.stopped) return Promise.resolve(undefined);
        return pair.fn({
          offsetTime: sourceRequestState.offset,
          rangeTime: sourceRequestState.range,
        });
      });

      const responsesPromiseSettledResult: Array<PromiseSettledResult<Awaited<Promise<Awaited<undefined>> | Promise<ActionLogResponse | RestApiError>>>> = await Promise.allSettled(requests);

      responsesPromiseSettledResult.forEach((res, i) => {
        const currentRequestState = sourceRequestStateRef.current[i];

        if (res.status === "rejected") {
          const errorResponse: RestApiError = res.reason as RestApiError;
          notificationService.requestFailed("Failed to retrieve action logs from " + logSources[i].sourceName, {
            type: "error",
            message: errorResponse,
          });
          currentRequestState.stopped = true;
          return;
        }

        if (res.status === "fulfilled") {
          if (!res) return;

          const fulfilledResponse: ActionLogResponse =
            res.value as ActionLogResponse;
          const { actionLogs, recordsAfterRange } = fulfilledResponse;
          const hasLogs = actionLogs.length > 0;
          const hasMore = recordsAfterRange > 0;
          const logCount = actionLogs.length;

          if (!hasLogs && hasMore) {
            currentRequestState.range = Math.min(
              currentRequestState.range * 2,
              RANGE_MAX,
            );
          } else if (hasLogs) {
            currentRequestState.range =
              logCount > CONTENT_SIZE_THRESHOLD ? RANGE_MIN : RANGE_DEFAULT;
          }

          currentRequestState.offset -= currentRequestState.range;

          if (!hasMore) currentRequestState.stopped = true;

          actionLogs.forEach((log) => allLogsRef.current.set(log.id, log));
        }
      });

      return Array.from(allLogsRef.current.values()).sort(
        (a, b) => b.actionTime - a.actionTime,
      );
    },
    getNextPageParam: () => {
      return sourceRequestStateRef.current.some((s) => !s.stopped)
        ? Date.now()
        : undefined;
    },
  });

  const logsData =
    actionLogsQuery.data?.pages[actionLogsQuery.data.pages.length - 1] ?? [];

  const refresh = async () => {
    allLogsRef.current.clear();
    sourceRequestStateRef.current = logSources.map(() => ({
      offset: Date.now(),
      range: RANGE_DEFAULT,
      stopped: false,
    }));

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
