import { useCallback, useRef, useState } from "react";
import { useNavigate } from "react-router";
import { api } from "../../api/api.ts";
import { TestsRunSource } from "../../api/apiTypes.ts";
import { useNotificationService } from "../useNotificationService.tsx";

const TESTS_RUNS_PATH = "/admintools/testing/test-runs";

export type UseTestsRunStarterOptions = {
  /** Set when the list is scoped to a chain, which reads the notification differently. */
  chainId?: string;
  /** The entity kind the selected ids belong to; left out for test cases. */
  source?: TestsRunSource;
  /** Ids the current selection stands for. Memoize it. */
  collectTargetIds: () => Promise<string[]>;
  /** Runs after a run has started, for a list the new run belongs in. Memoize it. */
  onStarted?: () => void;
};

export type TestsRunStarter = {
  /** True while a run is being started, which is when the action stays inert. */
  isStarting: boolean;
  startRun: () => Promise<void>;
};

/**
 * Starts a test run over the rows a testing list has selected, one at a time: the
 * service answers with the id of a new run every time it is asked, so a second
 * click before the first answer arrives would start a second run over the same
 * cases. The ref holds the door even where a disabled button cannot, such as a
 * click that lands before the button has rendered again.
 */
export function useTestsRunStarter({
  chainId,
  source,
  collectTargetIds,
  onStarted,
}: UseTestsRunStarterOptions): TestsRunStarter {
  const navigate = useNavigate();
  const notificationService = useNotificationService();
  const [isStarting, setIsStarting] = useState(false);
  const startingRef = useRef(false);

  const startRun = useCallback(async () => {
    if (startingRef.current) {
      return;
    }
    startingRef.current = true;
    setIsStarting(true);
    try {
      const ids = await collectTargetIds();
      if (ids.length === 0) {
        return;
      }
      const runId = await api.startTestsRun(ids, source);
      // The run lives under Admin Tools, which chain rights alone do not open, so
      // the chain scope names the run rather than linking into a section the
      // reader may not reach.
      notificationService.info(
        "Test run started",
        chainId ? (
          runId
        ) : (
          <a onClick={() => void navigate(`${TESTS_RUNS_PATH}/${runId}`)}>
            {runId}
          </a>
        ),
      );
      onStarted?.();
    } catch (error) {
      notificationService.requestFailed("Failed to start a test run", error);
    } finally {
      startingRef.current = false;
      setIsStarting(false);
    }
  }, [
    chainId,
    source,
    collectTargetIds,
    onStarted,
    navigate,
    notificationService,
  ]);

  return { isStarting, startRun };
}
