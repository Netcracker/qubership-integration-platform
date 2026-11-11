import { useCallback, useEffect, useMemo, useRef } from "react";
import type { CatalogChangePayload } from "../ai/modelProviders/types.ts";
import { subscribeCatalogChanges } from "../misc/catalog-change-events.ts";

/** Serializes reads for one view and repeats once when a change arrives during its current read. */
export function useCatalogRefresh(
  key: string,
  matches: (change: CatalogChangePayload) => boolean,
  load: (isCurrent: () => boolean) => Promise<void>,
  blocked = false,
) {
  const callbacks = useRef({ matches, load, blocked });
  callbacks.current = { matches, load, blocked };
  const state = useMemo(
    () => ({
      key,
      active: true,
      pending: false,
      flight: null as Promise<void> | null,
    }),
    [key],
  );
  const refresh = useCallback((): Promise<void> => {
    state.pending = true;
    if (!state.flight && !callbacks.current.blocked) {
      state.flight = Promise.resolve()
        .then(async () => {
          let failure: Error | undefined;
          while (state.active && state.pending && !callbacks.current.blocked) {
            state.pending = false;
            try {
              await callbacks.current.load(
                () => state.active && !callbacks.current.blocked,
              );
              failure = undefined;
            } catch (error) {
              failure =
                error instanceof Error ? error : new Error(String(error));
            }
          }
          if (failure) throw failure;
        })
        .finally(() => {
          state.flight = null;
        });
    }
    return state.flight ?? Promise.resolve();
  }, [state]);
  useEffect(() => {
    state.active = true;
    const unsubscribe = subscribeCatalogChanges((change) => {
      if (callbacks.current.matches(change))
        void refresh().catch(() => undefined);
    });
    return () => {
      state.active = false;
      unsubscribe();
    };
  }, [refresh, state]);
  useEffect(() => {
    if (blocked && state.flight) state.pending = true;
    if (!blocked && state.pending) void refresh().catch(() => undefined);
  }, [blocked, refresh, state]);
  const waitForIdle = useCallback(async () => {
    if (state.pending && !callbacks.current.blocked) await refresh();
    else if (state.flight) await state.flight;
  }, [refresh, state]);
  return { refresh, waitForIdle };
}
