/**
 * Export and import in the format the platform ships today.
 *
 * Every case here is a round trip — export, delete, import the original bytes, compare — because
 * that is the only shape in which an import proves anything. Two rules of `e2e/AGENTS.md` exist for
 * this file and both are load-bearing:
 *
 * - **Delete before importing.** An import over a live id is an update, so "the chain is present
 *   afterwards" passes whether the import created it, updated it, or did nothing at all. The
 *   `UPDATED` case below is that mutation check made permanent rather than run once by hand.
 * - **Import the original bytes.** A re-zipped tree whose entries sit at the zip root imports
 *   **nothing** and reports success: measured, `POST /v1/catalog/import` answers 200 with
 *   `{"chains": []}`. The case that pins it is the reason a spec asserts the returned rows and not
 *   the status.
 *
 * What the export carries, measured against this stack:
 *
 * - entries are `chains/<id>/<id>.chain.qip.yaml`, and the chain id survives a round trip;
 * - a folder is **not** a document. The tree travels as `metaInfo.group: "outer/inner"`, so the
 *   import rebuilds folders by name — reusing one that still exists, creating one that does not,
 *   and giving the new one a **new id**. A spec comparing folder ids across a round trip is
 *   asserting the wrong half;
 * - `deployAction: SNAPSHOT` rides along, so the import builds a snapshot as its last step. A chain
 *   that cannot be snapshotted still imports, and says so as **207** with a row whose status is
 *   `ERROR` and whose message begins `Chain is saved but without snapshot`.
 *
 * Import instructions are the other half of the file. They are global — no folder, no owner — so
 * every case names only its own chain id, and `POST .../import-instructions/upload` deserves the
 * warning it carries in the client: every id under a `delete` action is deleted from the platform
 * as the file is read.
 */
import { test, expect } from "../../support/fixtures.js";
import { tokenized } from "../../support/run.js";
import { tokenizedChain } from "../../support/deployable.js";
import { entryNames, entryText, zipOf } from "../../support/zip.js";
import { chainEntry, chainExists, importChains } from "../../support/chain-import.js";
import { readSpecificationFixture } from "../../fixtures/templating.js";

/** The `metaInfo.group` line of an exported chain, which is the folder path and the only copy of it. */
function groupOf(document: string): string | undefined {
  return /^\s*group:\s*"?([^"\n]*)"?\s*$/m.exec(document)?.[1];
}

/** Re-zips a chain document at the archive root — the layout that imports nothing. */
async function flatten(archive: Buffer, entry: string): Promise<Buffer> {
  return await zipOf([[entry.split("/").pop() as string, await entryText(archive, entry)]]);
}

test("a chain round-trips through export, delete, and import of the original bytes", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await tokenizedChain(catalog, run, {
    prefix: "export",
    what: "roundtrip",
    parentId: folder.id,
  });

  const archive = await catalog.exportChains([chain.id]);
  // The exact entry path, not the basename: a root-layout archive has the same basename and
  // imports nothing, so a basename assertion is green over the failure it exists to catch.
  expect(await entryNames(archive)).toEqual([chainEntry(chain.id)]);

  await catalog.deleteChain(chain.id);
  expect(await chainExists(catalog, chain.id)).toBe(false);

  const imported = await importChains(catalog, archive);
  expect(imported.status).toBe(200);
  // The rows, not the status. An import that imported nothing answers 200 with an empty array.
  expect(imported.body.chains).toEqual([
    expect.objectContaining({ id: chain.id, name: chain.name, status: "CREATED" }),
  ]);

  const restored = await catalog.getChain(chain.id);
  expect(restored.name).toBe(chain.name);
  // The folder still existed, so the import matched it by name rather than creating a second one.
  expect(restored.parentId).toBe(folder.id);

  const elements = await catalog.listChainElements(chain.id);
  expect(elements.map((element) => element.type).sort()).toEqual(["header-modification", "http-trigger"]);
  const trigger = elements.find((element) => element.type === "http-trigger");
  expect(trigger?.properties?.contextPath).toBe(chain.contextPath);
});

test("an import over a live chain updates it, which is why the delete step exists", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await tokenizedChain(catalog, run, {
    prefix: "export",
    what: "update",
    parentId: folder.id,
  });
  const archive = await catalog.exportChains([chain.id]);

  // No delete. The chain is present before and after, so every assertion about presence passes —
  // and the row is the only thing that says the import created nothing.
  const imported = await importChains(catalog, archive);

  expect(imported.status).toBe(200);
  expect(imported.body.chains).toEqual([
    expect.objectContaining({ id: chain.id, name: chain.name, status: "UPDATED" }),
  ]);
  expect(await chainExists(catalog, chain.id)).toBe(true);
});

test("a folder tree round-trips with its nested chains, rebuilt by name", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const outer = await catalog.createFolder(tokenized(run, "tree-outer"), folder.id);
  const inner = await catalog.createFolder(tokenized(run, "tree-inner"), outer.id);
  const shallow = await tokenizedChain(catalog, run, {
    prefix: "export",
    what: "tree-shallow",
    parentId: outer.id,
  });
  const deep = await tokenizedChain(catalog, run, {
    prefix: "export",
    what: "tree-deep",
    parentId: inner.id,
  });

  const archive = await catalog.exportChains([shallow.id, deep.id]);
  expect(await entryNames(archive)).toEqual([chainEntry(shallow.id), chainEntry(deep.id)].sort());

  // The folder path is a string inside each chain document. There is no folder document, so this
  // line is the whole of what the archive knows about the tree.
  expect(groupOf(await entryText(archive, chainEntry(shallow.id)))).toBe(
    `${folder.name}/${outer.name}`,
  );
  expect(groupOf(await entryText(archive, chainEntry(deep.id)))).toBe(
    `${folder.name}/${outer.name}/${inner.name}`,
  );

  await catalog.deleteFolder(outer.id);
  expect(await chainExists(catalog, shallow.id)).toBe(false);
  expect(await chainExists(catalog, deep.id)).toBe(false);

  const imported = await importChains(catalog, archive);
  expect(imported.status).toBe(200);
  expect(imported.body.chains.map((row) => row.status)).toEqual(["CREATED", "CREATED"]);
  expect(imported.body.chains.map((row) => row.id).sort()).toEqual([shallow.id, deep.id].sort());

  // Chain ids survive; folder ids do not, because the folders were rebuilt from their names.
  const rebuiltOuter = (await catalog.getChain(shallow.id)).parentId as string;
  const rebuiltInner = (await catalog.getChain(deep.id)).parentId as string;
  expect(rebuiltOuter).not.toBe(outer.id);
  expect((await catalog.getFolder(rebuiltOuter)).name).toBe(outer.name);
  expect((await catalog.getFolder(rebuiltInner)).name).toBe(inner.name);
  expect((await catalog.getFolder(rebuiltInner)).parentId).toBe(rebuiltOuter);
  // And the rebuilt tree hangs off this worker's folder, so the cascade still reaches it.
  expect((await catalog.getFolder(rebuiltOuter)).parentId).toBe(folder.id);
});

test("an archive whose entries sit at the zip root imports nothing and reports success", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await tokenizedChain(catalog, run, {
    prefix: "export",
    what: "flat",
    parentId: folder.id,
  });
  const archive = await catalog.exportChains([chain.id]);
  const flat = await flatten(archive, chainEntry(chain.id));
  expect(await entryNames(flat)).toEqual([`${chain.id}.chain.qip.yaml`]);

  await catalog.deleteChain(chain.id);
  const imported = await importChains(catalog, flat);

  // 200 and no error anywhere. The empty array is the only report that nothing was imported, and a
  // spec asserting the status would call this a pass.
  expect(imported.status).toBe(200);
  expect(imported.body.chains).toEqual([]);
  expect(await chainExists(catalog, chain.id)).toBe(false);
});

test("the single-chain export and the multi-chain export carry the same bytes", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await tokenizedChain(catalog, run, {
    prefix: "export",
    what: "shapes",
    parentId: folder.id,
  });
  const entry = chainEntry(chain.id);

  const single = await catalog.exportChain(chain.id);
  expect(await entryNames(single)).toEqual([entry]);
  expect(await entryText(single, entry)).toBe(
    await entryText(await catalog.exportChains([chain.id]), entry),
  );

  // Export-all is deliberately not read here, and the rule that says so is `e2e/AGENTS.md` rule 2:
  // a spec that needs a global view does not belong in `specs/api/`. The exporter lists every chain
  // and then resolves each one's folder, so a folder a parallel worker deletes in between makes the
  // whole request answer **500 `EntityNotFoundException: Unable to find … Folder with id …`** —
  // measured under a full `--project=api` run, 3 of 120 calls answered that way. There is no
  // version of the call scoped to this case's own entities, and a retry around it waits on nothing
  // but the other workers. It is owed a single-worker project.
});

test("the preview names what an import would create without creating it", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await tokenizedChain(catalog, run, {
    prefix: "export",
    what: "preview",
    parentId: folder.id,
  });
  const archive = await catalog.exportChains([chain.id]);
  await catalog.deleteChain(chain.id);

  const preview = await catalog.previewImport(archive);

  expect(preview.chains).toEqual([
    expect.objectContaining({ id: chain.id, name: chain.name, deployAction: "SNAPSHOT" }),
  ]);
  expect(await chainExists(catalog, chain.id)).toBe(false);
});

test("an ignore instruction keeps the import from creating the chain", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await tokenizedChain(catalog, run, {
    prefix: "export",
    what: "ignored",
    parentId: folder.id,
  });
  const archive = await catalog.exportChains([chain.id]);

  try {
    await catalog.addImportInstruction({ id: chain.id, entityType: "CHAIN", action: "IGNORE" });
    await catalog.deleteChain(chain.id);

    const imported = await importChains(catalog, archive);

    // What matters, and what a fix to the reporting below would not change: nothing was created.
    expect(await chainExists(catalog, chain.id)).toBe(false);
    expect(imported.body.chains).toHaveLength(1);
    expect(imported.body.chains[0].id).toBe(chain.id);
    // Measured, and it is a divergence rather than a contract: `ImportService.makeDeployActions`
    // — the `/v1` path — skips only `ERROR`, where `ChainImportService` (the `/v2` and `/v3` path)
    // skips `IGNORED` and `SKIPPED` too. So the ignored row goes on to a snapshot build that cannot
    // find its chain, and the reported status is `ERROR` with 207 rather than `IGNORED` with 200.
    // The assertion says only that the import did not claim to have written the chain.
    expect(["CREATED", "UPDATED"]).not.toContain(imported.body.chains[0].status);
  } finally {
    await catalog.deleteImportInstructions({ chains: [chain.id] }).catch(() => {});
  }
});

test("an import instruction is created, read, searched, filtered, updated, and deleted", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await catalog.createChain(tokenized(run, "instruction"), folder.id);
  const override = await catalog.createChain(tokenized(run, "instruction-target"), folder.id);
  const ours = (instructions: { chains: { ignore?: Array<{ id: string }> } }) =>
    (instructions.chains.ignore ?? []).filter((each) => each.id === chain.id);

  try {
    const created = await catalog.addImportInstruction({
      id: chain.id,
      entityType: "CHAIN",
      action: "IGNORE",
    });
    // The name is resolved from the live chain rather than stored, which is why it is absent once
    // the chain is gone — and why the sweep cannot find an orphaned instruction by name.
    expect(created).toMatchObject({ id: chain.id, name: chain.name, preview: false });

    expect(ours(await catalog.listImportInstructions())).toHaveLength(1);

    // The search reads `ID` and `OVERRIDDEN_BY` and nothing else, so the name every response
    // carries matches nothing. A case searching by name would be green over an empty result.
    expect(ours(await catalog.searchImportInstructions(chain.id.slice(0, 8)))).toHaveLength(1);
    expect(ours(await catalog.searchImportInstructions(chain.name))).toHaveLength(0);

    expect(
      ours(await catalog.filterImportInstructions([{ column: "ID", condition: "IS", value: chain.id }])),
    ).toHaveLength(1);
    // A column the builder cannot translate is a 500, not the 400 the audit-log filter answers.
    const unsupported = await catalog.raw("post", "/v1/catalog/import-instructions/filter", [
      { column: "TOPIC", condition: "IS", value: chain.id },
    ]);
    expect(unsupported.status()).toBe(500);
    expect(await unsupported.text()).toContain("Unexpected feature value: TOPIC");

    const updated = await catalog.updateImportInstruction({
      id: chain.id,
      entityType: "CHAIN",
      action: "OVERRIDE",
      overriddenBy: override.id,
    });
    expect(updated.overriddenById).toBe(override.id);
    const afterUpdate = await catalog.listImportInstructions();
    expect(ours(afterUpdate)).toHaveLength(0);
    expect((afterUpdate.chains.override ?? []).filter((each) => each.id === chain.id)).toHaveLength(1);

    await catalog.deleteImportInstructions({ chains: [chain.id] });
    const afterDelete = await catalog.listImportInstructions();
    expect((afterDelete.chains.override ?? []).filter((each) => each.id === chain.id)).toHaveLength(0);
    // The delete is idempotent: an id nothing answers to is 204 rather than 404.
    await catalog.deleteImportInstructions({ chains: [chain.id] });
  } finally {
    await catalog.deleteImportInstructions({ chains: [chain.id] }).catch(() => {});
  }
});

test("uploading an instruction configuration deletes immediately and merges the rest", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const doomed = await catalog.createChain(tokenized(run, "upload-doomed"), folder.id);
  const kept = await catalog.createChain(tokenized(run, "upload-kept"), folder.id);
  const stored = await catalog.createChain(tokenized(run, "upload-stored"), folder.id);
  const label = tokenized(run, "upload-label");

  try {
    // An instruction the uploaded file does not mention, to prove the upload merges.
    await catalog.addImportInstruction({ id: stored.id, entityType: "CHAIN", action: "IGNORE" });

    const results = await catalog.uploadImportInstructions(
      [
        "chains:",
        "  delete:",
        `  - "${doomed.id}"`,
        "  ignore:",
        `  - "${kept.id}"`,
        "  override: []",
        "",
      ].join("\n"),
      [label],
    );

    // The rows an upload answers with are its deletions, and the deletion has already happened.
    expect(results).toEqual([
      expect.objectContaining({ id: doomed.id, name: doomed.name, entityType: "CHAIN", status: "DELETED" }),
    ]);
    expect(await chainExists(catalog, doomed.id)).toBe(false);

    const instructions = await catalog.listImportInstructions();
    const ignore = instructions.chains.ignore ?? [];
    expect(ignore.find((each) => each.id === kept.id)?.labels).toEqual([label]);
    // Merged, not replaced: the instruction the file never mentioned is still there.
    expect(ignore.map((each) => each.id)).toContain(stored.id);

    const exported = await catalog.exportImportInstructions();
    expect(exported.status()).toBe(200);
    expect(exported.headers()["content-disposition"]).toContain("import-instructions.yaml");
    const document = await exported.text();
    expect(document).toContain(kept.id);
    // The YAML carries two sections the JSON view has no field for, so the two readings of one
    // configuration disagree about what a configuration holds.
    expect(document).toContain("contextServices:");
    expect(document).toContain("mcpServices:");
  } finally {
    await catalog
      .deleteImportInstructions({ chains: [doomed.id, kept.id, stored.id] })
      .catch(() => {});
  }
});

test("a specification exports as its own bytes and a group as an archive", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  const service = await catalog.createSystem(tokenized(run, "spec-export"), "EXTERNAL");
  const groupName = tokenized(run, "spec-export-group");
  const groupId = `${service.id}-${groupName}`;
  const first = readSpecificationFixture("widgets.openapi.yaml");
  const second = readSpecificationFixture("widgets-v2.openapi.yaml");

  // The service owns the group and both specifications, so deleting it is the whole teardown — and
  // it has to be a `finally`: `awaitSpecificationImport` throws on a done-but-empty import, which
  // is the failure it exists for, and every one of the reads below can fail before the case ends.
  try {
    const started = await catalog.importSpecificationGroup(service.id, groupName, first);
    await catalog.awaitSpecificationImport(started);
    const added = await catalog.importSpecification(groupId, second);
    await catalog.awaitSpecificationImport(added);

    // Both ids are derived from `info.version`, which is what makes the two fixtures tell apart.
    const firstModel = `${groupId}-1.0.0`;
    const secondModel = `${groupId}-2.0.0`;
    expect((await catalog.listModels(groupId)).map((each) => each.id).sort()).toEqual(
      [firstModel, secondModel].sort(),
    );

    // One id whose specification has one source: the source file itself, byte for byte, under its
    // own name. Not an archive, so a caller that unconditionally unzips gets a parse error.
    const single = await catalog.exportSpecifications({ specificationIds: [firstModel] });
    expect(single.status()).toBe(200);
    expect(single.headers()["content-disposition"]).toContain(first.name);
    expect(Buffer.from(await single.body())).toEqual(first.buffer);

    // The group is the archive form, and each entry sits under its specification's id rather than
    // under the name of the file it came from.
    const group = await catalog.exportSpecifications({ specificationGroupId: groupId });
    expect(group.status()).toBe(200);
    expect(await entryNames(Buffer.from(await group.body()))).toEqual(
      [`source-${firstModel}/${first.name}`, `source-${secondModel}/${second.name}`].sort(),
    );

    // `specificationIds` is a filter within a group and not a selector: two ids and no group is a
    // **404**, where a caller would expect an archive of two.
    const both = await catalog.exportSpecifications({
      specificationIds: [firstModel, secondModel],
    });
    expect(both.status()).toBe(404);
    const empty = await catalog.exportSpecifications({});
    expect(empty.status()).toBe(404);
  } finally {
    await catalog.deleteSystem(service.id).catch(() => {});
  }
});
