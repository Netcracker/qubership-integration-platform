/**
 * What a Script element does when it goes wrong: at deploy, when it does not compile, and at run
 * time, caught and uncaught.
 *
 * **A compile failure is not one outcome.** Both engines compile eagerly before the routes are
 * added — `IntegrationRuntimeService.compileGroovyScript` on classic, `CompileGroovyScriptsAction`
 * on `RouteAddedEvent` on micro — and the exception they raise decides what the catalog reports. A
 * script that does not parse raises a plain `RuntimeException` and the deployment lands **FAILED**;
 * a script naming a class the engine cannot resolve raises `DeploymentRetriableException` and the
 * deployment stays **PROCESSING**, retrying on the engine's 30 s schedule, with the *same*
 * `Failed to compile groovy script.` message on both. So a case asserting "the deployment fails"
 * never sees `FAILED` on the second one — it times out — and the two are asserted apart here: the
 * status, and for the retriable one the retry itself.
 *
 * Getting an unresolved class is harder than it looks and the fixture says so: Groovy resolves a
 * dotted expression dynamically, so `org.qubership.nothing.Missing.class` in the body compiles and
 * deploys. It takes an `import`, which is resolved at compile time.
 *
 * **A nested script is compiled at deploy too**, which the source alone does not say: both engines
 * iterate the deployment's outputs without recursing. The catalog emits **every element as its own
 * Camel route** reached by `direct:`, so an element inside a container is still a top-level output
 * of a route of its own — measured here for `try-catch-finally`, where a syntax error two levels
 * down, with no script anywhere else in the chain, fails the deployment exactly as a top-level one
 * does. `loop` and `split` emit the same shape — measured on the compiled XML in
 * `specs/runtime/script-in-container.spec.ts`, where a loop body and every split branch is a route
 * reached by `direct:` with its script as a top-level output. `choice` is still unmeasured.
 *
 * The three chains that cannot deploy are **spec-owned**: they are imported, deployed and deleted
 * by this file and named in `SPEC_OWNED_FIXTURES`, because the shared seed gates on every corpus
 * chain reaching a live route and one that never does would break every runtime spec in the suite.
 * The chains that do deploy are ordinary corpus fixtures.
 *
 * **Infinite loops and allocation blowups are deliberately absent.** Neither engine declares a
 * script timeout, a memory cap, or a sandbox — `grep` over both `application.yml` files returns
 * nothing — so there is no defined behavior to assert. A spec written against undefined behavior
 * fails whenever the platform changes without ever having said anything true.
 */
import { test, expect } from "../../support/fixtures.js";
import {
  DEPLOY_TIMEOUT,
  ENGINE_CASE_TIMEOUT,
  RETRY_DELAY,
  readCorpusState,
  seedChain,
} from "../../support/corpus.js";
import { SCRIPT_FIXTURE_DIR } from "../../fixtures/templating.js";
import { logWindowStart } from "../../support/logs.js";
import { callChain, element, elementNames, HTTP_TRIGGER_STEPS } from "../../support/sessions.js";
import { deploymentRows, routeStatus, withOwnFixture } from "../../support/spec-owned.js";
import { covers } from "../../registry/covers.js";
import type { Catalog } from "../../support/catalog.js";

/** The one message both compile failures carry, whichever status they end on. */
const COMPILE_FAILURE = "Failed to compile groovy script.";

/** The class the unresolved-class fixture imports, which nothing on the engine's classpath carries. */
const MISSING_CLASS = "org.qubership.integration.e2e.NoSuchLibraryClass";

/**
 * How many compile attempts the retry case waits for, counted off the line the engine writes **once
 * per attempt**.
 *
 * `IntegrationRuntimeService.putInRetryQueue` logs `Deployment marked for retry <deploymentId>`
 * from the `DeploymentRetriableException` catch, so one line is one failed attempt. The class name
 * is not the counter: a single attempt writes the FQCN two to four times — `unable to resolve
 * class`, the echoed `import` source line, the wrapped stack, and the route XML the service logs at
 * DEBUG — so a count over it is satisfied before any retry has happened.
 *
 * Measured on the marker rather than on the class name, which is what corrected the number: one
 * attempt lands with the deploy and the retries follow — 13:41:28, 13:41:50, 13:42:21, 13:42:51 over
 * one parked deployment. The earlier reading of "twice within two seconds" was the class name being
 * written several times by a single attempt.
 *
 * The first gap is not 30 s, and the reason is what sizes the poll below.
 * `TasksScheduler.retryProcessingDeploys` is `@Scheduled(fixedDelayString =
 * "${qip.deployments.retry-delay}")`, a tick free-running since the engine booted rather than a
 * delay measured from the failed attempt. So the first retry lands wherever in the tick the deploy
 * fell, anywhere in 0 to 30 s — measured at 3.8 s and at 19 s on two runs against one engine, and at
 * 22 s on the run above — and only the gaps after it are 30 s. Three marks is therefore at most
 * `RETRY_DELAY * 2` after the deploy, which is the budget the poll is given.
 */
const COMPILE_ATTEMPTS = 3;

/**
 * What the retry case gets instead of `ENGINE_CASE_TIMEOUT`.
 *
 * The two polls alone are `DEPLOY_TIMEOUT` plus `RETRY_DELAY * 2 + DEPLOY_TIMEOUT`, which is 180 s
 * exactly — the shared engine budget, before the import, the snapshot, the deploy and the teardown
 * are charged. With `retries: 0` an overrun is a hard red, and a body Playwright abandons may skip
 * the `finally` that deletes a chain the engine would go on recompiling for the life of the stack.
 */
const RETRY_CASE_TIMEOUT = 300_000;

/** The engine's own deployment id for the chain, which is what scopes the retry count to this deploy. */
async function deploymentIdOf(catalog: Catalog, chainId: string): Promise<string> {
  const rows = await catalog.runtimeDeploymentsOf(chainId);
  expect(rows, "the engine reported no deployment for the parked chain").toHaveLength(1);
  return rows[0].deploymentInfo.deploymentId;
}

function occurrences(log: string, needle: string): number {
  return log.split(needle).length - 1;
}

test("a script that does not compile fails the deployment", { tag: ["@engine", "@catalog", "@tier2"] }, async ({ env, catalog, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  covers("script");

  await withOwnFixture(catalog, run, "script-failures-syntax.yaml", SCRIPT_FIXTURE_DIR, async (chain) => {
    await expect
      .poll(async () => await deploymentRows(catalog, chain.id), {
        timeout: DEPLOY_TIMEOUT,
        message: "the engine never reported the unparseable script",
      })
      .toEqual([`FAILED: ${COMPILE_FAILURE}`]);

    // The other half of "failed": nothing is serving. A 405 here would be a chain that went live
    // with a script the engine could not compile.
    expect(await routeStatus(env, chain), "a chain that failed to compile is serving").toBe(404);
  });
});

test("a script naming a class the engine cannot resolve parks the deployment and keeps retrying", { tag: ["@engine", "@catalog", "@tier2"] }, async ({ env, catalog, run }) => {
  test.setTimeout(RETRY_CASE_TIMEOUT);

  // Opened before the import, so every compile attempt counted below is this case's.
  const since = logWindowStart();

  await withOwnFixture(catalog, run, "script-failures-unresolved.yaml", SCRIPT_FIXTURE_DIR, async (chain) => {
    // `PROCESSING`, not `FAILED`, and the message is the one the syntax error carries too — which is
    // why the status is what separates them.
    await expect
      .poll(async () => await deploymentRows(catalog, chain.id), {
        timeout: DEPLOY_TIMEOUT,
        message: "the engine never reported the unresolved class",
      })
      .toEqual([`PROCESSING: ${COMPILE_FAILURE}`]);

    // The retry itself, which is the half a status read cannot see: the same script is compiled
    // again on the engine's schedule. Counted on the one line an attempt writes exactly once, and
    // scoped by the **deployment id** rather than by the chain — measured, the deployment executor
    // writes this line with `chain_id=-` in the MDC, and a fixture's chain id is frozen anyway, so
    // a parked deployment an earlier run left behind would share it. A deployment id is minted per
    // deploy and cannot be.
    const retryMark = `Deployment marked for retry ${await deploymentIdOf(catalog, chain.id)}`;
    await expect
      .poll(async () => occurrences(await env.logs("engine", since), retryMark), {
        timeout: RETRY_DELAY * 2 + DEPLOY_TIMEOUT,
        intervals: [2_000],
        message: "the parked deployment was compiled once and never retried",
      })
      .toBeGreaterThanOrEqual(COMPILE_ATTEMPTS);

    const log = await env.logs("engine", since);
    // The exception type is what makes the retry a contract rather than an accident of scheduling,
    // and the class is what it failed on. The class name is this fixture's own, so the window is
    // scope enough for both.
    expect(log, "the engine did not treat the unresolved class as retriable").toContain(
      "DeploymentRetriableException",
    );
    expect(log).toContain(`unable to resolve class ${MISSING_CLASS}`);

    // Still parked after those retries, and still not serving: this deployment has no ending.
    expect(await deploymentRows(catalog, chain.id)).toEqual([`PROCESSING: ${COMPILE_FAILURE}`]);
    expect(await routeStatus(env, chain), "a parked deployment is serving").toBe(404);
  });
});

test("a syntax error inside a container fails the deployment too", { tag: ["@engine", "@catalog", "@tier2"] }, async ({ env, catalog, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);

  await withOwnFixture(catalog, run, "script-failures-nested-syntax.yaml", SCRIPT_FIXTURE_DIR, async (chain) => {
    // The chain carries no top-level script at all, so a `FAILED` here is the nested one having
    // been compiled: the catalog emits the Try branch's script as its own route.
    await expect
      .poll(async () => await deploymentRows(catalog, chain.id), {
        timeout: DEPLOY_TIMEOUT,
        message: "a script two levels down was not compiled at deploy",
      })
      .toEqual([`FAILED: ${COMPILE_FAILURE}`]);
    expect(await routeStatus(env, chain)).toBe(404);
  });
});

test("a script that throws inside a Try branch is caught, and the catch branch reads the exception", { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
  const chain = seedChain(readCorpusState(), "script-failures-caught.yaml");

  const call = await callChain(request, env.chainUrl(chain.contextPath), { data: { ping: "caught" } });

  // 200, because the catch branch handled it. The status alone cannot tell this from a chain that
  // never failed, which is what the trace below is for.
  expect(call.response.status()).toBe(200);
  expect(JSON.parse(await call.response.text())).toEqual({
    caught: "Cannot get property 'someProperty' on null object",
  });
  // What the catch branch read off `CamelExceptionCaught`: Groovy raises a plain NPE for a property
  // read on null, so the script's failure reaches the catch branch as the JVM's own exception.
  expect(call.response.headers()["e2e-caught-class"]).toBe("java.lang.NullPointerException");
  expect(call.response.headers()["e2e-caught-finally"]).toBe("ran");

  const session = await sessions.byExternalId(call.token, { elements: HTTP_TRIGGER_STEPS.length + 7 });
  expect(session.executionStatus).toBe("COMPLETED_WITH_WARNINGS");
  expect(elementNames(session)).toEqual([
    ...HTTP_TRIGGER_STEPS,
    "Try-Catch-Finally",
    "Try",
    "Dereference Null",
    "Catch",
    "Report Caught",
    "Finally",
    "Finally Branch",
  ]);
  expect(element(session, "Dereference Null")?.executionStatus).toBe("COMPLETED_WITH_ERRORS");
  expect(element(session, "Dereference Null")?.exceptionInfo?.message).toBe(
    "Cannot get property 'someProperty' on null object",
  );
  // The branch that ran, which is the assertion the response status cannot make.
  expect(element(session, "Report Caught")?.executionStatus).toBe("COMPLETED_NORMALLY");
  expect(element(session, "Finally Branch")?.executionStatus).toBe("COMPLETED_NORMALLY");
});

test("an uncaught exception answers the caller the platform's error body, naming the script that threw", { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
  const chain = seedChain(readCorpusState(), "script-failures-uncaught.yaml");

  const call = await callChain(request, env.chainUrl(chain.contextPath), { data: { ping: "uncaught" } });

  // Measured rather than assumed, and none of it is what a reader would guess: the caller gets the
  // platform's error contract, not the Groovy message. `QIP-0001` is
  // `ErrorCode.UNEXPECTED_BUSINESS_ERROR`, the reason and message are the enum's fixed wording, and
  // the exception the script threw appears nowhere in the response.
  expect(call.response.status()).toBe(500);
  expect(call.response.headers()["content-type"]).toContain("application/json");
  const body = JSON.parse(await call.response.text()) as {
    code: string;
    reason: string;
    message: string;
    extra: { sessionId: string; failedElementId: string };
  };
  expect(body.code).toBe("QIP-0001");
  expect(body.reason).toBe("Unexpected Business error");
  expect(body.message).toBe("Chain execution failed due to unexpected business error");
  // The half worth having: `extra` names the element that threw, and the fixture freezes its id.
  expect(body.extra.failedElementId).toBe(chain.elements["Throw Uncaught"]);

  const session = await sessions.byExternalId(call.token, { elements: HTTP_TRIGGER_STEPS.length + 1 });
  // And the session it names is the session the call recorded, so a caller holding the error body
  // can find the trace. That is what makes `extra` an answer rather than an opaque pair of ids.
  expect(body.extra.sessionId).toBe(session.id);
  expect(session.executionStatus).toBe("COMPLETED_WITH_ERRORS");
  expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, "Throw Uncaught"]);
  const failed = element(session, "Throw Uncaught");
  expect(failed?.executionStatus).toBe("COMPLETED_WITH_ERRORS");
  // The message the response withheld is in the trace, which is the only place it is.
  expect(failed?.exceptionInfo?.message).toBe("e2e uncaught script failure");
  // The script ran up to the throw rather than being rejected whole: the header it set before
  // throwing is on the exchange the failing step recorded.
  expect(failed?.headersAfter?.["e2e-reached"]).toBe("before-the-throw");
});

test("a null pointer inside a script fails the chain the way any other exception does", { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
  const chain = seedChain(readCorpusState(), "script-failures-npe.yaml");

  // A method call on a header that was never sent — the commonest real Script failure there is, and
  // the one shape of it a caller can reproduce without the script cooperating.
  const call = await callChain(request, env.chainUrl(chain.contextPath), { data: { ping: "npe" } });

  expect(call.response.status()).toBe(500);
  const body = JSON.parse(await call.response.text()) as {
    code: string;
    extra: { failedElementId: string };
  };
  // The same error contract as a deliberate throw: nothing about a null pointer reaches the caller.
  expect(body.code).toBe("QIP-0001");
  expect(body.extra.failedElementId).toBe(chain.elements["Null Property"]);

  const session = await sessions.byExternalId(call.token, { elements: HTTP_TRIGGER_STEPS.length + 1 });
  expect(session.executionStatus).toBe("COMPLETED_WITH_ERRORS");
  expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, "Null Property"]);
  expect(element(session, "Null Property")?.exceptionInfo?.message).toBe(
    "Cannot invoke method length() on null object",
  );
});

test("a whitespace-only script is a no-op and the body passes through it", { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
  // Measured on the classic engine, which is the only one Compose runs. Micro's
  // `GroovyLanguageWithResettableCache` coalesces a null script to `""` and the classic copy does
  // not, which reads as a divergence and is not one: the two engines use different chain loaders,
  // `camel-xml-io-dsl` drops a whitespace-only expression body where the Spring engine's JAXB
  // loader keeps it, and the override restores the behavior this case asserts. To Groovy an empty
  // source and a whitespace-only one are the same thing. `docs/product-defects.md` carries it.
  const chain = seedChain(readCorpusState(), "script-empty.yaml");
  const sent = { ping: "empty" };
  const call = await callChain(request, env.chainUrl(chain.contextPath), { data: sent });

  // It compiled, it was cached, the chain deployed, and the call answers with the request.
  expect(call.response.status()).toBe(200);
  expect(JSON.parse(await call.response.text())).toEqual(sent);

  const session = await sessions.byExternalId(call.token, { elements: HTTP_TRIGGER_STEPS.length + 1 });
  expect(session.executionStatus).toBe("COMPLETED_NORMALLY");
  expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, "Empty Script"]);
  // The engine's own record that the step is a no-op rather than a step that was skipped: it ran,
  // and it ended on the body it started with.
  const step = element(session, "Empty Script");
  expect(step?.executionStatus).toBe("COMPLETED_NORMALLY");
  // Two readings, because one cannot separate them: the step recorded its exchange at all, and the
  // body it ended on is the body it started with. The equality alone is equally green on a trace
  // that carries no exchange, where both sides read `undefined`.
  expect(step?.bodyBefore, "the step recorded no exchange at all").toBeTruthy();
  expect(step?.bodyAfter).toBe(step?.bodyBefore);
});
