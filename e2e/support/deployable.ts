/**
 * The smallest chain that can be deployed and then observed from outside: an HTTP trigger into a
 * header modification.
 *
 * Both the snapshot spec and the deployment spec need one, and they need it built the same way, so
 * it is here rather than copied twice. What it gives a spec:
 *
 * - a `contextPath` carrying the run token, so two runs and two workers cannot collide on one
 *   Camel route;
 * - an echoed request body, which is what an HTTP trigger does with no sender behind it;
 * - a **marker response header**, which is the only cheap way to tell one deployed version of a
 *   chain from another. A redeploy that swaps the marker is observable from a single request; a
 *   redeploy that changes nothing observable cannot be asserted at all.
 *
 * It makes no outbound call, so the correlation header stays usable and the testing service never
 * intercepts anything — the same reason `fixtures/chains/http-echo/` is shaped this way.
 *
 * Three element properties are set rather than left at their creation defaults, and each one
 * matters: `contextPath` is the route, `httpMethodRestrict: POST` makes a `GET` answer **405**
 * while the route is live and **404** once it is gone (which is how an undeploy is asserted on the
 * engine rather than on the catalog's own record of it), and `externalRoute: false` keeps the route
 * off the gateway, where a duplicate path is refused by a different check.
 */
import type { Catalog, LoggingProperties } from "./catalog.js";
import type { SeedChain } from "./corpus.js";
import { noteChain } from "./diagnostics.js";
import { tokenized } from "./run.js";

/** The response header the header modification adds, and the handle on which version answered. */
export const MARKER_HEADER = "e2e-marker";

export interface DeployableChain {
  id: string;
  name: string;
  contextPath: string;
  triggerId: string;
  headerElementId: string;
}

export interface DeployableOptions {
  name: string;
  parentId: string;
  contextPath: string;
  marker: string;
  /** Puts the trigger on the public gateway, for a case about external routes. */
  externalRoute?: boolean;
}

/** The properties a live HTTP trigger needs, merged over the ones creation supplied. */
function triggerProperties(contextPath: string, externalRoute = false): Record<string, unknown> {
  return { contextPath, httpMethodRestrict: "POST", externalRoute };
}

function markerProperties(marker: string): Record<string, unknown> {
  return { headerModificationToAdd: { [MARKER_HEADER]: marker }, headerModificationToRemove: [] };
}

/** Creates the chain and wires it. It is not snapshotted or deployed — the spec decides that. */
export async function createDeployableChain(
  catalog: Catalog,
  options: DeployableOptions,
): Promise<DeployableChain> {
  const chain = await catalog.createChain(options.name, options.parentId);
  const trigger = await catalog.createElement(chain.id, "http-trigger");
  await catalog.patchElementProperties(
    chain.id,
    trigger.id,
    triggerProperties(options.contextPath, options.externalRoute),
  );
  const header = await catalog.createElement(chain.id, "header-modification");
  await catalog.patchElementProperties(chain.id, header.id, markerProperties(options.marker));
  await catalog.connectElements(chain.id, trigger.id, header.id);
  return {
    id: chain.id,
    name: chain.name,
    contextPath: options.contextPath,
    triggerId: trigger.id,
    headerElementId: header.id,
  };
}

export interface TokenizedChainOptions {
  /** Which spec the chain belongs to, so two specs asking for `live` cannot collide on one route. */
  prefix: string;
  /** What the chain is for inside that spec. Names the chain and its route. */
  what: string;
  parentId: string;
  /** The marker header, when a case has to tell one deployed version of the chain from another. */
  marker?: string;
  externalRoute?: boolean;
}

/**
 * The same chain, named and routed off the run token.
 *
 * Three specs wrote this wrapper themselves and differed only in the prefix. The name and the
 * context path are the same string on purpose: a route the sweep can find is a route named after
 * the chain that holds it.
 */
export async function tokenizedChain(
  catalog: Catalog,
  run: string,
  options: TokenizedChainOptions,
): Promise<DeployableChain> {
  const name = tokenized(run, `${options.prefix}-${options.what}`);
  return await createDeployableChain(catalog, {
    name,
    parentId: options.parentId,
    contextPath: name,
    marker: options.marker ?? options.what,
    externalRoute: options.externalRoute,
  });
}

/** An empty chain named `name` under the run token, inside `folderId`, so the folder cascade removes it. */
export async function emptyChain(catalog: Catalog, run: string, folderId: string, name: string): Promise<string> {
  return (await catalog.createChain(tokenized(run, name), folderId)).id;
}

/**
 * How many steps one call through a chain this builder made records.
 *
 * Three, not two: the trigger emits a nested `Validate Request` step of its own type whether or not
 * `validateRequest` is set, and this builder sets it to `false`. Measured on this stack, and it
 * matters because a session lookup returns on the **first** trace carrying at least this many steps
 * — ask for two and a payload assertion can be handed a trace the script step has not reached yet.
 * A chain built with `between` records one more, and a caller wanting that step waited for says so.
 */
export const SCRIPT_CHAIN_STEPS = 3;

export interface ScriptChainOptions {
  /** Names the chain and its route, off the run token. */
  what: string;
  parentId: string;
  /** The body of the terminal script. Throwing from it is how a case makes the chain fail. */
  script: string;
  /** The script element's name, which is what the trace carries and what a spec asserts on. */
  scriptName: string;
  /**
   * An element type to wire between the trigger and the script, for a case that needs the exchange
   * to pass one on the way. No properties are written for it — the element types this takes fill
   * their own on create — and its id comes back in `elements` under the type it was asked for.
   */
  between?: string;
  /**
   * The runtime properties to write before the deploy.
   *
   * Written by the builder rather than by the caller because the Consul key it creates is what
   * `built` exists to hand to the teardown: a chain that has one and is not on that list orphans
   * the key under an id nothing can list.
   *
   * `Partial`, because the endpoint takes one: a case pinning a single level writes that level
   * alone, and a case whose trace assertions rest on the corpus's seven fields spreads them.
   */
  logging: Partial<LoggingProperties>;
}

/**
 * A one-trigger, one-script chain in the worker's folder, wired and ready to snapshot.
 *
 * The terminal element is the parameter, and it is the only thing the first two callers differed
 * in: the logging spec runs a script that throws on a header, the metrics spec one that always
 * throws. `between` came later, for the one case that needs a checkpoint on the path, and it is a
 * wiring difference rather than a second prologue.
 *
 * Every step of that — the trigger, the connections, the logging write, the diagnostics note and
 * the `built` entry that makes the caller's `finally` releasable — is `createStepsChain`, so this
 * is the same builder with the steps spelled for it. `between` is given no name of its own, which
 * is what keeps the element at the name the library creates it with; the script's name is the one
 * a spec asserts on, and its id comes back in `elements` under it.
 */
export async function createScriptChain(
  catalog: Catalog,
  run: string,
  options: ScriptChainOptions,
  built: BuiltChain[],
): Promise<SeedChain> {
  const steps: ChainStep[] = [];
  if (options.between) {
    steps.push({ name: options.between, type: options.between, properties: {}, keepName: true });
  }
  steps.push({ name: options.scriptName, type: "script", properties: { script: options.script } });

  return await createStepsChain(
    catalog,
    run,
    { what: options.what, parentId: options.parentId, steps, logging: options.logging },
    built,
  );
}

/** What a builder appends to `built`; `ReleasableChain` in `support/cleanup.ts` matches it. */
export interface BuiltChain {
  id: string;
  name: string;
}

/** One element of a chain `createStepsChain` builds: its type, the name the trace reports, its properties. */
export interface ChainStep {
  /** The key its id comes back under in `elements`, and the name the trace reports unless `keepName`. */
  name: string;
  type: string;
  properties: Record<string, unknown>;
  /** Leaves the element at the name creation gave it, for a step addressed by id rather than by trace. */
  keepName?: boolean;
}

export interface StepsChainOptions {
  /** Names the chain and its route, off the run token. */
  what: string;
  parentId: string;
  /** The elements after the trigger, wired in order. Each id comes back in `elements` under its name. */
  steps: ChainStep[];
  /** A trigger other than the HTTP one `triggeredChain` writes by default, for a chain no HTTP call starts. */
  trigger?: ChainStep;
  logging: Partial<LoggingProperties>;
}

/**
 * The builder: a trigger from `triggeredChain`, then the given steps wired in order, for a case
 * whose element needs ids a seeded chain cannot carry. Not snapshotted or deployed.
 *
 * `createScriptChain` is the one-script specialization of this, and every other shape a case needs
 * goes through here rather than growing a builder of its own.
 */
export async function createStepsChain(
  catalog: Catalog,
  run: string,
  options: StepsChainOptions,
  built: BuiltChain[],
): Promise<SeedChain> {
  const { seeded, triggerId } = await triggeredChain(catalog, run, options.what, options.parentId, built, options.trigger);
  let upstream = triggerId;
  for (const step of options.steps) {
    const created = await catalog.createElement(seeded.id, step.type);
    await catalog.patchElementProperties(
      seeded.id,
      created.id,
      step.properties,
      step.keepName ? undefined : step.name,
    );
    await catalog.connectElements(seeded.id, upstream, created.id);
    seeded.elements[step.name] = created.id;
    upstream = created.id;
  }
  await writeLoggingAndNote(catalog, seeded, options.logging);
  return seeded;
}

/** A chain named off the run token and its trigger, appended to `built` as soon as it has an id. */
async function triggeredChain(
  catalog: Catalog,
  run: string,
  what: string,
  parentId: string,
  built: BuiltChain[],
  trigger?: ChainStep,
): Promise<{ seeded: SeedChain; triggerId: string }> {
  const name = tokenized(run, what);
  const chain = await catalog.createChain(name, parentId);
  const seeded: SeedChain = {
    fixture: what,
    id: chain.id,
    name,
    contextPath: name,
    elements: {},
  };
  built.push(seeded);

  const created = await catalog.createElement(chain.id, trigger?.type ?? "http-trigger");
  if (trigger) {
    await catalog.patchElementProperties(chain.id, created.id, trigger.properties, trigger.name);
    seeded.elements[trigger.name] = created.id;
  } else {
    await catalog.patchElementProperties(chain.id, created.id, {
      contextPath: name,
      httpMethodRestrict: "POST",
      accessControlType: "NONE",
      externalRoute: false,
      validateRequest: false,
    });
  }
  return { seeded, triggerId: created.id };
}

async function writeLoggingAndNote(catalog: Catalog, seeded: SeedChain, logging: Partial<LoggingProperties>): Promise<void> {
  // Before the deploy, and that ordering is convenience rather than a constraint. Measured against
  // the code: `DeploymentBuilderService` carries `maskedFields` and no runtime properties, and the
  // engine resolves the level per exchange from the live Consul-backed `AtomicReference` in
  // `CamelDebuggerPropertiesService` — so a level raised on a running route takes effect on the
  // next call. Written here anyway, because a chain already serving at OFF would otherwise need
  // the cache refresh polled before the first call could be trusted.
  await catalog.saveLoggingProperties(seeded.id, logging);
  noteChain({ id: seeded.id, name: seeded.name, contextPath: seeded.contextPath });
}

/** Changes what the chain answers, so the next snapshot is observably a different one. */
export async function setMarker(
  catalog: Catalog,
  chain: DeployableChain,
  marker: string,
): Promise<void> {
  await catalog.patchElementProperties(chain.id, chain.headerElementId, markerProperties(marker));
}

/**
 * A second HTTP trigger on the same path, which is how a deployment is made to fail on demand.
 *
 * Measured, and it is not the obvious shape: two **chains** sharing a `contextPath` on one
 * domain both reach `DEPLOYED` and the last one deployed owns the route. What the catalog refuses
 * is a single snapshot carrying two triggers on one path — `POST .../deployments` answers **409**
 * naming the path, and nothing is deployed.
 */
export async function addCollidingTrigger(
  catalog: Catalog,
  chain: DeployableChain,
): Promise<string> {
  const trigger = await catalog.createElement(chain.id, "http-trigger");
  await catalog.patchElementProperties(chain.id, trigger.id, triggerProperties(chain.contextPath));
  const header = await catalog.createElement(chain.id, "header-modification");
  await catalog.patchElementProperties(chain.id, header.id, markerProperties("collision"));
  await catalog.connectElements(chain.id, trigger.id, header.id);
  return trigger.id;
}
