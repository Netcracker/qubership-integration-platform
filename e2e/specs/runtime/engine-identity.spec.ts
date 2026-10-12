/**
 * The engine a project names is the engine that runs its chains.
 *
 * `runtime` and `runtime-micro` run the same files, and every other case in them would pass just as
 * well if both projects reached the classic engine. The session records the kind of engine that ran
 * the chain, so this case reads it. It is the one runtime spec whose subject is `engineKind`, and the
 * only one allowed to read it.
 */
import { test, expect } from "../../support/fixtures.js";
import { readCorpusState, seedChain } from "../../support/corpus.js";
import { callChain } from "../../support/sessions.js";

test("a seeded chain runs on the engine kind its project names", { tag: ["@engine", "@sessions", "@tier1"] }, async ({ request, env, sessions, engineKind }) => {
  const chain = seedChain(readCorpusState(), "http-echo");

  const call = await callChain(request, env.chainUrl(chain.contextPath), { data: { ping: "identity" } });
  expect(call.response.status()).toBe(200);

  const session = await sessions.byExternalId(call.token);
  expect(session.chainId).toBe(chain.id);
  expect(session.domainType).toBe(engineKind === "micro" ? "MICRO" : "CLASSIC");
});
