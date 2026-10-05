/**
 * What the sweeps keep, what they delete, and what they only log.
 *
 * Both readings turn on one asymmetry between the two hops that clean a chain up. A chain left
 * deployed is still named by its id and its folder, so the next sweep reaches it. Its logging
 * properties are a Consul key filed under the chain id, and `ChainRuntimePropertiesService`
 * exposes no listing, so a chain deleted over a failed key delete leaves the key under an id nothing
 * can address — and the residue check that follows reports a clean stack over it. So a failed key
 * delete keeps the chain, and a failed undeploy does not: the chain delete undeploys on its own
 * (`ChainService.deleteByIdIfExists` calls `deploymentService.deleteAllByChainId`,
 * `ChainService.java:190`), so keeping the chain would block the call that removes the route.
 *
 * No stack. `sweepRunToken` and `releaseChains` take a catalog client, and the stub below is a
 * catalog that answers a fixed stack and refuses the calls a case names. The two services the
 * catalog cannot answer for — the recorded sessions and the endpoint mocks — arrive through the
 * same seam: `OutsideCatalog` is an optional parameter precisely so this file can drive the sweep
 * over both without a stack, and `stubOutside` below is the pair a case puts in front of it.
 */
import fs from "node:fs";
import path from "node:path";
import { test, expect } from "@playwright/test";
import type { Catalog, Named } from "../../support/catalog.js";
import type { ReleasableChain } from "../../support/cleanup.js";
import { mocksOfRun, type EndpointMock } from "../../support/testing-service.js";
import { repoRoot } from "../../env/host.js";
import {
  findResidue,
  sessionsOfRun,
  sweepRunToken,
  sweptClean,
  type OutsideCatalog,
  type SweepOutcome,
} from "../../support/fixtures.js";
import { EXAMPLE_RUN_TOKEN } from "../../support/run.js";
import { release, releaseChains } from "../../support/cleanup.js";
import { whileCollectingErrors } from "../../support/console-errors.js";

const RUN = EXAMPLE_RUN_TOKEN;
const FOLDER = { id: "f1", name: `e2e-${RUN}-w0`, itemType: "FOLDER" };
const CHAIN = { id: "c1", name: `e2e-${RUN}-metrics-failure` };

/**
 * A chain that recorded sessions, in the shape `sessionsOfRun` hands over: the id is the chain's,
 * because `DELETE /v1/sessions/chains?chainIds=…` is what the sweep deletes them with, and the
 * hundred-odd documents behind it are already reduced to this one row.
 */
const RECORDED: Named = { id: "seed1", name: `e2e-${RUN}-http-echo` };

/**
 * A detailed-design template this run named, and the built-in it sits beside.
 *
 * The pair is the whole point: a template belongs to no folder and to no chain, so nothing cascades
 * to it, and the only thing separating "sweep this" from "sweep the platform's own" is the token in
 * the name. `Default` is compiled in, and `DELETE /templates` over it answers 204 without removing
 * anything.
 */
const TEMPLATE: Named = { id: "t1", name: `e2e-${RUN}-ghost` };
const BUILT_IN_TEMPLATE: Named = { id: "default", name: "Default" };

/**
 * A mock this run named.
 *
 * `endpointReference` is a chain and an element a fixture freezes, which is the whole hazard: the
 * name is the only part of a mock that says which run made it, and a survivor goes on answering for
 * that sender on every later run.
 */
const MOCK: EndpointMock = {
  id: "m1",
  name: `e2e-${RUN}-mock`,
  enabled: true,
  endpointReference: { chainId: CHAIN.id, elementId: "sender" },
};

/** The classic domain every stack lists, whose name carries no run token. */
const DEFAULT = { id: "default", name: "default", type: "CLASSIC" };

/** The micro domain `seed-micro` names after the run. */
const MICRO_DOMAIN = { id: `e2e-${RUN}-micro`, name: `e2e-${RUN}-micro`, type: "MICRO" };

/** The stack a case puts in front of the sweep, and the calls it makes fail. */
interface StackShape {
  /** Root items, as `GET /v1/folders` answers them. */
  root?: Array<Named & { itemType: string }>;
  /** Chains, as `GET /v1/chains` answers them. */
  chains?: Named[];
  /** The chains a folder holds, by folder id. */
  nested?: Record<string, Named[]>;
  /** Chain ids the logging-properties read reports a `custom` layer for. */
  keys?: string[];
  /** Detailed-design templates, as `GET /v1/detailed-design/templates` answers them. */
  templates?: Named[];
  /** Engine domains, as `GET /v1/catalog/domains` answers them. */
  domains?: Array<Named & { type: string }>;
  /** The calls that reject, named `method` or `method:id`. */
  failing?: string[];
  /** The calls that reject the first time and answer after that, named the same way. */
  failingOnce?: string[];
}

/**
 * A catalog that answers `shape` and records every call into `calls`, in order.
 *
 * The call names are what the assertions read: whether a delete was issued at all is the finding in
 * half the cases below, and a chain nothing tried to delete looks exactly like one that was kept.
 */
function stubCatalog(shape: StackShape, calls: string[] = []): Catalog {
  const refuses = new Set(shape.failing ?? []);
  // Consumed on its first call, which is what separates a hop that fails from one that fails once:
  // a retry is worth nothing unless a case can show the second attempt answering.
  const refusesOnce = new Set(shape.failingOnce ?? []);
  const cascaded = new Set<string>();
  const call = <T>(what: string, answer: T): Promise<T> => {
    calls.push(what);
    if (refusesOnce.delete(what)) {
      return Promise.reject(new Error(`the stub catalog refused ${what} once`));
    }
    return refuses.has(what)
      ? Promise.reject(new Error(`the stub catalog refused ${what}`))
      : Promise.resolve(answer);
  };
  const stub = {
    listRootItems: () => call("listRootItems", shape.root ?? []),
    listChains: () => call("listChains", shape.chains ?? []),
    listDomains: () => call("listDomains", shape.domains ?? [DEFAULT]),
    deleteCustomResource: (name: string) => call(`deleteCustomResource:${name}`, undefined),
    listNestedChains: (id: string) => call(`listNestedChains:${id}`, shape.nested?.[id] ?? []),
    getLoggingProperties: (id: string) =>
      call(
        `getLoggingProperties:${id}`,
        (shape.keys ?? []).includes(id) ? { fallbackDefault: {}, custom: {} } : { fallbackDefault: {} },
      ),
    listSystems: () => call("listSystems", []),
    listSpecificationGroups: (id: string) => call(`listSpecificationGroups:${id}`, []),
    listEnvironments: (id: string) => call(`listEnvironments:${id}`, []),
    listContextSystems: () => call("listContextSystems", []),
    listMcpSystems: () => call("listMcpSystems", []),
    listCommonVariables: () => call("listCommonVariables", {}),
    listDesignTemplates: () => call("listDesignTemplates", shape.templates ?? []),
    deleteDesignTemplates: (ids: readonly string[]) =>
      call(`deleteDesignTemplates:${ids.join(",")}`, undefined),
    listSecuredVariables: () => call("listSecuredVariables", []),
    undeployAll: (id: string) => call(`undeployAll:${id}`, undefined),
    deleteLoggingProperties: (id: string) => call(`deleteLoggingProperties:${id}`, undefined),
    // A folder delete cascades to the chains `nested` puts in it, as the catalog's does.
    deleteChain: (id: string) =>
      cascaded.has(id)
        ? call(`deleteChain:${id}`, undefined).then(() => {
            throw new Error(`DELETE /v1/chains/${id} answered 404`);
          })
        : call(`deleteChain:${id}`, undefined),
    deleteFolder: (id: string) =>
      call(`deleteFolder:${id}`, undefined).then(() => {
        for (const chain of shape.nested?.[id] ?? []) cascaded.add(chain.id);
      }),
    holds: (path: string) => call(`holds:${path}`, !cascaded.has(path.split("/").pop() ?? "")),
    moveChain: (id: string) => call(`moveChain:${id}`, { id, name: CHAIN.name }),
  };
  return stub as unknown as Catalog;
}

/** The stack the two outside services put in front of the sweep. */
interface OutsideShape {
  /** The chains a run recorded, as `sessionsOfRun` reduces them. */
  sessions?: Named[];
  /** The mocks a run named. */
  mocks?: EndpointMock[];
  /** The calls that reject, named `client.method` or `client.method:id`. */
  failing?: string[];
}

/**
 * The sessions and mock clients, answering `shape` and recording into the same `calls` the catalog
 * stub writes to.
 *
 * One array for both, because the order across the two is a finding: the mock delete has to be
 * issued before every other delete, and two arrays cannot show that.
 */
function stubOutside(shape: OutsideShape = {}, calls: string[] = []): OutsideCatalog {
  const refuses = new Set(shape.failing ?? []);
  const call = <T>(what: string, answer: T): Promise<T> => {
    calls.push(what);
    return refuses.has(what)
      ? Promise.reject(new Error(`the stub refused ${what}`))
      : Promise.resolve(answer);
  };
  return {
    sessions: {
      of: () => call("sessions.of", shape.sessions ?? [RECORDED]),
      remove: (chainIds: readonly string[]) =>
        call(`sessions.remove:${chainIds.join(",")}`, undefined),
    },
    mocks: {
      of: () => call("mocks.of", shape.mocks ?? [MOCK]),
      remove: (id: string) => call(`mocks.remove:${id}`, undefined),
    },
  };
}

/**
 * Runs `body` with `fetch` refusing every call, and answers with what `body` produced.
 *
 * The only way to show that a reading made no HTTP call at all. A stack answering an unwanted call
 * with an empty list looks exactly like a reading that never made it, so an assertion over the rows
 * alone passes on a machine with a stack and fails on one with no stack to answer.
 */
async function whileRefusingFetch<T>(body: () => Promise<T>): Promise<T> {
  const original = globalThis.fetch;
  globalThis.fetch = ((input: unknown) =>
    Promise.reject(new Error(`a schema spec reached ${String(input)}`))) as typeof fetch;
  try {
    return await body();
  } finally {
    globalThis.fetch = original;
  }
}

/** One chain of the two specs that build their own, in the shape `releaseChains` takes. */
const seeded: ReleasableChain[] = [{ id: CHAIN.id, name: CHAIN.name }];

test("a run is forgotten only when nothing failed and nothing was left", { tag: ["@infra", "@tier1"] }, () => {
  const row = { kind: "chain", id: "c1", name: CHAIN.name };
  const other = { kind: "folder", id: "f1", name: FOLDER.name };
  const clean: SweepOutcome = { residue: [row], removed: [row], failures: [] };
  expect(sweptClean(clean)).toBe(true);
  expect(sweptClean({ residue: [], removed: [], failures: [] })).toBe(true);

  // Two readings rather than one, and each catches what the other cannot: a delete can fail without
  // the row being reported, and a row can survive a delete that reported nothing.
  expect(sweptClean({ ...clean, failures: ["chain c1: 500"] })).toBe(false);
  expect(sweptClean({ residue: [row, other], removed: [row], failures: [] })).toBe(false);
});

test("a chain whose Consul key survived is kept, and so is the folder holding it", { tag: ["@infra", "@tier1"] }, async () => {
  const calls: string[] = [];
  const outcome = await sweepRunToken(
    stubCatalog(
      {
        root: [FOLDER],
        chains: [CHAIN],
        nested: { f1: [CHAIN] },
        keys: [CHAIN.id],
        failing: [`deleteLoggingProperties:${CHAIN.id}`],
      },
      calls,
    ),
    RUN,
  );

  // Neither delete was issued. Deleting the chain would put the key under an id nothing can list,
  // and deleting the folder cascades to the same chain.
  expect(calls).not.toContain(`deleteChain:${CHAIN.id}`);
  expect(calls).not.toContain("deleteFolder:f1");
  expect(outcome.failures.join("; ")).toContain("logging properties of chain");
  expect(outcome.failures.join("; ")).toContain(`folder ${FOLDER.name} was kept`);
  // And the run keeps its manifest entry, which is the handle the next sweep finds it by.
  expect(sweptClean(outcome)).toBe(false);
});

test("a folder whose chains cannot be listed is kept rather than cascaded", { tag: ["@infra", "@tier1"] }, async () => {
  const calls: string[] = [];
  const outcome = await sweepRunToken(
    stubCatalog(
      {
        root: [FOLDER],
        chains: [CHAIN],
        keys: [CHAIN.id],
        failing: [`deleteLoggingProperties:${CHAIN.id}`, "listNestedChains:f1"],
      },
      calls,
    ),
    RUN,
  );

  // A listing that failed is not an empty folder, and deleting on the strength of one cascades away
  // the very chain the failed key delete is holding back.
  expect(calls).not.toContain("deleteFolder:f1");
  expect(outcome.failures.join("; ")).toContain("its chains could not be listed");
});

test("a failed undeploy is logged and the chain is deleted anyway", { tag: ["@infra", "@tier1"] }, async () => {
  const calls: string[] = [];
  let outcome: SweepOutcome | null = null;
  const said = await whileCollectingErrors(async () => {
    outcome = await sweepRunToken(
      stubCatalog({ chains: [CHAIN], failing: [`undeployAll:${CHAIN.id}`] }, calls),
      RUN,
    );
  });

  // The delete undeploys the chain itself, so a chain kept over this hop would keep its route
  // forever — and on a stack with the classic domain disabled every chain of every run would be kept.
  expect(calls).toContain(`deleteChain:${CHAIN.id}`);
  expect(said.join("\n")).toContain("was not undeployed before its delete");
  expect(outcome!.failures).toEqual([]);
  expect(sweptClean(outcome!)).toBe(true);
});

test("a failed chain delete is reported and counts as still there", { tag: ["@infra", "@tier1"] }, async () => {
  const outcome = await sweepRunToken(
    stubCatalog({ chains: [CHAIN], failing: [`deleteChain:${CHAIN.id}`] }),
    RUN,
  );

  expect(outcome.failures.join("; ")).toContain(`chain ${CHAIN.name}`);
  expect(outcome.removed).toEqual([]);
  expect(sweptClean(outcome)).toBe(false);
});

test("a chain its folder's delete already removed is swept, not reported", { tag: ["@infra", "@tier1"] }, async () => {
  const calls: string[] = [];
  const outcome = await sweepRunToken(
    stubCatalog({ root: [FOLDER], chains: [CHAIN], nested: { f1: [CHAIN] } }, calls),
    RUN,
  );

  // `GET /v1/chains` lists the chain beside its folder, so its own delete comes after the cascade
  // and answers 404. Counting that as a failure kept every kept run in the manifest.
  expect(calls).toContain(`deleteChain:${CHAIN.id}`);
  expect(calls).toContain(`holds:/v1/chains/${CHAIN.id}`);
  expect(outcome.failures).toEqual([]);
  expect(sweptClean(outcome)).toBe(true);
});

test("a logging-properties read that failed still issues the key delete", { tag: ["@infra", "@tier1"] }, async () => {
  const calls: string[] = [];
  const outcome = await sweepRunToken(
    stubCatalog({ chains: [CHAIN], failing: [`getLoggingProperties:${CHAIN.id}`] }, calls),
    RUN,
  );

  // A read that could not answer says nothing about whether a key is there. Taking it for "no key"
  // lets a transient error authorize the chain delete over a key nothing could then find.
  expect(calls).toContain(`deleteLoggingProperties:${CHAIN.id}`);
  expect(outcome.residue.map((each) => each.kind)).not.toContain("logging-properties");
  expect(calls).toContain(`deleteChain:${CHAIN.id}`);
});

test("a spec's own chain is moved out of the worker folder when its key survives", { tag: ["@infra", "@tier1"] }, async () => {
  const calls: string[] = [];
  const catalog = stubCatalog({ failing: [`deleteLoggingProperties:${CHAIN.id}`] }, calls);

  // The `folder` fixture deletes the worker folder after this worker's last test and the cascade
  // takes the chain with it, so keeping the chain here means moving it to the root.
  await expect(releaseChains(catalog, seeded, false)).rejects.toThrow(/logging key/);
  expect(calls).toContain(`moveChain:${CHAIN.id}`);
});

test("a spec's own chain is not moved when its key went", { tag: ["@infra", "@tier1"] }, async () => {
  const calls: string[] = [];
  await releaseChains(stubCatalog({}, calls), seeded, false);
  expect(calls).toEqual([`deleteLoggingProperties:${CHAIN.id}`, `undeployAll:${CHAIN.id}`]);
});

test("a spec's own chain that could not be undeployed is logged, not failed over", { tag: ["@infra", "@tier1"] }, async () => {
  const said = await whileCollectingErrors(async () => {
    await releaseChains(stubCatalog({ failing: [`undeployAll:${CHAIN.id}`] }), seeded, false);
  });
  expect(said.join("\n")).toContain("was not undeployed");
});

test("a cleanup failure never displaces the assertion that already failed", { tag: ["@infra", "@tier1"] }, async () => {
  const said = await whileCollectingErrors(async () => {
    await releaseChains(
      stubCatalog({ failing: [`deleteLoggingProperties:${CHAIN.id}`] }),
      seeded,
      true,
    );
  });
  // Reported as context and thrown nowhere: a teardown error raised over a real finding leaves the
  // reader with the leak instead of the reason for it.
  expect(said.join("\n")).toContain("this spec left state on the stack");
});

test("a chain that could not be moved out spends its last moment on the key", { tag: ["@infra", "@tier1"] }, async () => {
  const calls: string[] = [];
  const catalog = stubCatalog(
    { failingOnce: [`deleteLoggingProperties:${CHAIN.id}`], failing: [`moveChain:${CHAIN.id}`] },
    calls,
  );

  const said = await whileCollectingErrors(async () => {
    // The chain stays in the worker folder, so the cascade is about to take it and the id the key
    // is filed under with it. The second delete is the last one that can reach the key at all.
    await releaseChains(catalog, seeded, false);
  });

  expect(calls.filter((each) => each === `deleteLoggingProperties:${CHAIN.id}`)).toHaveLength(2);
  expect(said.join("\n")).toContain("deleted on a second attempt");
  // Nothing was left behind, so nothing is raised: a teardown that fails over a stack it put back
  // turns a transient catalog error into a red spec.
  expect(said.join("\n")).toContain("Nothing was left behind");
});

test("a key that survives both deletes and a failed move is reported as orphaned", { tag: ["@infra", "@tier1"] }, async () => {
  const calls: string[] = [];
  const catalog = stubCatalog(
    { failing: [`deleteLoggingProperties:${CHAIN.id}`, `moveChain:${CHAIN.id}`] },
    calls,
  );

  // The one outcome this module cannot recover from, and the reason it has to be named: the cascade
  // deletes the chain, and `ChainRuntimePropertiesService` exposes no listing, so nothing addresses
  // the key again. Reported as a failure of the teardown rather than as a line in the log.
  await expect(releaseChains(catalog, seeded, false)).rejects.toThrow(/orphans the key/);
  expect(calls.filter((each) => each === `deleteLoggingProperties:${CHAIN.id}`)).toHaveLength(2);
  // The chain is still undeployed: its route outlives the cascade otherwise, on a stack where the
  // delete is the only hop that removes one.
  expect(calls).toContain(`undeployAll:${CHAIN.id}`);
});

test("a spec's own chain whose key survived is not deleted with its service", { tag: ["@infra", "@tier1"] }, async () => {
  const calls: string[] = [];
  const removed: string[] = [];
  const built = {
    chains: [...seeded],
    services: [{ name: "the context service", remove: async () => { removed.push("service"); } }],
  };
  const catalog = stubCatalog({ failing: [`deleteLoggingProperties:${CHAIN.id}`] }, calls);

  // Deleting the chain would leave the key under an id nothing can list, whether or not the body failed.
  const said = await whileCollectingErrors(() => release(catalog, built, true));
  expect(said.join("\n")).toContain("this spec left state on the stack");
  await expect(release(catalog, built, false)).rejects.toThrow(/logging key/);
  expect(calls).not.toContain(`deleteChain:${CHAIN.id}`);
  expect(removed).toEqual(["service", "service"]);
});

// ---------------------------------------------------------------------------
// The residue the catalog cannot see
// ---------------------------------------------------------------------------

test("a chain that recorded sessions is residue, and the row names the chain the delete takes", { tag: ["@infra", "@tier1"] }, async () => {
  const found = await findResidue(
    stubCatalog({}),
    RUN,
    stubOutside({ sessions: [RECORDED, { id: "seed2", name: `e2e-${RUN}-split` }], mocks: [] }),
  );

  // Deleting the chain does not delete what it recorded, and the corpus deploys at
  // `sessionsLoggingLevel: "DEBUG"`, so a run leaves roughly a hundred documents behind. They are
  // reported one row per chain because that is the unit the delete takes, and the id is the chain's
  // rather than any document's.
  expect(found).toEqual([
    { kind: "sessions", id: "seed1", name: `e2e-${RUN}-http-echo` },
    { kind: "sessions", id: "seed2", name: `e2e-${RUN}-split` },
  ]);
});

test("a template this run named is residue, and a built-in one is not", { tag: ["@infra", "@tier1"] }, async () => {
  const found = await findResidue(
    stubCatalog({ templates: [TEMPLATE, BUILT_IN_TEMPLATE] }),
    RUN,
    stubOutside({ sessions: [], mocks: [] }),
  );

  expect(found).toEqual([{ kind: "design-template", id: "t1", name: `e2e-${RUN}-ghost` }]);
});

test("the sweep deletes the run's templates in one call and counts them gone", { tag: ["@infra", "@tier1"] }, async () => {
  const calls: string[] = [];
  const outcome = await sweepRunToken(
    stubCatalog({ templates: [TEMPLATE, BUILT_IN_TEMPLATE] }, calls),
    RUN,
    stubOutside({ sessions: [], mocks: [] }, calls),
  );

  // The id list names the run's template and nothing else: the delete is by explicit id, so a
  // built-in swept along with it would be a delete the endpoint answers 204 to and a row a later
  // run would then find missing.
  expect(calls).toContain("deleteDesignTemplates:t1");
  expect(outcome.removed).toEqual(outcome.residue);
  expect(sweptClean(outcome)).toBe(true);
});

test("a failed template delete is reported and keeps the run in the manifest", { tag: ["@infra", "@tier1"] }, async () => {
  const outcome = await sweepRunToken(
    stubCatalog({ templates: [TEMPLATE], failing: ["deleteDesignTemplates:t1"] }),
    RUN,
    stubOutside({ sessions: [], mocks: [] }),
  );

  expect(outcome.failures).toHaveLength(1);
  expect(outcome.failures[0]).toMatch(/^design-templates e2e-ab12cd-ghost: /);
  expect(outcome.removed).toEqual([]);
  expect(sweptClean(outcome)).toBe(false);
});

test("a mock this run named is residue", { tag: ["@infra", "@tier1"] }, async () => {
  const found = await findResidue(stubCatalog({}), RUN, stubOutside({ sessions: [] }));

  // The one row here that reddens a later run rather than merely occupying the stack: a mock is
  // keyed on ids a fixture freezes, so while it stands it answers in place of the real endpoint for
  // a sender every run addresses the same way.
  expect(found).toEqual([{ kind: "endpoint-mock", id: "m1", name: `e2e-${RUN}-mock` }]);
});

test("a caller with no outside client reads neither service", { tag: ["@infra", "@tier1"] }, async () => {
  // Two arguments, which is how this file calls it: `outside` is optional so a project declared as
  // needing no stack can drive the sweep, and a default of the live pair would put two HTTP calls
  // into a run with nothing to answer them. CI runs `--project=schema` where there is no Docker.
  //
  // Asserted with `fetch` refusing rather than over the rows alone. The two live readings answer an
  // empty list on a stack that holds nothing of this run, so a row count says nothing on a machine
  // where the stack happens to be up — and that is every machine this case would be edited on.
  const found = await whileRefusingFetch(() => findResidue(stubCatalog({ root: [FOLDER] }), RUN));

  expect(found.map((each) => each.kind)).toEqual(["folder"]);
});

test("the sweep deletes the sessions and the mock, and counts them gone", { tag: ["@infra", "@tier1"] }, async () => {
  const calls: string[] = [];
  const outcome = await sweepRunToken(stubCatalog({}, calls), RUN, stubOutside({}, calls));

  expect(calls).toContain("mocks.remove:m1");
  expect(calls).toContain("sessions.remove:seed1");
  expect(outcome.removed).toEqual(outcome.residue);
  expect(outcome.residue.map((each) => each.kind).sort()).toEqual(["endpoint-mock", "sessions"]);
  expect(sweptClean(outcome)).toBe(true);
});

test("a failed session delete is reported and keeps the run in the manifest", { tag: ["@infra", "@tier1"] }, async () => {
  const outcome = await sweepRunToken(
    stubCatalog({}),
    RUN,
    stubOutside({ mocks: [], failing: ["sessions.remove:seed1"] }),
  );

  // One call covers the whole set, so the rows go together or not at all: the failure names the
  // chains rather than a row, and none of them counts as removed.
  expect(outcome.failures).toHaveLength(1);
  expect(outcome.failures[0]).toMatch(/^sessions of e2e-ab12cd-http-echo: /);
  expect(outcome.removed).toEqual([]);
  expect(sweptClean(outcome)).toBe(false);
});

test("a failed mock delete is reported and keeps the run in the manifest", { tag: ["@infra", "@tier1"] }, async () => {
  const outcome = await sweepRunToken(
    stubCatalog({}),
    RUN,
    stubOutside({ sessions: [], failing: ["mocks.remove:m1"] }),
  );

  // The manifest entry is the handle the next run finds the mock by, and a mock nothing collects
  // fails a run that did nothing wrong.
  expect(outcome.failures).toHaveLength(1);
  expect(outcome.failures[0]).toMatch(/^endpoint-mock e2e-ab12cd-mock: /);
  expect(outcome.removed).toEqual([]);
  expect(sweptClean(outcome)).toBe(false);
});

test("the mock goes before every other delete", { tag: ["@infra", "@tier1"] }, async () => {
  const calls: string[] = [];
  const outcome = await sweepRunToken(
    stubCatalog({ root: [FOLDER], chains: [CHAIN] }, calls),
    RUN,
    stubOutside({}, calls),
  );

  // While it stands it answers for a sender addressed by ids no run varies, so it is the one row
  // whose survival reddens a later run. Nothing else in the sweep depends on it having gone, which
  // is what lets it go first — and going first is what a sweep cut short still gets through.
  const deletes = calls.filter((each) => each.startsWith("delete") || each.includes(".remove:"));
  expect(deletes[0]).toBe("mocks.remove:m1");
  expect(deletes).toContain("sessions.remove:seed1");
  expect(deletes).toContain(`deleteChain:${CHAIN.id}`);
  expect(sweptClean(outcome)).toBe(true);
});

/**
 * Runs `body` against a testing service that holds `held` mocks and serves `page` of them at a
 * time, and answers with what `body` produced and the URLs it called.
 *
 * The page size is the service's rather than the caller's, which is the whole reason the reading
 * has to loop: `effectiveLimit` in `internal/dao/pagination.go` clamps a request above the cap to
 * the cap, so no limit a client sends buys it the whole list. The answer carries neither a total
 * nor a next-page marker, so the stub ends the listing the way the service does — with an empty
 * page.
 */
async function whileServingMocks<T>(
  held: EndpointMock[],
  page: number,
  body: () => Promise<T>,
): Promise<{ result: T; urls: string[] }> {
  const urls: string[] = [];
  const original = globalThis.fetch;
  globalThis.fetch = ((input: unknown) => {
    const url = new URL(String(input));
    urls.push(url.toString());
    const offset = Number(url.searchParams.get("offset") ?? "0");
    const served = held.slice(offset, offset + page);
    return Promise.resolve({
      ok: true,
      status: 200,
      json: () => Promise.resolve(served),
      text: () => Promise.resolve(JSON.stringify(served)),
    } as unknown as Response);
  }) as typeof fetch;
  try {
    return { result: await body(), urls };
  } finally {
    globalThis.fetch = original;
  }
}

test("the mock listing is read to its end rather than to the end of its first page", { tag: ["@infra", "@tier1"] }, async () => {
  const held = [0, 1, 2].map((each) => ({ ...MOCK, id: `m${each}`, name: `e2e-${RUN}-mock-${each}` }));
  const { result, urls } = await whileServingMocks(held, 2, () =>
    mocksOfRun(RUN, "http://testing-service:8080"),
  );

  // A reading that stops at the first answer sees two of the three, deletes those, reports success,
  // and `forgetRun` then drops the manifest entry that is the only handle on the third — after
  // which the survivor answers for a sender every later run addresses by the same frozen ids.
  expect(result.map((each) => each.id)).toEqual(["m0", "m1", "m2"]);
  // The offsets advance by what a page held rather than by what the request asked for, so a
  // service serving fewer rows than the caller wanted is read on from where it stopped.
  expect(urls.map((each) => new URL(each).searchParams.get("offset"))).toEqual(["0", "2", "3"]);
  // Ordered, because a LIMIT/OFFSET window over an unordered query can repeat one row and skip
  // another, which loses a mock across two pages that both answered.
  expect(urls.map((each) => new URL(each).searchParams.get("sort_by"))).toEqual(["id", "id", "id"]);
});

/**
 * Runs `body` against a sessions-management that holds `held` sessions and serves at most `page` of
 * them per request, and answers with what `body` produced, the URLs it called, and the size of each
 * answer.
 *
 * The page size the caller asks for is a request parameter named `count`
 * (`SessionController.java:105`), and the stub reads it the way Spring does: a request that names
 * something else names nothing, and is served the declared default of 20. That is the whole of the
 * defect this models — the wrong name is not rejected, it is quietly answered with a smaller page.
 *
 * `page` caps the answer on top of that, because a stub that always served what it was asked for
 * could never end a listing, and `SessionSearchResponse` carries no total: its `offset` comes back
 * as `offset + rows` (`SessionService.java:220`), so the empty page is the ending, the same way it
 * is for the mock listing above.
 */
async function whileServingSessions<T>(
  held: Array<{ chainId: string; chainName: string }>,
  page: number,
  body: () => Promise<T>,
): Promise<{ result: T; urls: string[]; served: number[] }> {
  const urls: string[] = [];
  const served: number[] = [];
  const original = globalThis.fetch;
  globalThis.fetch = ((input: unknown) => {
    const url = new URL(String(input));
    urls.push(url.toString());
    const offset = Number(url.searchParams.get("offset") ?? "0");
    const count = Number(url.searchParams.get("count") ?? "20");
    const sessions = held.slice(offset, offset + Math.min(count, page));
    served.push(sessions.length);
    const answer = { offset: offset + sessions.length, sessions };
    return Promise.resolve({
      ok: true,
      status: 200,
      json: () => Promise.resolve(answer),
      text: () => Promise.resolve(JSON.stringify(answer)),
    } as unknown as Response);
  }) as typeof fetch;
  try {
    return { result: await body(), urls, served };
  } finally {
    globalThis.fetch = original;
  }
}

/** Sessions of `count` chains this run drove, one session each, in the shape the index answers. */
function recorded(count: number): Array<{ chainId: string; chainName: string }> {
  return Array.from({ length: count }, (_, each) => ({
    chainId: `seed${each}`,
    chainName: `e2e-${RUN}-chain-${each}`,
  }));
}

test("the session search is read to its end rather than to the end of one page", { tag: ["@infra", "@tier1"] }, async () => {
  const { result, urls } = await whileServingSessions(recorded(3), 2, () =>
    sessionsOfRun(RUN, "http://sessions-management:8080"),
  );

  // A run records on the order of a hundred sessions through fifteen or so chains, so a reading
  // that stops at one answer reports part of the set as the whole of it. The sweep then deletes
  // the chains it saw, reports success, and `forgetRun` drops the manifest entry that is the only
  // handle left on the sessions nobody read.
  expect(result.map((each) => each.id)).toEqual(["seed0", "seed1", "seed2"]);
  // The offsets advance by what the answer held rather than by what the request asked for, so a
  // service that served fewer rows than the caller wanted is read on from where it stopped.
  expect(urls.map((each) => new URL(each).searchParams.get("offset"))).toEqual(["0", "2", "3"]);
});

test("the session search names the page parameter the service reads", { tag: ["@infra", "@tier1"] }, async () => {
  const { result, urls, served } = await whileServingSessions(recorded(25), 1000, () =>
    sessionsOfRun(RUN, "http://sessions-management:8080"),
  );

  expect(result).toHaveLength(25);
  // Served in one page, which is the observable difference. Under any other name the parameter is
  // dropped and the default 20 answers, and the reading pays for pages it asked not to have.
  expect(served[0]).toBe(25);
  // Over the set of calls rather than a fixed number of them, so that this case answers for the
  // name alone and the case above answers for the paging.
  expect(new Set(urls.map((each) => new URL(each).searchParams.get("count")))).toEqual(
    new Set(["1000"]),
  );
  expect(urls.some((each) => new URL(each).searchParams.has("limit"))).toBe(false);
});

/**
 * The specs that write a chain's logging key, and how many key deletes of their own each may hold.
 *
 * Read as text rather than imported: all four pull `support/fixtures.js`, whose `auto` fixtures
 * create a folder over HTTP and shell out to `docker logs`, and rule 14 keeps that out of this
 * directory.
 *
 * `ownDeletes` is a budget rather than a ban, and `specs/api/masking.spec.ts` is why. Its first
 * case writes the key of a chain in the worker folder and releases it the shared way; its second
 * writes one under a chain id that answers to nothing, which is the defect that case pins. A key
 * with no chain has nothing `releaseChains` can do for it — `POST /v1/chains/{ghost}/move` answers
 * 404, so the move could only fail, and the report it produced would name a folder cascade that is
 * not coming. So that one delete stands, and a second one does not: a budget still turns red for a
 * spec that quietly goes back to deleting the key of a chain that exists.
 */
const OWN_CHAINS: Array<{ spec: string; ownDeletes: number }> = [
  { spec: "specs/runtime/metrics.spec.ts", ownDeletes: 0 },
  { spec: "specs/runtime/logging-level.spec.ts", ownDeletes: 0 },
  { spec: "specs/api/creation-path-equivalence.spec.ts", ownDeletes: 0 },
  { spec: "specs/api/masking.spec.ts", ownDeletes: 1 },
];

test("the specs that hold a chain's logging key share one policy", { tag: ["@infra", "@tier1"] }, () => {
  for (const { spec, ownDeletes } of OWN_CHAINS) {
    const source = fs.readFileSync(path.resolve(repoRoot(), "e2e", spec), "utf-8");
    expect(source, `${spec} releases its chains its own way`).toContain("releaseChains(catalog");
    // A key delete of the spec's own is the second policy, and half a policy is a leak: the delete,
    // the move out of the worker folder and the last attempt are one decision. The `folder` fixture
    // cascades the worker folder away after the worker's last test, so a chain kept where it stands
    // is a chain the cascade takes — and its key is then filed under an id nothing can list.
    const own = source.match(/deleteLoggingProperties\(/g)?.length ?? 0;
    expect(own, `${spec} deletes a logging key of its own`).toBe(ownDeletes);
  }
});

test("a micro domain named after the run is deleted, and no other domain is", { tag: ["@infra", "@tier1"] }, async () => {
  const calls: string[] = [];
  const other = { ...MICRO_DOMAIN, id: "e2e-zz99zz-micro", name: "e2e-zz99zz-micro" };
  const outcome = await sweepRunToken(stubCatalog({ domains: [DEFAULT, MICRO_DOMAIN, other] }, calls), RUN);

  expect(outcome.residue).toEqual([{ kind: "micro-domain", id: MICRO_DOMAIN.name, name: MICRO_DOMAIN.name }]);
  expect(sweptClean(outcome)).toBe(true);
  expect(calls.filter((each) => each.startsWith("deleteCustomResource"))).toEqual([
    `deleteCustomResource:${MICRO_DOMAIN.name}`,
  ]);
});

test("a micro domain whose delete failed keeps the run in the manifest", { tag: ["@infra", "@tier1"] }, async () => {
  const outcome = await sweepRunToken(
    stubCatalog({ domains: [MICRO_DOMAIN], failing: [`deleteCustomResource:${MICRO_DOMAIN.name}`] }),
    RUN,
  );
  expect(outcome.removed).toEqual([]);
  expect(outcome.failures).toHaveLength(1);
  expect(sweptClean(outcome)).toBe(false);
});
