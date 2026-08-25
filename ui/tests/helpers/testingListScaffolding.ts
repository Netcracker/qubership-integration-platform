import type React from "react";

/** A column as the page hands it to the table, before the table reads it. */
export type RenderedColumn = {
  key?: React.Key;
  title?: React.ReactNode;
  sorter?: unknown;
};

export type CapturedConfirm = {
  title: React.ReactNode;
  content?: React.ReactNode;
  onOk: () => unknown;
};

/**
 * What a testing list screen hands out on the way to the table and to the
 * confirmation dialog, held for the assertions. One object, `mock`-prefixed, so a
 * hoisted `jest.mock` factory may reference it.
 */
export const mockListScaffolding = {
  columns: [] as RenderedColumn[],
  confirm: undefined as CapturedConfirm | undefined,

  recordColumns(columns: unknown): void {
    mockListScaffolding.columns = (columns ?? []) as RenderedColumn[];
  },

  captureConfirm(options: CapturedConfirm): void {
    mockListScaffolding.confirm = options;
  },

  reset(): void {
    mockListScaffolding.columns = [];
    mockListScaffolding.confirm = undefined;
  },
};
