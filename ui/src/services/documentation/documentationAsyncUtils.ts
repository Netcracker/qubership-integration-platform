export function createLatestOnlyGuard(): {
  nextToken: () => number;
  isLatest: (token: number) => boolean;
} {
  let current = 0;
  return {
    nextToken: () => {
      current += 1;
      return current;
    },
    isLatest: (token: number) => token === current,
  };
}

export function createDebouncedCallback<TArgs extends unknown[]>(
  delayMs: number,
  fn: (...args: TArgs) => void,
): {
  call: (...args: TArgs) => void;
  cancel: () => void;
} {
  let timer: ReturnType<typeof setTimeout> | null = null;

  return {
    call: (...args: TArgs) => {
      if (timer) {
        clearTimeout(timer);
      }
      timer = setTimeout(() => fn(...args), delayMs);
    },
    cancel: () => {
      if (timer) {
        clearTimeout(timer);
        timer = null;
      }
    },
  };
}
