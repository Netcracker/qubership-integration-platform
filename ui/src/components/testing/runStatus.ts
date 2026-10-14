import { TestRunStatus } from "../../api/apiTypes.ts";

/**
 * A case run is cancelled only while it is still queued: the service updates the
 * rows it selects `where status = 'pending'` and leaves every other one alone,
 * answering 204 either way. A button offered over a started or finished run would
 * report a success that changed nothing.
 */
export function isTestCaseRunCancellable(
  status: TestRunStatus | null,
): boolean {
  return status === TestRunStatus.PENDING;
}

/**
 * A test run reports the lowest status among its cases, with pending read as
 * running and skipped as finished. Only `finished` therefore proves that no case
 * is still queued - `canceled` wins the comparison over `running` and can hide a
 * queued case behind an already cancelled one.
 */
export function isTestsRunCancellable(status: TestRunStatus | null): boolean {
  return status === TestRunStatus.RUNNING || status === TestRunStatus.CANCELED;
}
