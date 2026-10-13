import type React from "react";
import { useCallback, useState } from "react";

export interface TableRowExpandCollapseOptions<T> {
  getSubtreeIds: (record: T) => React.Key[];
  ensureLoaded?: (record: T) => Promise<React.Key[]>;
}

export function mergeExpandedKeys(
  prev: React.Key[],
  ids: React.Key[],
): React.Key[] {
  return [...new Set([...prev, ...ids])];
}

export function removeExpandedKeys(
  prev: React.Key[],
  ids: React.Key[],
): React.Key[] {
  const subtree = new Set(ids);
  return prev.filter((k) => !subtree.has(k));
}

export function useTableRowExpandCollapse<T>({
  getSubtreeIds,
  ensureLoaded,
}: TableRowExpandCollapseOptions<T>) {
  const [expandedRowKeys, setExpandedRowKeys] = useState<React.Key[]>([]);

  const expandSubtree = useCallback(
    async (record: T): Promise<void> => {
      const ids = ensureLoaded
        ? await ensureLoaded(record)
        : getSubtreeIds(record);
      if (ids.length > 0) {
        setExpandedRowKeys((prev) => mergeExpandedKeys(prev, ids));
      }
    },
    [ensureLoaded, getSubtreeIds],
  );

  const collapseSubtree = useCallback(
    (record: T): void => {
      const ids = getSubtreeIds(record);
      if (ids.length > 0) {
        setExpandedRowKeys((prev) => removeExpandedKeys(prev, ids));
      }
    },
    [getSubtreeIds],
  );

  return {
    expandedRowKeys,
    setExpandedRowKeys,
    expandSubtree,
    collapseSubtree,
  };
}
