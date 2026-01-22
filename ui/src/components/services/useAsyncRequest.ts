import { useCallback, useRef, useState, useEffect } from "react";

interface AsyncRequestOptions<T> {
  immediate?: boolean;
  initialValue?: T | null;
  throwOnError?: boolean;
}

interface AsyncRequestResult<T, Args extends unknown[]> {
  value: T | null;
  loading: boolean;
  error: string | null;
  execute: (...args: Args | []) => Promise<T | undefined>;
  reset: () => void;
}

export function useAsyncRequest<
  T = unknown,
  Args extends unknown[] = unknown[],
>(
  asyncFn: (...args: Args) => Promise<T>,
  options: AsyncRequestOptions<T> = {},
): AsyncRequestResult<T, Args> {
  const {
    immediate = false,
    initialValue = null,
    throwOnError = false,
  } = options;
  const [value, setValue] = useState<T | null>(initialValue);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const isMounted = useRef(true);
  const lastArgs = useRef<Args>();

  const execute = useCallback(
    async (...args: Args | []) => {
      setLoading(true);
      setError(null);
      lastArgs.current = args as Args;
      try {
        const result = await asyncFn(...(args as Args));
        if (isMounted.current) {
          setValue(result);
        }
        return result;
      } catch (e: unknown) {
        let message = "Unknown error";
        if (typeof e === "string") message = e;
        else if (
          e &&
          typeof e === "object" &&
          "message" in e &&
          typeof (e as Record<string, unknown>).message === "string"
        )
          message = (e as Record<string, unknown>).message as string;
        if (isMounted.current) {
          setError(message);
        }
        if (throwOnError) throw e;
        return undefined;
      } finally {
        if (isMounted.current) {
          setLoading(false);
        }
      }
    },
    [asyncFn, throwOnError],
  );

  const reset = useCallback(() => {
    setValue(initialValue);
    setError(null);
    setLoading(false);
  }, [initialValue]);

  useEffect(() => {
    isMounted.current = true;
    return () => {
      isMounted.current = false;
    };
  }, []);

  useEffect(() => {
    if (immediate) {
      void execute(...([] as unknown as Args));
    }
  }, [immediate]);

  return { value, loading, error, execute, reset };
}
