/** A `.catch` handler that logs a failed cleanup, so it cannot replace what the case failed on. */
export function leftBehind(what: string, undone = "deleted"): (cause: unknown) => void {
  return (cause) => console.error(`[teardown] ${what} was not ${undone}: ${String(cause)}`);
}
