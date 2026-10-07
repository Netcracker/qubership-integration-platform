/**
 * Services and the three entity families that sit beside them: environments, context services and
 * MCP services.
 *
 * None of these hangs off a folder, so every case names its entities with the run token and
 * deletes them itself; the run-token sweep is only the backstop for a case that dies halfway. That
 * is also why the assertions are filtered to ids the case created — `GET /v1/systems`
 * is one global list on a shared stack, and its length belongs to nobody.
 *
 * Ten shapes are pinned because each one is measured rather than obvious, and each one bites:
 *
 * - `PUT /v1/systems/{id}` **replaces**. `SystemMapper.mergeWithoutLabels` carries no
 *   null-ignoring strategy, so a request that omits `description` clears it and one that omits
 *   `activeEnvironmentId` deactivates the environment. `PATCH` on the same path merges.
 * - `activeEnvironmentId` is **stored** for `EXTERNAL` and **derived** for `INTERNAL` and
 *   `IMPLEMENTED`, where `SystemMapper.getActiveEnvironmentId` answers the first environment
 *   whatever the row holds.
 * - The first environment of an `EXTERNAL` service is activated on creation; the second is not.
 * - An `INTERNAL` service refuses a second environment, and any service refuses two environments
 *   carrying one label.
 * - `PUT /v1/catalog/context-system/{id}` **merges**, which is the opposite of the service `PUT`
 *   on the line above.
 * - `DELETE /v1/catalog/mcp-system/{id}` is idempotent where the context delete answers 404 the
 *   second time.
 * - The two families disagree about the **filter envelope**: the context filter takes a bare list
 *   of clauses and the MCP one wraps them in `{searchString, filters}`, though both deserialize the
 *   same `FilterRequestDTO`.
 * - `POST /v1/systems/search` matches an exact id or name, or a name or description containing the
 *   condition, ignoring case. `POST /v1/systems/filter` on `NAME` reads the name alone.
 * - `GET /v1/systems/usage` refuses a request without `type` with **400**, and resolves the service
 *   name from the id an element carries whether or not a specification stands behind it.
 * - The service and MCP previews answer `UPDATE` for an id the catalog holds and `CREATE` for one it
 *   does not, and write nothing either way.
 */
import { test, expect } from "../../support/fixtures.js";
import { tokenized } from "../../support/run.js";
import { archiveOf, entryNames } from "../../support/zip.js";

test("a service round-trips through create, read, update, and delete for each of the three types", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  for (const type of ["EXTERNAL", "INTERNAL", "IMPLEMENTED"]) {
    const name = tokenized(run, `svc-${type.toLowerCase()}`);
    const created = await catalog.createSystem(name, type, "the original");
    try {
      expect(created.type).toBe(type);

      const read = await catalog.getSystem(created.id);
      expect(read).toMatchObject({ id: created.id, name, type, description: "the original" });

      const renamed = `${name}-renamed`;
      const updated = await catalog.updateSystem(created.id, {
        name: renamed,
        type,
        description: "rewritten",
      });
      expect(updated.id).toBe(created.id);
      expect(await catalog.getSystem(created.id)).toMatchObject({
        name: renamed,
        description: "rewritten",
      });

      // Filtered to this case's own row: the list is the whole stack's.
      const listed = (await catalog.listSystems()).filter((each) => each.id === created.id);
      expect(listed).toHaveLength(1);
      expect(listed[0].name).toBe(renamed);

      expect((await catalog.raw("delete", `/v1/systems/${created.id}`)).status()).toBe(200);
      expect((await catalog.raw("get", `/v1/systems/${created.id}`)).status()).toBe(404);
    } finally {
      await catalog.deleteSystem(created.id).catch(() => {});
    }
  }
});

test("PUT replaces the service's fields where PATCH merges them", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  const name = tokenized(run, "svc-merge");
  const created = await catalog.createSystem(name, "EXTERNAL", "the original");
  try {
    // The whole point of the distinction: a caller renaming a service through PUT and sending no
    // description silently clears one it never meant to touch.
    const replaced = await catalog.updateSystem(created.id, { name, type: "EXTERNAL" });
    // Absent rather than null: the field is cleared and the serializer then drops the key, so an
    // assertion written as `toBeNull()` fails on `undefined` while the behavior is what it claims.
    expect(replaced.description, "PUT sends the whole state, so an omitted key is cleared").toBeUndefined();
    expect((await catalog.getSystem(created.id)).description).toBeUndefined();

    const patched = await catalog.patchSystem(created.id, { description: "patched" });
    expect(patched).toMatchObject({ name, description: "patched" });

    const renamed = await catalog.patchSystem(created.id, { name: `${name}-patched` });
    expect(renamed, "PATCH keeps every key the body omits").toMatchObject({
      name: `${name}-patched`,
      description: "patched",
    });
  } finally {
    await catalog.deleteSystem(created.id).catch(() => {});
  }
});

test("an environment round-trips, and the first one of an external service is activated by itself", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  const service = await catalog.createSystem(tokenized(run, "svc-env"), "EXTERNAL");
  try {
    expect(await catalog.listEnvironments(service.id)).toEqual([]);

    const first = await catalog.createEnvironment(service.id, {
      name: tokenized(run, "env-first"),
      address: "http://first.example",
      labels: ["QA"],
    });
    expect(first.systemId).toBe(service.id);
    // Read from the service rather than from the create response: activation is a field on the
    // service, written as a side effect of the create, and the environment body cannot show it.
    expect(
      (await catalog.getSystem(service.id)).activeEnvironmentId,
      "the first environment with an address activates itself on an external service",
    ).toBe(first.id);

    const second = await catalog.createEnvironment(service.id, {
      name: tokenized(run, "env-second"),
      address: "http://second.example",
      labels: ["PRODUCTION"],
    });
    expect(
      (await catalog.getSystem(service.id)).activeEnvironmentId,
      "and the second does not take the activation from it",
    ).toBe(first.id);

    const read = await catalog.getEnvironment(service.id, first.id);
    expect(read).toMatchObject({ id: first.id, address: "http://first.example", labels: ["QA"] });

    const updated = await catalog.updateEnvironment(service.id, first.id, {
      name: tokenized(run, "env-first-renamed"),
      address: "http://first-moved.example",
      labels: ["QA"],
    });
    expect(updated.id, "an update is an update, not a replacement under a new id").toBe(first.id);
    expect(await catalog.getEnvironment(service.id, first.id)).toMatchObject({
      name: tokenized(run, "env-first-renamed"),
      address: "http://first-moved.example",
    });

    expect((await catalog.raw("delete", `/v1/systems/${service.id}/environments/${first.id}`)).status()).toBe(200);
    expect((await catalog.raw("get", `/v1/systems/${service.id}/environments/${first.id}`)).status()).toBe(404);
    expect((await catalog.raw("delete", `/v1/systems/${service.id}/environments/${first.id}`)).status()).toBe(404);
    expect((await catalog.listEnvironments(service.id)).map((each) => each.id)).toEqual([second.id]);
  } finally {
    await catalog.deleteSystem(service.id).catch(() => {});
  }
});

test("activating an environment is a write to the service, and it takes the rest of the state with it", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  const name = tokenized(run, "svc-activate");
  const service = await catalog.createSystem(name, "EXTERNAL", "keep me");
  try {
    const first = await catalog.createEnvironment(service.id, {
      name: tokenized(run, "act-first"),
      address: "http://first.example",
      labels: ["QA"],
    });
    const second = await catalog.createEnvironment(service.id, {
      name: tokenized(run, "act-second"),
      address: "http://second.example",
      labels: ["PRODUCTION"],
    });
    expect((await catalog.getSystem(service.id)).activeEnvironmentId).toBe(first.id);

    const activated = await catalog.activateEnvironment(service.id, second.id);
    expect(activated.activeEnvironmentId).toBe(second.id);
    // There is no activation endpoint: it rides the wholesale PUT, so the helper has to carry the
    // name and the description through or activating an environment renames the service to null.
    expect(activated, "activation must not clear the fields it did not mean to touch").toMatchObject({
      name,
      description: "keep me",
    });
    expect(await catalog.getSystem(service.id)).toMatchObject({
      activeEnvironmentId: second.id,
      description: "keep me",
    });
  } finally {
    await catalog.deleteSystem(service.id).catch(() => {});
  }
});

test("an internal service derives its active environment and refuses a second one", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  // Not `svc-internal`: the round-trip case above builds that exact name for `type = "INTERNAL"`,
  // and under `fullyParallel` the two live at the same moment. Nothing breaks — every assertion in
  // both is filtered by id — but the sweep and the failure messages then name two services.
  const service = await catalog.createSystem(tokenized(run, "svc-internal-only"), "INTERNAL");
  try {
    // No address, so nothing would activate it on the external path — and the internal service
    // still reports it active, because the mapper derives the value rather than reading the row.
    const only = await catalog.createEnvironment(service.id, {
      name: tokenized(run, "internal-env"),
    });
    expect((await catalog.getSystem(service.id)).activeEnvironmentId).toBe(only.id);

    const refused = await catalog.raw("post", `/v1/systems/${service.id}/environments`, {
      name: tokenized(run, "internal-env-2"),
      address: "http://second.example",
    });
    expect(refused.status()).toBe(400);
    expect(await refused.text()).toContain("Can't put more than one environment to 'internal' system");
    expect((await catalog.listEnvironments(service.id)).map((each) => each.id)).toEqual([only.id]);
  } finally {
    await catalog.deleteSystem(service.id).catch(() => {});
  }
});

test("two environments of one service cannot carry the same label", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  const service = await catalog.createSystem(tokenized(run, "svc-label"), "EXTERNAL");
  try {
    await catalog.createEnvironment(service.id, {
      name: tokenized(run, "label-first"),
      address: "http://first.example",
      labels: ["STAGING"],
    });

    const refused = await catalog.raw("post", `/v1/systems/${service.id}/environments`, {
      name: tokenized(run, "label-second"),
      address: "http://second.example",
      labels: ["STAGING"],
    });
    expect(refused.status()).toBe(400);
    expect(await refused.text()).toContain("Label should be unique within single system: STAGING");
    // And the refusal created nothing, which a status assertion alone would not show.
    expect(await catalog.listEnvironments(service.id)).toHaveLength(1);
  } finally {
    await catalog.deleteSystem(service.id).catch(() => {});
  }
});

test("a context service round-trips, and its PUT merges where the service PUT replaces", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  const name = tokenized(run, "ctx");
  const created = await catalog.createContextSystem(name, "the original");
  try {
    expect(await catalog.getContextSystem(created.id)).toMatchObject({
      id: created.id,
      name,
      description: "the original",
    });

    const renamed = await catalog.updateContextSystem(created.id, { name: `${name}-renamed` });
    expect(
      renamed.description,
      "the context PUT keeps a key the body omits, unlike the service PUT",
    ).toBe("the original");

    const patched = await catalog.patchContextSystem(created.id, { description: "patched" });
    expect(patched).toMatchObject({ name: `${name}-renamed`, description: "patched" });

    const found = await catalog.searchContextSystems(name);
    expect(found.map((each) => each.id)).toContain(created.id);
    expect((await catalog.listContextSystems()).filter((each) => each.id === created.id)).toHaveLength(1);

    expect((await catalog.raw("delete", `/v1/catalog/context-system/${created.id}`)).status()).toBe(204);
    expect((await catalog.raw("get", `/v1/catalog/context-system/${created.id}`)).status()).toBe(404);
    expect(
      (await catalog.raw("delete", `/v1/catalog/context-system/${created.id}`)).status(),
      "the context delete reports a second attempt, where the MCP delete does not",
    ).toBe(404);
  } finally {
    await catalog.deleteContextSystem(created.id).catch(() => {});
  }
});

test("a context service is filtered, exported by either verb, and previewed back", { tag: ["@catalog", "@tier2"] }, async ({ catalog, run }) => {
  const name = tokenized(run, "ctx-transfer");
  const created = await catalog.createContextSystem(name, "exported and read back");
  try {
    // The filter body is a bare list of clauses. The MCP twin beside it wraps the same clauses in
    // `{searchString, filters}`, so a body copied between the two families is accepted and ignored.
    const filtered = await catalog.filterContextSystems([
      { column: "NAME", condition: "CONTAINS", value: name },
    ]);
    expect(filtered.map((each) => each.id)).toEqual([created.id]);

    // One `@RequestMapping(method = {GET, POST})` serves the export, so the two verbs are one
    // mapping and the archives are asserted equal rather than assumed to be.
    const entry = `services/${created.id}/${created.id}.context-service.cip.yaml`;
    const viaGet = await catalog.exportContextSystems([created.id], "get");
    const viaPost = await catalog.exportContextSystems([created.id], "post");
    expect(viaGet.status()).toBe(200);
    expect(viaPost.status()).toBe(200);
    expect(await entryNames(await archiveOf(viaGet))).toEqual([entry]);
    expect(await entryNames(await archiveOf(viaPost)), "the POST form answers the same archive").toEqual(
      [entry],
    );

    // The preview reads the archive and reports what an import would do to the row it names,
    // without doing it: the service is still there afterwards, and still under its own description.
    const archive = Buffer.from(await viaPost.body());
    expect(await catalog.previewContextSystemImport(archive)).toEqual([
      { id: created.id, name, modified: 0, requiredAction: "UPDATE" },
    ]);
    expect((await catalog.getContextSystem(created.id)).description).toBe("exported and read back");
  } finally {
    await catalog.deleteContextSystem(created.id).catch(() => {});
  }
});

test("an MCP service round-trips", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  const name = tokenized(run, "mcp");
  const created = await catalog.createMcpSystem({
    name,
    description: "the original",
    identifier: name,
    instructions: "answer questions",
  });
  try {
    expect(await catalog.getMcpSystem(created.id)).toMatchObject({
      id: created.id,
      name,
      identifier: name,
      instructions: "answer questions",
    });

    const updated = await catalog.updateMcpSystem(created.id, {
      name: `${name}-renamed`,
      identifier: name,
    });
    expect(updated).toMatchObject({
      name: `${name}-renamed`,
      description: "the original",
      instructions: "answer questions",
    });

    const filtered = await catalog.filterMcpSystems(name);
    expect(filtered.map((each) => each.id)).toContain(created.id);

    expect((await catalog.raw("delete", `/v1/catalog/mcp-system/${created.id}`)).status()).toBe(204);
    expect((await catalog.raw("get", `/v1/catalog/mcp-system/${created.id}`)).status()).toBe(404);
    expect(
      (await catalog.raw("delete", `/v1/catalog/mcp-system/${created.id}`)).status(),
      "the MCP delete goes through ifPresent, so a second attempt is a no-op rather than a 404",
    ).toBe(204);
  } finally {
    await catalog.deleteMcpSystem(created.id).catch(() => {});
  }
});

test("the service search matches a name or a description, and the filter the name alone", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  const marker = tokenized(run, "svc-search-marked");
  const byName = await catalog.createSystem(`${marker}-by-name`, "EXTERNAL");
  const byDescription = await catalog.createSystem(tokenized(run, "svc-search-described"), "INTERNAL", `mentions ${marker}`);
  const unrelated = await catalog.createSystem(tokenized(run, "svc-search-unrelated"), "INTERNAL");
  try {
    // The search ignores case, so the upper-cased marker finds the same rows.
    expect((await catalog.searchSystems(marker.toUpperCase())).map((each) => each.id).sort()).toEqual(
      [byName.id, byDescription.id].sort(),
    );
    expect((await catalog.searchSystems(byName.id)).map((each) => each.id), "an exact id matches too").toEqual([
      byName.id,
    ]);

    const filtered = await catalog.filterSystems([{ column: "NAME", condition: "CONTAINS", value: marker }]);
    expect(filtered.map((each) => each.id)).toEqual([byName.id]);
    expect(filtered[0]).toMatchObject({ name: byName.name, type: "EXTERNAL" });
  } finally {
    for (const system of [byName, byDescription, unrelated]) await catalog.deleteSystem(system.id).catch(() => {});
  }
});

test("a service-call names its service to the usage readers", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const system = await catalog.createSystem(tokenized(run, "svc-usage"), "EXTERNAL");
  const specificationId = `${system.id}-${tokenized(run, "svc-usage")}-1.0.0`;
  const chain = await catalog.createChain(tokenized(run, "svc-usage-caller"), folder.id);
  const bystander = await catalog.createChain(tokenized(run, "svc-usage-bystander"), folder.id);
  try {
    const caller = await catalog.createElement(chain.id, "service-call");
    // The operation tab's fields, as the UI writes them on choosing an operation. The readers take
    // the ids as they stand, so no specification has to be imported behind them.
    await catalog.patchElementProperties(chain.id, caller.id, {
      systemType: "EXTERNAL",
      integrationSystemId: system.id,
      integrationSpecificationGroupId: `${system.id}-${tokenized(run, "svc-usage")}`,
      integrationSpecificationId: specificationId,
      integrationOperationId: tokenized(run, "svc-usage-op"),
      integrationOperationPath: "/orders",
      integrationOperationMethod: "POST",
    });

    expect(await catalog.usedSystems([chain.id, bystander.id])).toEqual([
      { systemId: system.id, usedSystemModelIds: [specificationId] },
    ]);

    // One row per calling element. `version` is left out, because no specification with that id
    // exists to name one.
    const external = await catalog.systemUsage("EXTERNAL");
    expect(external.filter((row) => row.chainId === chain.id)).toEqual([
      {
        service: system.name,
        method: "POST",
        path: "/orders",
        chainId: chain.id,
        chainName: chain.name,
        elementId: caller.id,
        elementName: caller.name,
      },
    ]);
    expect((await catalog.systemUsage("internal")).filter((row) => row.chainId === chain.id)).toEqual([]);
    const refused = await catalog.raw("get", "/v1/systems/usage");
    expect(refused.status(), "the type is required").toBe(400);
    expect(((await refused.json()) as { errorMessage: string }).errorMessage).toBe(
      "Required parameter 'type' is not present",
    );

    const [withUsage] = await catalog.searchSystems(system.id, true);
    expect((withUsage.chains ?? []).map((each) => each.id)).toEqual([chain.id]);
    expect((await catalog.searchSystems(system.id))[0].chains, "usage is only added on request").toBeUndefined();
  } finally {
    // The service cannot be deleted while a chain references it.
    await catalog.deleteChain(chain.id).catch(() => {});
    await catalog.deleteSystem(system.id).catch(() => {});
  }
});

test("a service archive is previewed as an update while the service exists and as a create once it is gone", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  const name = tokenized(run, "svc-preview");
  const created = await catalog.createSystem(name, "INTERNAL", "previewed");
  try {
    const archive = await catalog.exportSystems([created.id]);

    expect(await catalog.previewSystemImport(archive)).toEqual([
      { id: created.id, name, modified: 0, requiredAction: "UPDATE" },
    ]);
    expect((await catalog.getSystem(created.id)).description, "the preview wrote nothing").toBe("previewed");

    await catalog.deleteSystem(created.id);
    expect(await catalog.previewSystemImport(archive)).toEqual([
      { id: created.id, name, modified: 0, requiredAction: "CREATE" },
    ]);
    expect((await catalog.raw("get", `/v1/systems/${created.id}`)).status(), "the preview created nothing").toBe(404);
  } finally {
    await catalog.deleteSystem(created.id).catch(() => {});
  }
});

test("an MCP service archive is previewed as an update while the service exists and as a create once it is gone", { tag: ["@catalog", "@tier2"] }, async ({ catalog, run }) => {
  const name = tokenized(run, "mcp-preview");
  const created = await catalog.createMcpSystem({ name, identifier: name, description: "previewed" });
  try {
    const archive = await catalog.exportMcpSystems([created.id]);

    expect(await catalog.previewMcpSystemImport(archive)).toEqual([
      { id: created.id, name, modified: 0, requiredAction: "UPDATE" },
    ]);
    expect((await catalog.getMcpSystem(created.id)).description, "the preview wrote nothing").toBe("previewed");

    await catalog.deleteMcpSystem(created.id);
    expect(await catalog.previewMcpSystemImport(archive)).toEqual([
      { id: created.id, name, modified: 0, requiredAction: "CREATE" },
    ]);
    expect((await catalog.raw("get", `/v1/catalog/mcp-system/${created.id}`)).status(), "the preview created nothing").toBe(404);
  } finally {
    await catalog.deleteMcpSystem(created.id).catch(() => {});
  }
});
