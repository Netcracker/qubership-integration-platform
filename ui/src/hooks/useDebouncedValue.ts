import { useCallback, useEffect, useState } from "react";

/**
 * The value a burst of changes settles on: every change restarts the timer, so a
 * caller acts once the changes stop rather than once per keystroke. The change
 * still waiting when the component goes is dropped.
 *
 * The second element skips the wait, for the Enter key and the submit button a
 * search box offers beside the typing.
 */
export function useDebouncedValue<T>(value: T, delay: number): [T, () => void] {
  const [debouncedValue, setDebouncedValue] = useState(value);

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedValue(value), delay);
    return () => clearTimeout(timer);
  }, [value, delay]);

  // The timer still pending settles on the same value, so it needs no cancelling.
  const flush = useCallback(() => setDebouncedValue(value), [value]);

  return [debouncedValue, flush];
}
