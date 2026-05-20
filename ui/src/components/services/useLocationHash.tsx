import { useEffect, useState } from "react";

export function useLocationHash(
  defaultValue: string,
): [string, (value: string) => void] {
  const [value, setValue] = useState(
    () => location.hash.slice(1) || defaultValue,
  );

  useEffect(() => {
    const onHashChange = () => {
      setValue(location.hash.slice(1) || defaultValue);
    };
    window.addEventListener("hashchange", onHashChange);
    return () => window.removeEventListener("hashchange", onHashChange);
  }, [defaultValue]);

  const navigate = (nextTab: string) => {
    window.location.hash = nextTab;
  };

  return [value, navigate];
}
