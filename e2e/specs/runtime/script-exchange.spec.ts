/**
 * What a Script element can reach on the exchange: the body in every type it holds, a body that is
 * not there at all, the headers, and the exchange properties.
 *
 * Four seeded chains, one per subject, and every script writes through `exchange.getIn().setBody`.
 * That is not a style: the element compiles to Camel's `<script>` EIP, whose **return value is
 * discarded**. The body chain proves it rather than asserting it in a comment — its first step
 * evaluates to a JSON string and sets nothing, and the step after it is still handed the request.
 *
 * The chains live in `fixtures/script/`, which the seed imports with the rest of the corpus. What
 * makes the traces below readable at all is the seed's
 * `POST /v1/chains/{id}/properties/logging` between the import and the deploy — measured, at the
 * `OFF` default ten invocations across four chains recorded zero sessions.
 */
import { test, expect } from "../../support/fixtures.js";
import { MICRO_STEP_NAMES } from "../../support/known-defect.js";
import { readCorpusState, seedChain } from "../../support/corpus.js";
import { callToken } from "../../support/run.js";
import { callChain, element, elementNames, HTTP_TRIGGER_STEPS } from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";

/** The body chain's steps, in order, after the two the trigger records. */
const LADDER = ["Return Only", "Read Inbound", "As Bytes", "As Stream", "As Map", "As List", "Report"];

test("a script reads and writes the body as a stream, a String, bytes, a Map and a List", { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions, engineKind }) => {
  test.fail(engineKind === "micro", MICRO_STEP_NAMES.title);
  covers("script");

  const chain = seedChain(readCorpusState(), "script-exchange-body.yaml");
  const text = callToken("payload");
  // Plain text rather than JSON, so the payload the ladder carries can be compared as one value
  // and quoted back into the report the last script builds.
  const call = await callChain(request, env.chainUrl(chain.contextPath), {
    headers: { "Content-Type": "text/plain" },
    data: text,
  });

  expect(call.response.status()).toBe(200);
  // The whole ladder in one reading: the text the request sent came back out of a List, having
  // been a String, a byte[], a stream and a Map on the way — and the discarded return value of the
  // first script never replaced it.
  expect(JSON.parse(await call.response.text())).toEqual({ text, marker: "e2e-list" });

  const headers = call.response.headers();
  // The inbound body is a stream cache, which is the fact the first conversion exists for.
  expect(headers["e2e-read-1"]).toContain("InputStreamCache");
  expect(headers["e2e-read-2"]).toBe("java.lang.String");
  expect(headers["e2e-read-3"]).toBe("[B");
  // A written stream may reach the next step wrapped by stream caching, so the assertion is about
  // the kind rather than the class: what the step was handed is an InputStream either way.
  expect(headers["e2e-read-4"]).toContain("InputStream");
  expect(headers["e2e-read-5"]).toBe("java.util.LinkedHashMap");
  expect(headers["e2e-read-6"]).toBe("java.util.ArrayList");

  const session = await sessions.byExternalId(call.token, { elements: HTTP_TRIGGER_STEPS.length + LADDER.length });
  expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, ...LADDER]);
  // The engine's own record of the discarded return value: the step that only evaluated a string
  // ended on the body it started with.
  expect(element(session, "Return Only")?.bodyAfter).toBe(text);
});

test("a script sees a request that carries no body, and a body it sets to null reaches the caller as nothing", { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions, engineKind }) => {
  test.fail(engineKind === "micro", MICRO_STEP_NAMES.title);
  const chain = seedChain(readCorpusState(), "script-exchange-null-body.yaml");

  // No `data` at all, which is the null-in half: the chain is called the way a caller with nothing
  // to send calls it.
  const call = await callChain(request, env.chainUrl(chain.contextPath));

  // The null-out half, and **204 rather than 200** — measured. A chain that ends on a null body
  // answers no content at all, which is the one place in this file where the body the script set
  // changes the status line rather than only the payload.
  expect(call.response.status()).toBe(204);
  expect(await call.response.text()).toBe("");

  const headers = call.response.headers();
  // Measured rather than assumed: a request with no body reaches the first script as a body that
  // is already null, not as an empty stream.
  expect(headers["e2e-in-null"]).toBe("true");
  expect(headers["e2e-in-class"]).toBe("null");
  // And `setBody(null)` is what the step after it is handed, rather than an empty String.
  expect(headers["e2e-null-seen"]).toBe("true");
  expect(headers["e2e-null-text"]).toBe("null");

  const session = await sessions.byExternalId(call.token, { elements: HTTP_TRIGGER_STEPS.length + 2 });
  expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, "Drop Body", "Read Null Body"]);
  // Two readings, because one cannot separate them: the step recorded its exchange at all, and the
  // body it recorded is nothing. `bodyBefore ?? null` alone is equally green on a trace that
  // carries no exchange, which is the failure the seed's logging step exists to prevent.
  const readNull = element(session, "Read Null Body");
  expect(readNull?.headersBefore, "the step recorded no exchange at all").toBeTruthy();
  expect(readNull?.bodyBefore ?? null).toBeNull();
});

test("a script reads a header, adds headers, and removes one, and the step after it sees all three", { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions, engineKind }) => {
  test.fail(engineKind === "micro", MICRO_STEP_NAMES.title);
  const chain = seedChain(readCorpusState(), "script-exchange-headers.yaml");
  const sent = callToken("header");

  const call = await callChain(request, env.chainUrl(chain.contextPath), {
    headers: { "e2e-in": sent, "e2e-remove-me": "present-on-the-request" },
    data: {},
  });

  expect(call.response.status()).toBe(200);
  // Read back by the second script, so each of the three changes outlived the element that made it.
  expect(JSON.parse(await call.response.text())).toEqual({
    echo: sent,
    added: "added-by-script",
    removed: true,
  });

  const headers = call.response.headers();
  expect(headers["e2e-echo"]).toBe(sent);
  expect(headers["e2e-added"]).toBe("added-by-script");
  expect(headers["e2e-remove-me"]).toBeUndefined();

  const session = await sessions.byExternalId(call.token, { elements: HTTP_TRIGGER_STEPS.length + 2 });
  expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, "Change Headers", "Read Headers"]);

  // The removal from both sides of the step that made it, which is the only reading that separates
  // "the header was removed" from "the header was never there".
  const changed = element(session, "Change Headers");
  expect(changed?.headersBefore?.["e2e-remove-me"]).toBe("present-on-the-request");
  // `e2e-added` is read first and out of the same map: it is what separates "the header was
  // removed" from "`headersAfter` is null and every lookup in it reads undefined".
  expect(changed?.headersAfter?.["e2e-added"]).toBe("added-by-script");
  expect(changed?.headersAfter?.["e2e-remove-me"]).toBeUndefined();
});

test("an exchange property a script sets is read back by a later element", { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions, engineKind }) => {
  test.fail(engineKind === "micro", MICRO_STEP_NAMES.title);
  const chain = seedChain(readCorpusState(), "script-exchange-properties.yaml");
  const sent = callToken("property");

  const call = await callChain(request, env.chainUrl(chain.contextPath), {
    headers: { "e2e-in": sent },
    data: {},
  });

  expect(call.response.status()).toBe(200);
  // `hopIsHeaderOnly` is the third reading and it is about the platform rather than about the
  // script: the header the element in between added is on the message and is **not** a property of
  // the same name, so the two namespaces stay apart across an element boundary.
  expect(JSON.parse(await call.response.text())).toEqual({
    carried: sent,
    stamp: "set-by-script",
    hopIsHeaderOnly: true,
  });

  const session = await sessions.byExternalId(call.token, { elements: HTTP_TRIGGER_STEPS.length + 3 });
  expect(elementNames(session)).toEqual([
    ...HTTP_TRIGGER_STEPS,
    "Write Property",
    "Header Modification",
    "Read Property",
  ]);

  // The engine's record of the same thing, and the stronger half of it: the property is on the
  // exchange before the last script runs, having crossed an element that knows nothing about it.
  expect(element(session, "Write Property")?.propertiesAfter?.["e2eScriptProperty"]).toEqual({
    type: "java.lang.String",
    value: sent,
  });
  expect(element(session, "Read Property")?.propertiesBefore?.["e2eScriptStamp"]?.value).toBe("set-by-script");
});
