/**
 * The roles an HTTP trigger accepts, the search that finds them, and the redeploy that applies them.
 *
 * Covers `chain-roles-controller` whole — three operations, and **six** registry rows, because the
 * controller is mapped at two paths: `@RequestMapping(value = {"/v1/catalog/chains/roles",
 * "/v1/catalog/chains/access-control"})`. The search case drives both and asserts the two answers
 * are equal, which is what makes the pairing asserted rather than assumed; the refusal, update and
 * redeploy cases each pick one path, and between them the file reaches all six rows.
 *
 * The spec is in `api` rather than in `global` on purpose: the search
 * spans the catalog but its predicate is this spec's own chain name, so no parallel worker can flip
 * an answer. The **redeploy** is the part with reach — `ChainRolesService.redeploy` builds a
 * snapshot and creates a deployment for a chain that had none — but everything it touches is a chain
 * this spec created, so the blast radius is the spec's own. The redeploy case undeploys what it
 * produced rather than leaving a route on the engine for the rest of the run.
 *
 * "Visible on the chain" means the `roles` and `accessControlType` properties read back off the
 * element. It does **not** mean access is enforced: the engine serves these routes `permitAll`, so
 * a role written here changes what the catalog stores and nothing about who may call the chain.
 * Nothing in this file calls a deployed route to check otherwise, and that is deliberate.
 *
 * Seven shapes measured rather than assumed:
 *
 * - **`accessControlType` is rewritten by the roles, not sent with them.** The update body carries
 *   `elementId` and `roles` and nothing else. `NONE` becomes `RBAC` the moment the roles are
 *   non-empty, and `RBAC` becomes `NONE` when they are emptied.
 * - **Both writes answer 204 with an empty body.** They used to rebuild and return a search page
 *   from a field shared between callers; PR #754 removed it, and the empty body is now the
 *   contract worth pinning.
 * - **A `PUT` naming an element nothing answers to is a 404**, and the batch is all or nothing:
 *   `resolveUpdates` resolves every element before applying any, so one bad id leaves the good
 *   ones untouched. It used to swallow every exception but the ABAC one into `log.error` and
 *   answer 200.
 * - **An unsupported (column, condition) pair is refused with a 400** naming the conditions the
 *   column does take. This is a regression test: the pair used to yield a null predicate that
 *   `buildFilterQuery` dropped with `.filter(Objects::nonNull)`, so `CHAIN IS` silently returned
 *   **every** chain. Fixed in PR #819 (issue #808); `docs/product-defects.md` carries the withdrawn
 *   entry.
 * - **`CHAIN` compares against `chain.name` and never the chain id**, so a search by id finds
 *   nothing however well-formed the id is.
 * - **The row's `properties` is a projection, not the element's map.**
 *   `ElementFilterRepositoryImpl.filterElementProperties` keeps eight keys and drops the rest.
 * - **`redeploy` deploys a chain that was never deployed**, and a second call **adds a deployment
 *   row rather than moving the one that exists**. It does both: `deployments.get(0).setSnapshot(…)`
 *   repoints the existing row and `deploymentService.createAll` then saves a new one, so the chain
 *   carries two rows on one snapshot until `RuntimeDeploymentService` prunes the superseded one
 *   against what the engine reports. Measured at about five seconds on this stack, which is why the
 *   case polls for the settled state instead of counting rows straight after the call.
 */
import { test, expect } from "../../support/fixtures.js";
import {
  CHAIN_ROLES_PATHS,
  type Catalog,
  type ChainElement,
  type ChainRolesPage,
  type ChainRolesPath,
} from "../../support/catalog.js";
import { tokenized } from "../../support/run.js";
import { ABSENT_UUID } from "../../support/absent.js";

/** A chain with one HTTP trigger on a route named after it, inside the worker folder. */
async function chainWithTrigger(
  catalog: Catalog,
  run: string,
  folderId: string,
  what: string,
): Promise<{ id: string; name: string; trigger: ChainElement }> {
  const name = tokenized(run, `chain-roles-${what}`);
  const chain = await catalog.createChain(name, folderId);
  const trigger = await catalog.createElement(chain.id, "http-trigger");
  // The context path is what makes the chain deployable and what the `endpoint` column filters on.
  // `externalRoute: false` keeps it off the gateway, where a duplicate path is a different refusal.
  await catalog.patchElementProperties(chain.id, trigger.id, {
    contextPath: name,
    httpMethodRestrict: "POST",
    externalRoute: false,
  });
  return { id: chain.id, name, trigger };
}

/** The chain's name is the search predicate, and it is this spec's alone. */
function byChainName(name: string) {
  return { filters: [{ column: "chain", condition: "CONTAINS", value: name }] };
}

/** The element's stored properties, which is where "visible on the chain" is read. */
async function propertiesOf(
  catalog: Catalog,
  chainId: string,
  elementId: string,
): Promise<Record<string, unknown>> {
  return (await catalog.getElement(chainId, elementId)).properties;
}

/** The same search on both mappings, asserted equal, so a case covers six rows and not three. */
async function searchOnBothPaths(
  catalog: Catalog,
  name: string,
): Promise<ChainRolesPage> {
  const [roles, accessControl] = await Promise.all(
    CHAIN_ROLES_PATHS.map((path) => catalog.searchChainRoles(byChainName(name), { path })),
  );
  expect(accessControl, "the two mappings are one controller, not two surfaces").toEqual(roles);
  return roles;
}

test("the search finds the chain's http trigger on either of the controller's two paths", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await chainWithTrigger(catalog, run, folder.id, "search");
  // Before the first search, not after it. A baseline captured between two searches cannot see the
  // one that ran first, and the re-read at the end of this case is meant to acquit all four.
  const stored = await propertiesOf(catalog, chain.id, chain.trigger.id);

  const page = await searchOnBothPaths(catalog, chain.name);
  expect(page.roles).toHaveLength(1);
  const row = page.roles[0];
  expect(row).toMatchObject({
    chainId: chain.id,
    chainName: chain.name,
    elementId: chain.trigger.id,
    elementName: "HTTP Trigger",
    // Never deployed, so the runtime view keys nothing and the service substitutes a single DRAFT.
    deploymentStatus: ["DRAFT"],
    unsavedChanges: true,
  });
  // `offset` is a cursor — the request's offset plus the rows returned — and not a total.
  expect(page.offset).toBe(1);
  expect(typeof row.modifiedWhen).toBe("number");

  // The row's properties are a projection of the eight keys `PROPERTIES_FILTER` names
  // (`ElementFilterRepositoryImpl.java:50`), of which this trigger sets four. The element carries a
  // dozen more, and a caller reading `connectTimeout` off a row reads `undefined`.
  expect(Object.keys(row.properties).sort()).toEqual([
    "accessControlType",
    "contextPath",
    "externalRoute",
    "privateRoute",
  ]);
  expect(row.properties).toMatchObject({ contextPath: chain.name, accessControlType: "NONE" });
  expect(stored.connectTimeout, "the element has it").toBe(120000);
  expect(row.properties.connectTimeout, "and the search row does not").toBeUndefined();

  // The endpoint column reaches the same row by the route rather than by the chain, which is what
  // makes the two-column scoping below meaningful.
  const byEndpoint = await catalog.searchChainRoles({
    filters: [{ column: "endpoint", condition: "IS", value: chain.name }],
  });
  expect(byEndpoint.roles.map((each) => each.elementId)).toEqual([chain.trigger.id]);

  // `isImplementedOnly` keeps only triggers pointed at a specification group; this one is not.
  const implemented = await catalog.searchChainRoles(byChainName(chain.name), {
    implementedOnly: true,
  });
  expect(implemented.roles).toEqual([]);

  // The projection is a read, not a write: after four searches on three predicates the element
  // still carries every property the rows dropped.
  expect(await propertiesOf(catalog, chain.id, chain.trigger.id)).toEqual(stored);
});

test("the search compares chain names and refuses a condition the column cannot apply", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await chainWithTrigger(catalog, run, folder.id, "filters");
  expect((await catalog.searchChainRoles(byChainName(chain.name))).roles).toHaveLength(1);

  // The column is called "Chain" and the value is compared against `chain.name`. A well-formed
  // chain id matches nothing, which is what makes the name assertion above a name assertion.
  const byId = await catalog.searchChainRoles({
    filters: [{ column: "chain", condition: "CONTAINS", value: chain.id }],
  });
  expect(byId.roles).toEqual([]);
  expect(byId.offset).toBe(0);

  // A regression test rather than a shape. `CHAIN` takes four conditions and `IS` is not one of
  // them; the pair used to be dropped from the query, which returned the whole table under a filter
  // the caller believed was scoping it. The message names the conditions the column does take, in
  // the enum's declaration order.
  const unsupported = await catalog.raw("post", CHAIN_ROLES_PATHS[0], {
    filters: [{ column: "chain", condition: "IS", value: chain.name }],
  });
  expect(unsupported.status()).toBe(400);
  expect(await unsupported.json()).toMatchObject({
    serviceName: "Catalog",
    errorMessage:
      "Filter condition IS is not supported for column CHAIN. " +
      "Supported conditions: [CONTAINS, DOES_NOT_CONTAIN, STARTS_WITH, ENDS_WITH]",
  });
  // On the other mapping too, so the refusal is the controller's rather than one path's.
  expect(
    (
      await catalog.raw("post", CHAIN_ROLES_PATHS[1], {
        filters: [{ column: "chain", condition: "IS", value: chain.name }],
      })
    ).status(),
  ).toBe(400);

  // The same guard rejects a clause missing either half, naming which half.
  const noColumn = await catalog.raw("post", CHAIN_ROLES_PATHS[0], {
    filters: [{ condition: "CONTAINS", value: chain.name }],
  });
  expect(noColumn.status()).toBe(400);
  expect((await noColumn.json()).errorMessage).toBe("Filter column is required");

  const noCondition = await catalog.raw("post", CHAIN_ROLES_PATHS[0], {
    filters: [{ column: "chain", value: chain.name }],
  });
  expect(noCondition.status()).toBe(400);
  expect((await noCondition.json()).errorMessage).toBe(
    "Filter condition is required for column CHAIN",
  );

  // A window the query cannot express is an empty page rather than a refusal, and the guard above
  // is what stops that shape from being how a bad filter behaves too.
  expect(await catalog.searchChainRoles({ ...byChainName(chain.name), limit: 0 })).toEqual({
    offset: 0,
    roles: [],
  });
  expect(await catalog.searchChainRoles({ ...byChainName(chain.name), offset: -1 })).toEqual({
    offset: 0,
    roles: [],
  });
});

test("a roles update answers 204 and moves the access control type with it", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await chainWithTrigger(catalog, run, folder.id, "update");
  expect((await propertiesOf(catalog, chain.id, chain.trigger.id)).accessControlType).toBe("NONE");

  // 204 with an empty body. The endpoint used to answer a search page rebuilt from a field shared
  // between callers, so a caller reading the body read some other caller's most recent search.
  const applied = await catalog.raw("put", CHAIN_ROLES_PATHS[0], [
    { elementId: chain.trigger.id, roles: ["e2e-reader", "e2e-writer"] },
  ]);
  expect(applied.status()).toBe(204);
  expect(await applied.text()).toBe("");

  // Visible on the chain: read back off the element, not off the response.
  const withRoles = await propertiesOf(catalog, chain.id, chain.trigger.id);
  expect(withRoles.roles).toEqual(["e2e-reader", "e2e-writer"]);
  // The request never named it: `NONE` plus a non-empty role set is rewritten to `RBAC`.
  expect(withRoles.accessControlType).toBe("RBAC");
  // And the properties the update did not name survive it.
  expect(withRoles).toMatchObject({ contextPath: chain.name, httpMethodRestrict: "POST" });

  // The search reports the same change, which is the view the access-control screen reads.
  const page = await searchOnBothPaths(catalog, chain.name);
  expect(page.roles[0].properties).toMatchObject({
    accessControlType: "RBAC",
    roles: ["e2e-reader", "e2e-writer"],
  });

  // Emptying the roles on the other mapping walks it back the same way, and that direction is not
  // symmetric bookkeeping: `RBAC` with no roles would be a trigger nobody can call.
  const cleared = await catalog.raw("put", CHAIN_ROLES_PATHS[1], [
    { elementId: chain.trigger.id, roles: [] },
  ]);
  expect(cleared.status()).toBe(204);
  const withoutRoles = await propertiesOf(catalog, chain.id, chain.trigger.id);
  expect(withoutRoles.roles).toEqual([]);
  expect(withoutRoles.accessControlType).toBe("NONE");
});

test("a roles batch is refused whole, and an ABAC endpoint refuses it at all", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await chainWithTrigger(catalog, run, folder.id, "refusals");
  const other = await chainWithTrigger(catalog, run, folder.id, "refusals-two");
  await catalog.updateChainRoles([{ elementId: chain.trigger.id, roles: ["keep-me"] }]);

  // An element nothing answers to is a 404 rather than the 200 this endpoint used to give, and the
  // element named beside it keeps the roles it had: `resolveUpdates` resolves the whole batch
  // before applying any of it.
  const missing = await catalog.raw("put", CHAIN_ROLES_PATHS[0], [
    { elementId: other.trigger.id, roles: ["applied-if-partial"] },
    { elementId: ABSENT_UUID, roles: ["nothing"] },
  ]);
  expect(missing.status()).toBe(404);
  expect(await missing.json()).toMatchObject({
    errorMessage: `Can't find chain element with id: ${ABSENT_UUID}`,
  });
  const untouched = await propertiesOf(catalog, other.id, other.trigger.id);
  expect(untouched.roles, "the good half of a refused batch is not applied").toBeUndefined();
  expect(untouched.accessControlType).toBe("NONE");
  expect((await propertiesOf(catalog, chain.id, chain.trigger.id)).roles).toEqual(["keep-me"]);

  // `roles` is `@NotNull` on the request, so omitting it is refused by validation rather than read
  // as an empty set — which would have cleared the roles instead.
  const noRoles = await catalog.raw("put", CHAIN_ROLES_PATHS[0], [{ elementId: chain.trigger.id }]);
  expect(noRoles.status()).toBe(400);
  expect((await noRoles.json()).errorMessage).toContain("roles must not be null");
  expect((await propertiesOf(catalog, chain.id, chain.trigger.id)).roles).toEqual(["keep-me"]);

  // ABAC is the one access control type roles cannot be written under: the attributes decide, and
  // a role list would be a second, silent policy. The refusal is by element id, not by batch.
  await catalog.patchElementProperties(other.id, other.trigger.id, { accessControlType: "ABAC" });
  const abac = await catalog.raw("put", CHAIN_ROLES_PATHS[1], [
    { elementId: other.trigger.id, roles: ["abac-attempt"] },
  ]);
  expect(abac.status()).toBe(400);
  expect(await abac.json()).toMatchObject({
    errorMessage: `Can't apply roles to ABAC endpoint with id: ${other.trigger.id}`,
  });
  const afterAbac = await propertiesOf(catalog, other.id, other.trigger.id);
  expect(afterAbac.roles, "nothing was written").toBeUndefined();
  expect(afterAbac.accessControlType).toBe("ABAC");
});

test("the redeploy builds a snapshot and deploys a chain that had no deployment", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await chainWithTrigger(catalog, run, folder.id, "redeploy");
  const path: ChainRolesPath = CHAIN_ROLES_PATHS[0];
  try {
    await catalog.updateChainRoles([{ elementId: chain.trigger.id, roles: ["e2e-redeploy"] }], path);
    expect(await catalog.listSnapshots(chain.id)).toEqual([]);
    expect(await catalog.listDeployments(chain.id)).toEqual([]);
    expect((await catalog.getChain(chain.id)).unsavedChanges).toBe(true);

    // 204 with an empty body, like the roles update. What it did is asserted below, not here.
    const first = await catalog.raw("put", `${path}/redeploy`, [chain.id]);
    expect(first.status()).toBe(204);
    expect(await first.text()).toBe("");

    // A snapshot was built and made current, and a deployment exists for a chain that had none —
    // this endpoint deploys as well as redeploys, which is its whole blast radius.
    expect((await catalog.listSnapshots(chain.id)).map((each) => each.name)).toEqual(["V1"]);
    const deployments = await catalog.listDeployments(chain.id);
    expect(deployments).toHaveLength(1);
    expect(deployments[0].name).toBe("V1");
    const afterFirst = await catalog.getChain(chain.id);
    expect(afterFirst.unsavedChanges, "the redeploy is what saves the chain").toBe(false);
    expect(afterFirst.currentSnapshot?.name).toBe("V1");

    // The second call, on the **other** mapping. It builds a further snapshot, repoints the
    // existing deployment at it — `deployments.get(0).setSnapshot(snapshot)` — and then saves a
    // *new* deployment row for it as well, so the chain briefly carries two rows already sharing
    // one snapshot. The superseded row is pruned asynchronously, once the engine reports which
    // deployments it actually holds and `RuntimeDeploymentService` calls
    // `deleteObsoleteDeployments`. Measured on this stack: two rows for about five seconds, then
    // one. So the assertion is on the settled state and on **which** row survived, never on a count
    // read straight after the call.
    const second = await catalog.raw("put", `${CHAIN_ROLES_PATHS[1]}/redeploy`, [chain.id]);
    expect(second.status()).toBe(204);
    const snapshots = await catalog.listSnapshots(chain.id);
    expect(snapshots.map((each) => each.name).sort()).toEqual(["V1", "V2"]);
    const rebuilt = snapshots.find((each) => each.name === "V2")!;

    await expect
      .poll(async () => (await catalog.listDeployments(chain.id)).map((each) => each.name))
      .toEqual(["V2"]);
    const settled = (await catalog.listDeployments(chain.id))[0];
    expect(settled.snapshotId, "the surviving deployment is on the snapshot just built").toBe(
      rebuilt.id,
    );
    expect(settled.id, "and it is the new row, not the one the first redeploy created").not.toBe(
      deployments[0].id,
    );

    // And the search now reports the chain as deployed rather than DRAFT, which is the column the
    // access-control screen filters on.
    await expect
      .poll(async () => (await catalog.searchChainRoles(byChainName(chain.name))).roles[0]?.deploymentStatus)
      .toEqual(["DEPLOYED"]);
    const deployedOnly = await catalog.searchChainRoles({
      filters: [
        { column: "chain", condition: "CONTAINS", value: chain.name },
        { column: "chain_status", condition: "IS", value: "deployed" },
      ],
    });
    expect(deployedOnly.roles.map((each) => each.chainId)).toEqual([chain.id]);
    // The same clause with the other status excludes it, so the filter filters.
    const draftOnly = await catalog.searchChainRoles({
      filters: [
        { column: "chain", condition: "CONTAINS", value: chain.name },
        { column: "chain_status", condition: "IS", value: "draft" },
      ],
    });
    expect(draftOnly.roles).toEqual([]);

    // A chain id nothing answers to is refused before anything is deployed, and an empty list is
    // accepted rather than treated as "every chain".
    const unknown = await catalog.raw("put", `${path}/redeploy`, [ABSENT_UUID]);
    expect(unknown.status()).toBe(404);
    expect(await unknown.json()).toMatchObject({
      errorMessage: `Can't find chain with id: ${ABSENT_UUID}`,
    });
    expect((await catalog.raw("put", `${path}/redeploy`, [])).status()).toBe(204);
  } finally {
    // The cascade would reach it — `FolderService.deleteById:245` calls `deleteRuntimeDeployments`
    // down the folder tree before it deletes anything — so this is the suite's convention rather
    // than a gap: a case undeploys what it deployed, and the route goes when the case ends rather
    // than when the worker does.
    await catalog.undeployAll(chain.id).catch(() => {});
  }
});
