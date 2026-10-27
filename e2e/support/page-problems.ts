/**
 * What the page guard in `page-guard.ts` collects, and the one kind of failed answer it lets through.
 */
import type { Catalog } from "./catalog.js";

/** One thing the guard caught, with the route the page was on when it happened. */
export interface PageProblem {
  route: string;
  message: string;
  /** The resource an answer or a console error is about, when it names one. */
  url?: string;
  /** The micro domain a failed checkpoint lookup addressed. */
  domain?: string;
}

/**
 * The checkpoint lookup the admin Sessions page sends to the engine of each micro domain its
 * sessions name. A session outlives its domain, so once a k8s case deletes its domain the lookup
 * answers 502 through the proxy, and `Sessions.tsx` leaves those sessions without checkpoints (#1137).
 * The guard lets that answer through for a domain the catalog no longer lists, and for no other.
 */
export const MICRO_CHECKPOINTS = /^\/api\/v1\/[^/]+\/engine\/([^/]+)\/sessions$/;

/** The problems left once the checkpoint lookups of deleted micro domains, and their console errors, are dropped. */
export async function withoutDeletedDomains(problems: readonly PageProblem[], catalog: Pick<Catalog, "listDomains">): Promise<PageProblem[]> {
  if (!problems.some((each) => each.domain)) return [...problems];
  const listed = new Set((await catalog.listDomains()).map((each) => each.name));
  const dropped = new Set(problems.filter((each) => each.domain && !listed.has(each.domain)).map((each) => each.url));
  return problems.filter((each) => !(each.url && dropped.has(each.url)));
}
