/**
 * `httpMethodRestrict` on `checkpoint`, asserted on the compiled snapshot of the seeded
 * `checkpoint-retry` chain.
 *
 * The value is the query of each checkpoint's retry route, the route
 * `specs/runtime/checkpoint-retry.spec.ts` retries through. A compile reading belongs in this
 * project, as the `http-trigger` access control ones do.
 */
import { test, expect } from "../../support/fixtures.js";
import { readCorpusState, seedChain } from "../../support/corpus.js";
import { covers } from "../../registry/covers.js";

test("each checkpoint compiles a retry route restricted to POST", { tag: ["@catalog", "@tier2"] }, async ({ catalog }) => {
  covers("checkpoint", "httpMethodRestrict", "POST");
  const chain = seedChain(readCorpusState(), "checkpoint-retry");
  const [snapshot] = await catalog.listSnapshots(chain.id);
  const xml = (await catalog.getSnapshot(chain.id, snapshot.id)).xmlDefinition ?? "";
  for (const checkpoint of ["First Checkpoint", "Second Checkpoint"]) {
    expect(xml).toContain(
      `<from uri="servlet-custom:/chains/${chain.id}/sessions/{checkpointSessionId}/checkpoint-elements/${chain.elements[checkpoint]}/retry?httpMethodRestrict=POST"/>`,
    );
  }
});
