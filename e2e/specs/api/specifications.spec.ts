/**
 * Specification groups, the specifications imported into them, and the operations that produces.
 *
 * The import is the reason this file exists. Everything else in the catalog's service surface is a
 * row written and read back; here a document is parsed and something new appears, so the case that
 * matters asserts what the parse produced — three operations across two paths, each with its
 * method and its path — rather than that the call answered.
 *
 * Four shapes measured rather than assumed:
 *
 * - **The ids are derived, not generated.** A group is `{systemId}-{name}` and a specification is
 *   `{groupId}-{version}`, where the version comes from the document's own `info.version`. That is
 *   why the fixtures differ in `info.version`: two imports of one version into one group are the
 *   same specification.
 * - **The import is asynchronous.** `POST /v1/specificationGroups/import` answers **202** with an
 *   `importId` and `done: false`; the specification and its operations appear once
 *   `GET /v1/import/{importId}` reports `done`. Reading the group before that is a race, and it is
 *   the kind that passes on a quiet stack. The wait is `Catalog.awaitSpecificationImport`, which
 *   stops on the specifications and their operations rather than on the flag: an import that
 *   answers `done: true` having produced nothing is a shape this product has shipped twice.
 * - **`POST /v1/models/deprecated` takes the bare model id as `text/plain`**, not as JSON. Sent as
 *   JSON it arrives quoted and matches no specification.
 * - **A specification cannot be deleted until it is deprecated.** `DELETE /v1/models/{id}` on a
 *   live one answers `400 Specification must be deprecated`, which is what ties the two halves of
 *   this file's last case together. Deleting a group takes its specifications whatever their
 *   state, and deleting the last specification does not take the group.
 */
import { test, expect } from "../../support/fixtures.js";
import type { Catalog, SpecificationImportView } from "../../support/catalog.js";
import { readSpecificationFixture } from "../../fixtures/templating.js";
import { tokenized } from "../../support/run.js";

async function serviceWithSpecification(
  catalog: Catalog,
  run: string,
  what: string,
  file = "widgets.openapi.yaml",
): Promise<{ systemId: string; groupId: string; modelId: string; imported: SpecificationImportView }> {
  const service = await catalog.createSystem(tokenized(run, `spec-${what}`), "EXTERNAL");
  const groupName = tokenized(run, `group-${what}`);
  // The service exists from here on, and `awaitSpecificationImport` throws on a done-but-empty
  // import — the failure it exists for. The caller's `finally` cannot clean up an id the helper
  // never returned, so the helper cleans up after itself and rethrows.
  try {
    const imported = await catalog.importSpecificationGroup(
      service.id,
      groupName,
      readSpecificationFixture(file),
      "http",
    );
    await catalog.awaitSpecificationImport(imported);
    return {
      systemId: service.id,
      groupId: `${service.id}-${groupName}`,
      modelId: `${service.id}-${groupName}-1.0.0`,
      imported,
    };
  } catch (cause) {
    await catalog.deleteSystem(service.id).catch(() => {});
    throw cause;
  }
}

test("importing a specification into a group produces the operations the document declares", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  const service = await catalog.createSystem(tokenized(run, "spec-import"), "EXTERNAL");
  const groupName = tokenized(run, "group-import");
  try {
    const started = await catalog.importSpecificationGroup(
      service.id,
      groupName,
      readSpecificationFixture("widgets.openapi.yaml"),
      "http",
    );
    // The group exists immediately and the specification does not: the id comes back on the 202,
    // and `done` is false.
    expect(started.specificationGroupId).toBe(`${service.id}-${groupName}`);
    expect(started.done).toBe(false);

    await catalog.awaitSpecificationImport(started);

    const groups = await catalog.listSpecificationGroups(service.id);
    expect(groups.map((each) => each.id)).toEqual([`${service.id}-${groupName}`]);
    expect(groups[0].name).toBe(groupName);

    const models = await catalog.listModels(groups[0].id);
    expect(models.map((each) => each.name)).toEqual(["1.0.0"]);
    // The version is the document's `info.version`, and the id is built from it.
    expect(models[0].id).toBe(`${service.id}-${groupName}-1.0.0`);
    expect(models[0]).toMatchObject({ version: "1.0.0", deprecated: false, systemId: service.id });

    // What the import actually produced. A count alone would pass on one operation per path, or
    // one per file, so each operation is named with its method and its path.
    const operations = await catalog.listOperations(models[0].id);
    expect(
      operations.map((each) => `${each.method} ${each.path}`).sort(),
      "three operations across two paths, as the document declares them",
    ).toEqual(["GET /widgets", "GET /widgets/{id}", "POST /widgets"]);
    expect(operations.map((each) => each.name).sort()).toEqual([
      "createWidget",
      "getWidget",
      "listWidgets",
    ]);
    for (const operation of operations) expect(operation.modelId).toBe(models[0].id);

    // And the same operations are reachable through the specification itself, which is the shape
    // the UI reads.
    const model = await catalog.getModel(models[0].id);
    expect(model.operations?.map((each) => each.name).sort()).toEqual([
      "createWidget",
      "getWidget",
      "listWidgets",
    ]);
  } finally {
    await catalog.deleteSystem(service.id).catch(() => {});
  }
});

test("a second version imports into the group that already exists rather than beside it", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  const fixture = await serviceWithSpecification(catalog, run, "versions");
  try {
    const second = await catalog.importSpecification(
      fixture.groupId,
      readSpecificationFixture("widgets-v2.openapi.yaml"),
    );
    expect(second.specificationGroupId).toBe(fixture.groupId);
    await catalog.awaitSpecificationImport(second);

    const groups = await catalog.listSpecificationGroups(fixture.systemId);
    expect(groups.map((each) => each.id), "one group, two versions").toEqual([fixture.groupId]);

    const models = await catalog.listModels(fixture.groupId);
    expect(models.map((each) => each.name).sort()).toEqual(["1.0.0", "2.0.0"]);
    // The two versions are told apart by what they produced, not only by their names: the second
    // fixture declares one operation where the first declares three.
    const byVersion = Object.fromEntries(models.map((each) => [each.version, each.id]));
    expect(await catalog.listOperations(byVersion["1.0.0"])).toHaveLength(3);
    expect((await catalog.listOperations(byVersion["2.0.0"])).map((each) => each.name)).toEqual([
      "listWidgets",
    ]);
  } finally {
    await catalog.deleteSystem(fixture.systemId).catch(() => {});
  }
});

test("an empty specification group is created, patched, and deleted without an import", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  const service = await catalog.createSystem(tokenized(run, "spec-group"), "EXTERNAL");
  const groupName = tokenized(run, "group-empty");
  try {
    const created = await catalog.createSpecificationGroup(service.id, groupName, {
      description: "the original",
    });
    // Derived rather than generated, which is what makes a group addressable before it is read.
    expect(created.id).toBe(`${service.id}-${groupName}`);
    expect(created).toMatchObject({ name: groupName, systemId: service.id, synchronization: false });
    expect(created.specifications ?? []).toEqual([]);

    const patched = await catalog.patchSpecificationGroup(created.id, { synchronization: true });
    expect(patched.synchronization).toBe(true);
    expect((await catalog.listSpecificationGroups(service.id))[0].synchronization).toBe(true);

    await catalog.deleteSpecificationGroup(created.id);
    expect(await catalog.listSpecificationGroups(service.id)).toEqual([]);
  } finally {
    await catalog.deleteSystem(service.id).catch(() => {});
  }
});

test("a specification is deprecated in place and stays readable", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  const fixture = await serviceWithSpecification(catalog, run, "deprecate");
  try {
    expect((await catalog.getModel(fixture.modelId)).deprecated).toBe(false);

    const deprecated = await catalog.deprecateModel(fixture.modelId);
    expect(deprecated.id).toBe(fixture.modelId);
    expect(deprecated.deprecated).toBe(true);

    // Re-read rather than taken from the response: an endpoint echoing its request body passes the
    // shallower assertion having written nothing.
    const reread = await catalog.getModel(fixture.modelId);
    expect(reread.deprecated).toBe(true);
    // Deprecation hides nothing — the specification keeps its operations and its group.
    expect(await catalog.listOperations(fixture.modelId)).toHaveLength(3);
    expect((await catalog.listModels(fixture.groupId)).map((each) => each.id)).toEqual([
      fixture.modelId,
    ]);
  } finally {
    await catalog.deleteSystem(fixture.systemId).catch(() => {});
  }
});

test("deleting a specification leaves its group, and deleting the group takes the rest", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  const fixture = await serviceWithSpecification(catalog, run, "delete");
  try {
    const second = await catalog.importSpecification(
      fixture.groupId,
      readSpecificationFixture("widgets-v2.openapi.yaml"),
    );
    await catalog.awaitSpecificationImport(second);
    const secondId = `${fixture.groupId}-2.0.0`;

    // Deprecation is not decoration: `SystemModelService.deleteSystemModel` refuses a
    // specification that is not deprecated, so the delete has a precondition and this is it.
    const premature = await catalog.raw("delete", `/v1/models/${fixture.modelId}`);
    expect(premature.status()).toBe(400);
    expect(await premature.text()).toContain("Specification must be deprecated");
    expect((await catalog.raw("get", `/v1/models/${fixture.modelId}`)).status()).toBe(200);

    await catalog.deprecateModel(fixture.modelId);
    expect((await catalog.raw("delete", `/v1/models/${fixture.modelId}`)).status()).toBe(204);
    expect((await catalog.raw("get", `/v1/models/${fixture.modelId}`)).status()).toBe(404);
    expect((await catalog.raw("delete", `/v1/models/${fixture.modelId}`)).status()).toBe(404);
    // The group survives its specification, and the other version is untouched.
    expect((await catalog.listSpecificationGroups(fixture.systemId)).map((each) => each.id)).toEqual([
      fixture.groupId,
    ]);
    expect((await catalog.listModels(fixture.groupId)).map((each) => each.id)).toEqual([secondId]);

    await catalog.deleteSpecificationGroup(fixture.groupId);
    expect(await catalog.listSpecificationGroups(fixture.systemId)).toEqual([]);
    expect(
      (await catalog.raw("get", `/v1/models/${secondId}`)).status(),
      "the group takes its remaining specifications with it",
    ).toBe(404);
  } finally {
    await catalog.deleteSystem(fixture.systemId).catch(() => {});
  }
});
