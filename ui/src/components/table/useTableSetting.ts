import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type SetStateAction,
} from "react";
import { parseJsonOrDefault } from "../../misc/json-helper";

const RESET_EVENT = "qip-reset-table-settings";

export function resetTableSettings(storageKey: string) {
  window.dispatchEvent(new CustomEvent(RESET_EVENT, { detail: storageKey }));
}

export function useTableSetting<T>(
  storageKey: string | undefined,
  setting: string,
  initialValue: T,
) {
  const key = storageKey ? `${storageKey}_${setting}` : undefined;
  const initial = useRef(initialValue);
  initial.current = initialValue;
  const read = () =>
    key
      ? (parseJsonOrDefault<T>(
          localStorage.getItem(key) ?? "null",
          initial.current,
        ) ?? initial.current)
      : initial.current;
  const [state, setState] = useState(() => ({ key, value: read() }));
  if (state.key !== key) setState({ key, value: read() });

  const setValue = useCallback(
    (action: SetStateAction<T>) => {
      setState((previous) => ({
        key,
        value:
          typeof action === "function"
            ? (action as (value: T) => T)(previous.value)
            : action,
      }));
    },
    [key],
  );

  useEffect(() => {
    if (key && state.key === key)
      localStorage.setItem(key, JSON.stringify(state.value));
  }, [key, state]);

  useEffect(() => {
    const reset = (event: Event) => {
      if (storageKey && (event as CustomEvent<string>).detail === storageKey) {
        setValue(initial.current);
      }
    };
    window.addEventListener(RESET_EVENT, reset);
    return () => window.removeEventListener(RESET_EVENT, reset);
  }, [storageKey, setValue]);

  return [state.value, setValue] as const;
}
