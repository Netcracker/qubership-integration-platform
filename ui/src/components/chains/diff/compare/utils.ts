import { Connection, Element } from "../../../../api/apiTypes.ts";

export function sortElementsTopologically(
  elements: Element[],
  connections: Connection[],
): Element[] {
  const idToElement = new Map<string, Element>(elements.map((e) => [e.id, e]));
  const connectionMap = new Map<string, string[]>();
  const revertedConnectionMap = new Map<string, string[]>();
  connections.forEach((c) => {
    if (connectionMap.has(c.from)) {
      connectionMap.get(c.from)?.push(c.to);
    } else {
      connectionMap.set(c.from, [c.to]);
    }
    if (revertedConnectionMap.has(c.to)) {
      revertedConnectionMap.get(c.to)?.push(c.from);
    } else {
      revertedConnectionMap.set(c.to, [c.from]);
    }
  });

  const nodes = sortBy(
    elements,
    extractValues([
      (e) => (revertedConnectionMap.get(e.id) ?? []).length,
      (e) => e.parentElementId ?? "",
    ]),
    compareArraysLexicographically,
  );
  const sorted: Element[] = [];
  const visited = new Set<string>();

  function visit(element: Element) {
    if (visited.has(element.id)) return;
    visited.add(element.id);
    sorted.push(element);
    (connectionMap.get(element.id) ?? [])
      .map((id) => idToElement.get(id))
      .filter((e) => !!e)
      .forEach(visit);
  }

  nodes.forEach(visit);
  return sorted;
}

export function sortBy<T, U>(
  list: T[],
  criteria: (v: T) => U,
  comparator: (v1: U, v2: U) => number,
): T[] {
  return [...list].sort((i0, i1) => {
    const v1 = criteria(i0);
    const v2 = criteria(i1);
    return comparator(v1, v2);
  });
}

export function extractValues<T>(
  keys: (keyof T | ((v: T) => unknown))[],
): (v: T) => unknown[] {
  return (obj: T) =>
    keys.map((keyOrExtractFn) =>
      typeof keyOrExtractFn === "function"
        ? keyOrExtractFn(obj)
        : obj[keyOrExtractFn],
    );
}

export function compareArraysLexicographically<T>(a: T[], b: T[]): number {
  const minLength = Math.min(a.length, b.length);

  for (let i = 0; i < minLength; i++) {
    if (a[i] < b[i]) return -1;
    if (a[i] > b[i]) return 1;
  }

  // If all elements up to minLength are equal,
  // the shorter array comes first.
  return a.length - b.length;
}

export function intersection<T>(s1: Set<T>, s2: Set<T>): Set<T> {
  return new Set([...s1].filter((x) => s2.has(x)));
}
