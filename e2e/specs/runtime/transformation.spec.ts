/**
 * The families that change the message rather than route it: script, mapper, and the exchange
 * context a chain carries from one element to the next.
 *
 * The mapper case is the one worth reading twice. Its mapping writes a constant into a target
 * header and into a target body attribute, which is the smallest shape that needs no imported
 * specification — and it is also the shape that proved a mapping's `path` addresses an attribute's
 * **id**, not its name: with the name there, the snapshot build fails with "Unable to find header".
 */
import { test, expect } from "../../support/fixtures.js";
import { readCorpusState, seedChain } from "../../support/corpus.js";
import { callChain, element, elementNames } from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";

test("a script replaces the body and the trace names the step that did it", { tag: ["@engine", "@sessions", "@tier1"] }, async ({ request, env, sessions }) => {
  covers("script");

  const chain = seedChain(readCorpusState(), "script");

  const call = await callChain(request, env.chainUrl(chain.contextPath), {
    data: { ping: "script" },
  });
  expect(call.response.status()).toBe(200);
  expect(JSON.parse(await call.response.text())).toEqual({ element: "script", ran: true });

  const session = await sessions.byExternalId(call.token, { elements: 3 });
  expect(elementNames(session)).toEqual(["HTTP Trigger", "Validate Request", "Script"]);
  // The body the engine recorded is the body the script produced, which is a stronger statement
  // than "a step named Script ran".
  expect(element(session, "Script")?.bodyAfter).toContain('"ran":true');
});

test("a mapper writes its constant into the header and the body it targets", { tag: ["@engine", "@sessions", "@tier1"] }, async ({ request, env, sessions }) => {
  covers("mapper-2");

  const chain = seedChain(readCorpusState(), "mapper");

  const call = await callChain(request, env.chainUrl(chain.contextPath), {
    data: { ping: "mapper" },
  });
  expect(call.response.status()).toBe(200);
  expect(call.response.headers()["e2e-mapped"]).toBe("mapper-ran");
  expect(JSON.parse(await call.response.text())).toEqual({ mapped: "mapper-ran" });

  const session = await sessions.byExternalId(call.token, { elements: 3 });
  expect(elementNames(session)).toEqual(["HTTP Trigger", "Validate Request", "Mapper"]);
});

test("the exchange context survives every hop of a chain", { tag: ["@engine", "@sessions", "@tier1"] }, async ({ request, env, sessions }) => {
  const chain = seedChain(readCorpusState(), "context-propagation");

  const call = await callChain(request, env.chainUrl(chain.contextPath), {
    data: { ping: "context" },
  });
  expect(call.response.status()).toBe(200);
  // The first script writes an exchange property and a header; a header modification runs between;
  // the last script reads both back. A context lost at any hop changes this body.
  expect(JSON.parse(await call.response.text())).toEqual({
    property: "property-survived",
    header: "header-survived",
  });
  expect(call.response.headers()["e2e-context"]).toBe("header-survived");

  const session = await sessions.byExternalId(call.token, { elements: 5 });
  expect(elementNames(session)).toEqual([
    "HTTP Trigger",
    "Validate Request",
    "Write Context",
    "Header Modification",
    "Read Context",
  ]);
});
