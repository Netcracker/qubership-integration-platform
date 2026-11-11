import type { CatalogChangePayload } from "../ai/modelProviders/types.ts";
import { parseCatalogChange } from "../ai/modelProviders/sseParsing.ts";

const EVENT = "qip-catalog-changed";

export function publishCatalogChange(change: CatalogChangePayload): void {
  if (typeof window !== "undefined") {
    window.dispatchEvent(new CustomEvent(EVENT, { detail: change }));
  }
}

export function subscribeCatalogChanges(
  listener: (change: CatalogChangePayload) => void,
): () => void {
  if (typeof window === "undefined") return () => undefined;
  const receive = (event: Event) => {
    const change = parseCatalogChange((event as CustomEvent<unknown>).detail);
    if (change) listener(change);
  };
  window.addEventListener(EVENT, receive);
  return () => window.removeEventListener(EVENT, receive);
}
