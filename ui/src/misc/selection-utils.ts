import type { Key } from "react";

export function toStringIds(selectedRowKeys: Key[]): string[] {
  return selectedRowKeys.map((key) => key.toString());
}

export function filterOutByIds<T extends { id: string }>(
  items: T[] | undefined,
  ids: string[],
): T[] {
  return (items ?? []).filter((item) => !ids.includes(item.id));
}
