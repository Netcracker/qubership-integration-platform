import { useEffect, useRef } from "react";
import type { FormInstance } from "antd";
import type { Domain } from "../components/SelectDomains.tsx";

export function useInitialDomains<T extends { domains: Domain[] }>(
  form: FormInstance<T>,
  initialDomains?: Domain[],
): void {
  const applied = useRef(false);
  useEffect(() => {
    if (applied.current) {
      return;
    }
    applied.current = true;
    form.setFieldsValue({ domains: initialDomains ?? [] } as Partial<T>);
  }, [form, initialDomains]);
}
