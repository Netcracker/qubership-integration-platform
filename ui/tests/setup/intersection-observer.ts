/**
 * Global IntersectionObserver mock for jsdom (which has no native impl).
 *
 * Captures observer callbacks so tests can synchronously trigger
 * intersection events via `triggerIntersection()`.
 */
import { jest } from "@jest/globals";

type IOEntryLike = { isIntersecting: boolean; target: Element };
type IOCallback = (entries: IOEntryLike[]) => void;

interface ActiveObserver {
  callback: IOCallback;
  targets: Set<Element>;
}

const activeObservers = new Set<ActiveObserver>();

class MockIntersectionObserver {
  private record: ActiveObserver;

  constructor(callback: IOCallback) {
    this.record = { callback, targets: new Set<Element>() };
    activeObservers.add(this.record);
  }

  observe(target: Element): void {
    this.record.targets.add(target);
  }

  unobserve(target: Element): void {
    this.record.targets.delete(target);
  }

  disconnect(): void {
    this.record.targets.clear();
    activeObservers.delete(this.record);
  }

  takeRecords(): IOEntryLike[] {
    return [];
  }
}

if (typeof globalThis !== "undefined") {
  (
    globalThis as unknown as { IntersectionObserver: unknown }
  ).IntersectionObserver = MockIntersectionObserver;
}
if (typeof window !== "undefined") {
  (
    window as unknown as { IntersectionObserver: unknown }
  ).IntersectionObserver = MockIntersectionObserver;
}

/**
 * Fire an intersection event for every currently-observed target.
 * Defaults to `isIntersecting: true` — i.e. "the sentinel just scrolled into view".
 */
export function triggerIntersection(isIntersecting = true): void {
  activeObservers.forEach(({ callback, targets }) => {
    const entries: IOEntryLike[] = Array.from(targets).map((target) => ({
      isIntersecting,
      target,
    }));
    if (entries.length > 0) callback(entries);
  });
}

/** Test isolation helper — drops all observers tracked across tests. */
export function resetIntersectionObservers(): void {
  activeObservers.clear();
}

afterEach(() => {
  resetIntersectionObservers();
});

// Reference jest to keep the import non-empty under strict ESM.
void jest;
