import { useCallback, useMemo, useRef, useState } from "react";
import type { ActivityStepPayload } from "./activityTypes.ts";

export interface ActivityStore {
  applyStep(step: ActivityStepPayload): void;
  markRunningCancelled(): void;
  reset(): void;
  getRows(): ActivityStepPayload[];
  getOrientationLabel(): string | undefined;
}

export function createActivityStore(): ActivityStore {
  const rowsById = new Map<string, ActivityStepPayload>();
  const insertionOrder: string[] = [];

  const getRows = (): ActivityStepPayload[] =>
    insertionOrder
      .map((id) => rowsById.get(id))
      .filter((row): row is ActivityStepPayload => row !== undefined);

  const getOrientationLabel = (): string | undefined => {
    const rows = getRows();
    for (let i = rows.length - 1; i >= 0; i -= 1) {
      const row = rows[i];
      if (
        (row.kind === "skill" || row.kind === "pipeline") &&
        row.status === "running"
      ) {
        return row.label ?? row.id;
      }
    }
    const lastSkillOrPipeline = [...rows]
      .reverse()
      .find((row) => row.kind === "skill" || row.kind === "pipeline");
    return lastSkillOrPipeline?.label ?? lastSkillOrPipeline?.id;
  };

  return {
    applyStep(step: ActivityStepPayload) {
      if (!rowsById.has(step.id)) {
        insertionOrder.push(step.id);
      }
      rowsById.set(step.id, { ...step });
    },
    markRunningCancelled() {
      for (const id of insertionOrder) {
        const row = rowsById.get(id);
        if (row?.status === "running") {
          rowsById.set(id, { ...row, status: "cancelled" });
        }
      }
    },
    reset() {
      rowsById.clear();
      insertionOrder.length = 0;
    },
    getRows,
    getOrientationLabel,
  };
}

export function useActivityStore(): ActivityStore & {
  rows: ActivityStepPayload[];
  orientationLabel: string | undefined;
  version: number;
} {
  const storeRef = useRef<ActivityStore>();
  if (!storeRef.current) {
    storeRef.current = createActivityStore();
  }
  const [version, setVersion] = useState(0);
  const bump = useCallback(() => setVersion((v) => v + 1), []);

  return useMemo(() => {
    const store = storeRef.current!;
    return {
      applyStep(step: ActivityStepPayload) {
        store.applyStep(step);
        bump();
      },
      markRunningCancelled() {
        store.markRunningCancelled();
        bump();
      },
      reset() {
        store.reset();
        bump();
      },
      getRows: () => store.getRows(),
      getOrientationLabel: () => store.getOrientationLabel(),
      get rows() {
        return store.getRows();
      },
      get orientationLabel() {
        return store.getOrientationLabel();
      },
      version,
    };
  }, [bump, version]);
}
