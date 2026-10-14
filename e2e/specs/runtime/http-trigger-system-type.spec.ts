/**
 * `systemType` IMPLEMENTED on `http-trigger`: a trigger that implements an operation of a service.
 *
 * The seeded `http-trigger-system-type-implemented` chain implements the one operation of the seeded
 * `system-type-implemented` service, whose environment address is `/`, the address the UI creates for
 * an implemented service (`ServicesList.tsx`). The trigger then serves the operation's path rather
 * than a `contextPath` of its own, and the seed reads that path as the chain's route. The INTERNAL and
 * EXTERNAL values compile a URI no request can reach, so their cases are in
 * `specs/api/http-trigger-system-type.spec.ts`. No case declares the IMPLEMENTED row: INTERNAL and
 * IMPLEMENTED both take the environment address, so neither case could tell its value from the other.
 */
import { test, expect } from "../../support/fixtures.js";
import { readCorpusState, seedChain } from "../../support/corpus.js";
import { MARKER_HEADER } from "../../support/deployable.js";
import { callChain, elementNames, HTTP_TRIGGER_STEPS } from "../../support/sessions.js";

test("systemType IMPLEMENTED serves the operation's path", { tag: ["@engine", "@catalog", "@sessions", "@tier2"] }, async ({ request, env, catalog, sessions }) => {
  const chain = seedChain(readCorpusState(), "http-trigger-system-type-implemented");
  // The environment's `/` and the operation path, joined as they are.
  const [deployment] = await catalog.listDeployments(chain.id);
  const xml = (await catalog.getSnapshot(chain.id, deployment.snapshotId)).xmlDefinition ?? "";
  expect(xml.match(/<from uri="(servlet-custom:[^"?]*)/)?.[1]).toBe(`servlet-custom:/${chain.contextPath}`);

  const call = await callChain(request, env.chainUrl(chain.contextPath), { data: {} });
  expect(call.response.status()).toBe(200);
  expect(call.response.headers()[MARKER_HEADER]).toBe("implemented");
  const session = await sessions.byExternalId(call.token, { elements: 3 });
  expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, "Header Modification"]);
});
