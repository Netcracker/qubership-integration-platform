/**
 * What the catalog client puts on the wire, read off a transport that answers without a stack.
 *
 * A helper that reads a listing is served whatever page the service defaults to, and the catalog's
 * listings carry neither a total nor a next-page marker, so a truncated answer is shaped exactly
 * like a complete one. `listOperations` was written without `count` and stayed green for as long as
 * its fixtures held three operations. The check belongs here rather than in `specs/api` because the
 * question is what the request says: against a stack the same case proves only that the corpus it
 * happens to hold is smaller than one page.
 *
 * The client is built on a base carrying a path segment of its own, so the calls below match no row
 * in the operation registry. That matters because the transport records every call it makes, and a
 * stubbed call recorded as reached would let `reconcile` credit an operation no run ever made. The
 * second case is what keeps it true.
 */
import { test, expect } from "@playwright/test";
import type { APIRequestContext } from "@playwright/test";
import { Catalog } from "../../support/catalog.js";
import { matchOperationPath } from "../../registry/operations.js";

/** A catalog host that exists nowhere, under a path prefix the registry does not describe. */
const BASE = "http://catalog.invalid/stub";

/** A catalog whose transport answers `[]` to everything and keeps the URLs it was handed. */
function recordingCatalog(urls: string[]): Catalog {
  const api = {
    fetch(url: string) {
      urls.push(url);
      return Promise.resolve({ ok: () => true, text: () => Promise.resolve("[]") });
    },
  };
  return new Catalog(api as unknown as APIRequestContext, BASE);
}

test("listOperations asks for the whole listing, not the default page", { tag: ["@infra", "@tier1"] }, async () => {
  const urls: string[] = [];
  await recordingCatalog(urls).listOperations("model-1");

  expect(urls).toHaveLength(1);
  const query = new URL(urls[0]).searchParams;
  expect(query.get("modelId")).toBe("model-1");
  // Not "a count is present": `OperationController` already defaults it to 20, and
  // `OperationService.getOperations` answers an empty list to a negative one. Zero is the single
  // value that reaches the unwindowed repository method.
  expect(query.get("count")).toBe("0");
});

test("the stub base records no operation coverage", { tag: ["@infra", "@tier1"] }, async () => {
  const urls: string[] = [];
  await recordingCatalog(urls).listOperations("model-1");

  for (const url of urls) {
    expect(matchOperationPath("catalog", "GET", new URL(url).pathname)).toBeUndefined();
  }
});
