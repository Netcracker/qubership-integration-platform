import { SystemOperation } from "../api/apiTypes.ts";

export function uniqueMapById(
  items: SystemOperation[],
): Map<string, SystemOperation> {
  const m = new Map<string, SystemOperation>();
  items.forEach((op) => {
    if (!m.has(op.id)) {
      m.set(op.id, op);
    }
  });
  return m;
}

export function uniqueListById(items: SystemOperation[]): SystemOperation[] {
  return Array.from(uniqueMapById(items).values());
}
