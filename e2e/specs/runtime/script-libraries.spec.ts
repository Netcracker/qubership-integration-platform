/**
 * What a Script element can reach without loading anything: the six Groovy modules the engines
 * ship, and the Java standard library under them.
 *
 * Each case calls one seeded chain and reads the answer its script built. The module is exercised
 * through behavior the module itself adds — an extension method on `java.time`, a `Path` that can
 * be read as text, a row that looks up its columns without regard to case — so a case that passes
 * says the jar is on the classpath, and not merely that the JDK class under it exists.
 *
 * Every case also runs under `runtime-micro`. #747 closed by aligning the module sets, so the four
 * modules micro used to lack are a classpath fact on both.
 * The micro engine resolves `groovy` and `groovy-xml` at 4.0.29 and the other modules at 4.0.30,
 * where the classic engine resolves all of them at 4.0.30. That was measured, and no case here
 * disagrees because of it.
 *
 * No case here loads an external library. That is the compiled-script cache's subject and it has
 * its own spec.
 */
import { createHash } from "node:crypto";
import { test, expect } from "../../support/fixtures.js";
import { readCorpusState, seedChain } from "../../support/corpus.js";
import { callToken } from "../../support/run.js";
import { callChain, element, elementNames, HTTP_TRIGGER_STEPS } from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";

test("a script parses the request with JsonSlurper and answers with JsonOutput", { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
  covers("script");

  const chain = seedChain(readCorpusState(), "script-libraries-json.yaml");
  const token = callToken("json");
  const call = await callChain(request, env.chainUrl(chain.contextPath), {
    data: { token, items: [1, 2, 3] },
  });

  expect(call.response.status()).toBe(200);
  // `sum` is the half a String conversion could not have produced: the numbers survived as numbers
  // across the element boundary, inside the map JsonSlurper built.
  expect(JSON.parse(await call.response.text())).toEqual({ token, sum: 6 });
  // Four lines for a two-field object, which is `JsonOutput.prettyPrint` having run rather than
  // `toJson` having been reported twice.
  expect(call.response.headers()["e2e-json-pretty-lines"]).toBe("4");

  const session = await sessions.byExternalId(call.token, { elements: HTTP_TRIGGER_STEPS.length + 2 });
  expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, "Slurp Json", "Write Json"]);
  // The parsed value crossed the boundary as a map, which the trace records as the body the second
  // script was handed rather than as the text the first one received.
  expect(element(session, "Write Json")?.bodyBefore).toContain(token);
});

test("a script reads one document through XmlSlurper and through XmlParser", { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
  const chain = seedChain(readCorpusState(), "script-libraries-xml.yaml");
  const token = callToken("xml");
  const call = await callChain(request, env.chainUrl(chain.contextPath), {
    headers: { "Content-Type": "application/xml" },
    data: `<payload><token>${token}</token><item>a</item><item>b</item></payload>`,
  });

  expect(call.response.status()).toBe(200);
  // The two parsers agree on the document, and `parserClass` is what says they are two: a GPath
  // result reports a node child, while `XmlParser` answers the core `groovy.util.Node` tree.
  expect(JSON.parse(await call.response.text())).toEqual({
    slurperToken: token,
    slurperItems: 2,
    parserToken: token,
    parserItems: 2,
    parserClass: "groovy.util.Node",
  });

  const session = await sessions.byExternalId(call.token, { elements: HTTP_TRIGGER_STEPS.length + 2 });
  expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, "Slurp Xml", "Parse Xml"]);
});

test("a script reaches groovy-datetime's extensions on java.time", { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
  const chain = seedChain(readCorpusState(), "script-libraries-datetime.yaml");
  const call = await callChain(request, env.chainUrl(chain.contextPath), { data: {} });

  expect(call.response.status()).toBe(200);
  // Three module extensions in one answer: `format(String)`, `+` on a date, and `upto`, which
  // visits both ends of the range and so counts 31 days rather than 30.
  expect(JSON.parse(await call.response.text())).toEqual({
    formatted: "2026/09/01",
    later: "2026-10-01",
    visited: 31,
  });

  const session = await sessions.byExternalId(call.token, { elements: HTTP_TRIGGER_STEPS.length + 1 });
  expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, "Use Datetime"]);
});

test("a script reaches groovy-nio's extensions on java.nio.file.Path", { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
  const chain = seedChain(readCorpusState(), "script-libraries-nio.yaml");
  const token = callToken("nio");
  const call = await callChain(request, env.chainUrl(chain.contextPath), {
    headers: { "e2e-in": token },
    data: {},
  });

  expect(call.response.status()).toBe(200);
  const answer = JSON.parse(await call.response.text());
  // Written and read back through the module's `setText` and `getText`, so the value made a round
  // trip through the engine's file system rather than through a variable.
  expect(answer.read).toBe(token);
  // `size()` is the module's, and the trailing newline the script wrote is why it is one over.
  expect(answer.size).toBe(token.length + 1);
  expect(answer.name).toMatch(/^e2e-script-nio-.*\.txt$/);
  // The case cleans up after itself inside the engine container, where nothing else can.
  expect(answer.deleted).toBe(true);

  const session = await sessions.byExternalId(call.token, { elements: HTTP_TRIGGER_STEPS.length + 1 });
  expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, "Use Nio"]);
});

test("a script reaches groovy-sql without a database", { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
  const chain = seedChain(readCorpusState(), "script-libraries-sql.yaml");
  const token = callToken("sql");
  const call = await callChain(request, env.chainUrl(chain.contextPath), {
    headers: { "e2e-in": token },
    data: {},
  });

  expect(call.response.status()).toBe(200);
  // `Token` and `Count` went in, `token` and `COUNT` come back out: case-insensitive lookup is
  // what `GroovyRowResult` adds over the map it wraps, and it needs no connection to show.
  expect(JSON.parse(await call.response.text())).toEqual({
    property: token,
    index: 3,
    columns: 2,
    varcharType: 12,
  });

  const session = await sessions.byExternalId(call.token, { elements: HTTP_TRIGGER_STEPS.length + 1 });
  expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, "Use Sql"]);
});

test("a script asks JSR-223 for a second Groovy engine and evaluates in it", { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
  const chain = seedChain(readCorpusState(), "script-libraries-jsr223.yaml");
  const token = callToken("jsr");
  const call = await callChain(request, env.chainUrl(chain.contextPath), {
    headers: { "e2e-in": token },
    data: {},
  });

  expect(call.response.status()).toBe(200);
  // `engine: "none"` is the shape the script answers with when the manager finds no factory, which
  // is what a missing module looks like from here. The bound value is the stronger half: the
  // nested engine evaluated a name this call minted, not a constant compiled into the script.
  expect(JSON.parse(await call.response.text())).toEqual({
    engine: "Groovy Scripting Engine",
    bound: token.toUpperCase(),
    evaluated: 42,
  });

  const session = await sessions.byExternalId(call.token, { elements: HTTP_TRIGGER_STEPS.length + 1 });
  expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, "Use Jsr223"]);
});

test("a script reaches the Java standard library with no Groovy module involved", { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
  const chain = seedChain(readCorpusState(), "script-libraries-jdk.yaml");
  const token = callToken("jdk");
  const call = await callChain(request, env.chainUrl(chain.contextPath), {
    headers: { "Content-Type": "text/plain" },
    data: token,
  });

  expect(call.response.status()).toBe(200);
  // Recomputed here rather than read back from the answer, so the digest is checked against a
  // value the platform had no part in producing.
  expect(JSON.parse(await call.response.text())).toEqual({
    sha256: createHash("sha256").update(token, "utf8").digest("hex"),
    base64: Buffer.from(token, "utf8").toString("base64"),
    upper: token.toUpperCase(),
  });

  const session = await sessions.byExternalId(call.token, { elements: HTTP_TRIGGER_STEPS.length + 1 });
  expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, "Use Jdk"]);
});
