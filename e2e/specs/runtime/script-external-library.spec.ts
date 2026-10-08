/**
 * The compiled-script cache, and the reflection that resets it.
 *
 * `GroovyLanguageWithResettableCache` reaches Camel's **private** `scriptCache` field by name, and
 * both failure paths are swallowed into `log.error`. A Camel upgrade that renames the field leaves
 * the platform serving stale compiled scripts with one log line as the only symptom, and no unit
 * test sees it: the reflection succeeds against the Camel version the tests compile with. This spec
 * is the reading that does — it changes a DTO library under a **deployed, unchanged** chain and
 * asserts the script answers the new library.
 *
 * **The lever is delete-and-re-import, measured rather than chosen.** Two candidates were on the
 * table. Renaming the *service* regenerates the library, but `PackageNameUtil.buildPackageName`
 * embeds the service name in the package, so the class moves and a working script turns into
 * `unable to resolve class` — an inverted assertion, and `specs/api/libraries.spec.ts` already pins
 * that the package moves. Deleting the specification and re-importing the same version reproduces
 * the model id (`{systemId}-{group}-{version}`) and therefore the **same fully-qualified class
 * name**, so one script text observes both states. Measured on the stack: the two imports produced
 * `graphql-{service}-1.0.0.jar` twice, at
 * `org.qubership.integration.engine.graphql.generated.{service}.{group}.model_1_0_0.Widget` both
 * times, carrying `color` first and `shade` after. The engine logged
 * `Removed library` → `Resetting groovy script cache` → `Saved library` →
 * `Resetting groovy script cache`, 2.5 s apart, which is `TasksScheduler.checkLibrariesUpdates`
 * seeing the delete and the re-import as two updates. So the two fixtures differ in one field name
 * and in nothing else.
 *
 * **The script names the class statically, and that is the whole mechanism.** A `Class.forName` over
 * the shell classloader would resolve the new library whether or not the cache was reset, because
 * `DefaultExternalLibraryService.updateShellClassLoader` rebuilds the loader on every change — such
 * a case is green with the reflection defeated. A class literal is resolved by the
 * `GroovyClassLoader` the script was **compiled** under, so a cached compiled script keeps
 * answering the library it was compiled against until the cache is cleared.
 *
 * **GraphQL rather than gRPC, and the codegen costs the catalog Metaspace once.** Only
 * `GraphQLCodeGenerator` and `GrpcCodeGenerator` carry `@TargetProtocol`; for http, soap, amqp and
 * kafka `generateJar` answers null and no library is produced at all. gRPC would work too — PR #797
 * put `protoc` into the catalog image — but GraphQL needs no binary toolchain in the path a
 * mutation check has to reason about, and a smaller schema. The cost is one-shot rather than per
 * import: measured, the first GraphQL import after a restart costs the catalog +12-16 MB of
 * Metaspace and the second +0.35 MB, whatever the schema's size. `infrastructure/docker-compose.yml`
 * caps the catalog at 320m, which is what makes this case's two imports affordable; at the 192m it
 * used to run under, the first one killed it.
 *
 * **The cache belongs to this case's chain, so the case runs beside the others.**
 * `IntegrationRuntimeService.buildContext` binds a new `GroovyLanguageWithResettableCache` into each
 * context it builds, and an undeploy stops only the language of the context it stops. A library
 * change another case makes resets this cache too, through the same reflection, so it cannot turn
 * the case green with the reflection broken.
 *
 * Two defeaters remain, and each would make the case green with the reflection broken:
 *
 * 1. The cache is a `newLRUSoftCache`, so a GC under memory pressure evicts the entry and the next
 *    call recompiles on its own. Nothing pins that; the mutation check is what measures whether it
 *    holds in practice, and it did — with the field literal renamed the case went red.
 * 2. The engine skips the reset when `event.isInitialUpdate()`. The log window opens **after** the
 *    first import has already been absorbed, so by then `systemModelLibraries` is non-null and the
 *    update this case asserts cannot be the initial one.
 *
 * The log is read two-sidedly and inside a window this case opened. `Resetting groovy script cache`
 * absent is a reset that never ran; `Failed to reset groovy script cache` present is the reflection
 * having broken and been swallowed. An absence-only assertion is equally green when nothing was
 * invoked, and an unscoped window is satisfied by an unrelated earlier reset. The reset line itself
 * carries no model, chain or library context, so it is read **after** this case's own
 * `Removed library` and `Saved library` lines for its model id rather than anywhere in the window.
 *
 * The micro engine is not measured here and has nothing to measure: `resetScriptCache()` has no
 * caller in `micro-engine/src/main`, because micro loads libraries before chains and loads chains
 * once at application start, so no cached script can outlive a library change.
 * `docs/product-defects.md` carries both halves.
 */
import { test, expect } from "../../support/fixtures.js";
import {
  ENGINE_CASE_TIMEOUT,
  SEED_LOGGING,
  waitForDeployed,
  waitForRoutes,
} from "../../support/corpus.js";
import { readSpecificationFixture } from "../../fixtures/templating.js";
import { createScriptChain, SCRIPT_CHAIN_STEPS } from "../../support/deployable.js";
import { logWindowStart } from "../../support/logs.js";
import { readUntil } from "../../support/poll.js";
import { tokenized } from "../../support/run.js";
import { callChain, elementNames, HTTP_TRIGGER_STEPS } from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";
import { waitForRecording, withBuilt } from "../../support/cleanup.js";
import type { APIRequestContext } from "@playwright/test";
import type { Env } from "../../env/index.js";

/** The version the GraphQL parser's model is given: the document declares none, so the catalog counts. */
const MODEL_VERSION = "1.0.0";

/** The script element's name, which is what the trace carries. */
const READ_WIDGET = "Read Widget";

/** The header the script answers the resolved class name in, so a package move is visible as itself. */
const CLASS_HEADER = "e2e-widget-class";

/** How long the engine is given to pick a library change up: `checkLibrariesUpdates` is a 2.5 s loop. */
const LIBRARY_TIMEOUT = 60_000;

/**
 * `PackageNameUtil.buildPackageName`, reduced to the one rule that applies here: a run of non-word
 * characters folds to a single `_`. The production form also strips leading and trailing `_` and
 * prefixes a segment that does not start with a letter; every name this case mints begins `e2e-`,
 * so neither can fire.
 */
function packageSegment(name: string): string {
  return name.replace(/\W+/g, "_").toLowerCase();
}

/** The fully-qualified name of the one DTO the fixtures generate, as the script has to spell it. */
function widgetClass(serviceName: string, groupName: string): string {
  return [
    "org.qubership.integration.engine.graphql.generated",
    packageSegment(serviceName),
    packageSegment(groupName),
    `model_${MODEL_VERSION.replace(/\./g, "_")}`,
    "Widget",
  ].join(".");
}

/**
 * The script, which is the same text across both states — that is what makes the cache the subject.
 *
 * The class literal is what binds the compiled script to one library. `declaredFields` is read
 * rather than a getter called, because Groovy dispatches a method call dynamically and a missing
 * `getShade()` would be a runtime failure rather than a different answer.
 */
function readWidgetScript(fqcn: string): string {
  return [
    `// script-external-library/${fqcn}`,
    `def widget = ${fqcn}.class`,
    `def fields = widget.getDeclaredFields().collect { it.getName() }.sort()`,
    `exchange.getIn().setHeader('${CLASS_HEADER}', widget.getName())`,
    `exchange.getIn().setBody(groovy.json.JsonOutput.toJson([fields: fields]))`,
  ].join("\n");
}

/**
 * Returns once the engine has the model's jar on disk.
 *
 * Read from the log rather than slept on, and it is not decoration: the eager compile at deploy
 * (`IntegrationRuntimeService.compileGroovyScript`) raises `DeploymentRetriableException` for an
 * unresolved class and retries **indefinitely**, so a deploy that races the library never fails —
 * it hangs, and the case times out somewhere with no reason attached.
 */
async function waitForLibrary(env: Env, since: string, modelId: string): Promise<void> {
  const saved = `Saved library: /tmp/cip-engine-libraries/${modelId}.jar`;
  const log = await readUntil(
    () => env.logs("engine", since),
    (read) => read.includes(saved),
    LIBRARY_TIMEOUT,
    1_000,
  );
  expect(log.includes(saved), `the engine never loaded the library for ${modelId}`).toBe(true);
}

/** The field names the deployed script reports for the class it resolves, plus the class it resolved. */
async function widgetFields(
  request: APIRequestContext,
  url: string,
): Promise<{ fields: string[]; className: string; status: number }> {
  const call = await callChain(request, url, { data: {} });
  const status = call.response.status();
  const body = await call.response.text();
  return {
    status,
    className: call.response.headers()[CLASS_HEADER] ?? "",
    // No fallback inside the 200 arm: the script always writes `fields`, and a default would feed
    // the poll a silent empty array where a missing field should fail on the spot.
    fields: status === 200 ? (JSON.parse(body) as { fields: string[] }).fields : [],
  };
}

test("a DTO library replaced under a deployed script is seen without a redeploy", { tag: ["@engine", "@catalog", "@sessions", "@tier1"] }, async ({ request, env, catalog, sessions, folder, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  covers("script");

  await withBuilt(catalog, async (built) => {
    const serviceName = tokenized(run, "extlib");
    const groupName = tokenized(run, "extlibgrp");
    const fqcn = widgetClass(serviceName, groupName);

    const system = await catalog.createSystem(serviceName, "EXTERNAL");
    built.services.push({
      name: `the service ${system.name} (${system.id})`,
      remove: () => catalog.deleteSystem(system.id),
    });
    const groupId = `${system.id}-${groupName}`;
    const modelId = `${groupId}-${MODEL_VERSION}`;

    const firstImport = logWindowStart();
    await catalog.awaitSpecificationImport(
      await catalog.importSpecificationGroup(
        system.id,
        groupName,
        readSpecificationFixture("widgets-color.graphql"),
        "graphql",
      ),
    );
    await waitForLibrary(env, firstImport, modelId);

    const chain = await createScriptChain(
      catalog,
      run,
      {
        what: "extlib",
        parentId: folder.id,
        script: readWidgetScript(fqcn),
        scriptName: READ_WIDGET,
        logging: SEED_LOGGING,
      },
      built.chains,
    );
    const snapshot = await catalog.createSnapshot(chain.id);
    await catalog.deploy(chain.id, snapshot.id);
    await waitForDeployed(catalog, [chain]);
    await waitForRoutes(env, [chain]);
    await waitForRecording(env, sessions, chain);

    const before = await widgetFields(request, env.chainUrl(chain.contextPath));
    expect(before.status).toBe(200);
    expect(before.className, "the script resolved the generated DTO").toBe(fqcn);
    expect(before.fields, "the first state declares color").toEqual(["color", "id", "name"]);

    // Opened here, so `Resetting groovy script cache` below is this case's reset and not one an
    // earlier import left in the log.
    const since = logWindowStart();

    // The lever. Two steps, because `SystemModelService.deleteSystemModel` refuses a specification
    // that is not deprecated, and the re-import goes into the group the first one created — which
    // is what reproduces the model id, and with it the package.
    await catalog.deprecateModel(modelId);
    const deleted = await catalog.raw("delete", `/v1/models/${modelId}`);
    expect(deleted.status(), await deleted.text()).toBe(204);
    await catalog.awaitSpecificationImport(
      await catalog.importSpecification(groupId, readSpecificationFixture("widgets-shade.graphql")),
    );

    // No redeploy, no undeploy, no snapshot: the chain the engine is serving is the one it was
    // serving above, and the only thing that changed is the jar under it. Polled rather than called
    // once, because the engine picks the change up on a 2.5 s loop and does it in two updates.
    await expect
      .poll(async () => (await widgetFields(request, env.chainUrl(chain.contextPath))).fields, {
        timeout: LIBRARY_TIMEOUT,
        intervals: [1_000],
        message: "the deployed script kept answering the library it was compiled against",
      })
      .toEqual(["id", "name", "shade"]);

    const after = await widgetFields(request, env.chainUrl(chain.contextPath));
    expect(after.className, "the lever changed the library, not the class name").toBe(fqcn);

    const log = await env.logs("engine", since);
    // The positive half, tied to this case's own library rather than to the window alone. The reset
    // line names nothing, so a concurrent GraphQL import elsewhere would satisfy a bare `toContain`
    // — the model id is what makes the line this case's, and the reset has to come after it.
    const savedAgain = log.lastIndexOf(`Saved library: /tmp/cip-engine-libraries/${modelId}.jar`);
    expect(savedAgain, `the engine never reloaded the library for ${modelId}`).toBeGreaterThan(-1);
    expect(
      log.indexOf("Resetting groovy script cache", savedAgain),
      "the library changed and no reset was invoked for it",
    ).toBeGreaterThan(-1);
    expect(log, "the reflection failed and the engine swallowed it into log.error").not.toContain(
      "Failed to reset groovy script cache",
    );

    // The trace, so a green case also says the call went through the element it claims to.
    const call = await callChain(request, env.chainUrl(chain.contextPath), { data: {} });
    const session = await sessions.byExternalId(call.token, { elements: SCRIPT_CHAIN_STEPS });
    expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, READ_WIDGET]);
  });
});
