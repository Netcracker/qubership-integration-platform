/**
 * A service archive the catalog exported imports back into the catalog.
 *
 * `fixtures/services/service-roundtrip.zip` holds the unmodified bytes `GET /v1/export/system`
 * returned on September 24, 2026, for three services created through the API:
 *
 * - an `EXTERNAL` service with the specification group `r6-adj.dotted.grp`, holding
 *   `widgets.openapi.yaml` (version `1.0.0`) and `widgets-v2.openapi.yaml` (version `2.0.0`);
 * - an `INTERNAL` service with an active environment and the group `orders`, holding
 *   `orders.openapi.yaml`. The environment comes first because a specification import into an
 *   `INTERNAL` service with none fails in `OperationParserService.resolveEnvironments`;
 * - an `IMPLEMENTED` service with no specification.
 *
 * The archive is committed rather than exported per run, so the case also checks that today's
 * importer reads an export made at the `[100, 101, 102]` service migration floor. Its ids and
 * names are fixed, so they cannot carry the run token: the case deletes whatever holds those ids
 * before importing, and that delete collects the residue of a run that died mid-case. The fixed ids
 * also make two suite runs against one stack delete and import the same services, which is one
 * more reason the suite allows one run per stack at a time.
 *
 * The group name, the specification version, and the resource directory each put a dot into an
 * entry name, as in `<id>-r6-adj.dotted.grp.specification-group.qip.yaml`. A reader that takes the
 * text after the first dot as the type suffix reads `dotted` there, and the catalog writes such
 * entries with no extension involved.
 *
 * This is a catalog round trip; the VS Code extension takes no part in it. Whether the catalog
 * imports what the extension writes is `specs/api/extension-output.spec.ts`.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { test, expect } from "../../support/fixtures.js";
import { leftBehind } from "../../support/teardown.js";
import { entryNames, entryText } from "../../support/zip.js";

const ARCHIVE = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../../fixtures/services/service-roundtrip.zip",
);

const EXTERNAL = "d8b55b04-61a5-4996-b275-f791a3af111a";
const INTERNAL = "3b2e9742-139c-4750-bb76-adaf1cc9e3f0";
const IMPLEMENTED = "16e4e3c2-4023-4e17-8a5f-b723ef7819bb";
const IDS = [EXTERNAL, INTERNAL, IMPLEMENTED].sort();

const DOTTED_GROUP = `${EXTERNAL}-r6-adj.dotted.grp`;
const ORDERS_GROUP = `${INTERNAL}-orders`;

/** Every entry the committed archive holds, spelled out so a regenerated archive shows in review. */
const ENTRIES = [
  `services/${EXTERNAL}/${EXTERNAL}.service.qip.yaml`,
  `services/${EXTERNAL}/${DOTTED_GROUP}.specification-group.qip.yaml`,
  `services/${EXTERNAL}/${DOTTED_GROUP}-1.0.0.specification.qip.yaml`,
  `services/${EXTERNAL}/${DOTTED_GROUP}-2.0.0.specification.qip.yaml`,
  `services/${EXTERNAL}/resources/source-${DOTTED_GROUP}-1.0.0/widgets.openapi.yaml`,
  `services/${EXTERNAL}/resources/source-${DOTTED_GROUP}-2.0.0/widgets-v2.openapi.yaml`,
  `services/${INTERNAL}/${INTERNAL}.service.qip.yaml`,
  `services/${INTERNAL}/${ORDERS_GROUP}.specification-group.qip.yaml`,
  `services/${INTERNAL}/${ORDERS_GROUP}-1.0.0.specification.qip.yaml`,
  `services/${INTERNAL}/resources/source-${ORDERS_GROUP}-1.0.0/orders.openapi.yaml`,
  `services/${IMPLEMENTED}/${IMPLEMENTED}.service.qip.yaml`,
].sort();

interface ServiceWant {
  name: string;
  type: string;
  /** Group id to the group's name and the ids of its specifications. */
  groups: Record<string, { name: string; models: string[] }>;
}

/** What each service holds after the import. */
const EXPECTED: Record<string, ServiceWant> = {
  [EXTERNAL]: {
    name: "e2e-service-roundtrip-external",
    type: "EXTERNAL",
    groups: {
      [DOTTED_GROUP]: { name: "r6-adj.dotted.grp", models: [`${DOTTED_GROUP}-1.0.0`, `${DOTTED_GROUP}-2.0.0`] },
    },
  },
  [INTERNAL]: {
    name: "e2e-service-roundtrip-internal",
    type: "INTERNAL",
    groups: { [ORDERS_GROUP]: { name: "orders", models: [`${ORDERS_GROUP}-1.0.0`] } },
  },
  [IMPLEMENTED]: { name: "e2e-service-roundtrip-implemented", type: "IMPLEMENTED", groups: {} },
};

test("a committed service export imports back with every specification, dotted entry names included", { tag: ["@catalog", "@tier1"] }, async ({ catalog }) => {
  const committed = fs.readFileSync(ARCHIVE);
  expect(await entryNames(committed)).toEqual(ENTRIES);

  try {
    // Delete before importing: over a live id the import is an update, and every read below would
    // hold without the archive having been read.
    for (const id of IDS) {
      if (await catalog.holds(`/v1/systems/${id}`)) await catalog.deleteSystem(id);
    }

    const response = await catalog.importArchive("/v1/import/system", committed, "services.zip");
    expect(response.status(), await response.text()).toBe(200);
    const rows = (await response.json()) as Array<{ id: string; name: string; status: string }>;
    expect(rows.sort((a, b) => a.id.localeCompare(b.id))).toEqual(
      IDS.map((id) => ({ id, name: EXPECTED[id].name, status: "CREATED" })),
    );

    for (const id of IDS) {
      const want = EXPECTED[id];
      const system = await catalog.getSystem(id);
      expect({ name: system.name, type: system.type }, id).toEqual({ name: want.name, type: want.type });

      const groups = await catalog.listSpecificationGroups(id);
      expect(
        groups.map((group) => ({ id: group.id, name: group.name })).sort((a, b) => a.id.localeCompare(b.id)),
        `the specification groups of ${id}`,
      ).toEqual(Object.entries(want.groups).map(([groupId, group]) => ({ id: groupId, name: group.name })));

      for (const [groupId, group] of Object.entries(want.groups)) {
        const models = (await catalog.listModels(groupId)).map((model) => model.id).sort();
        expect(models, `the specifications of ${groupId}`).toEqual(group.models);
      }
    }

    // The export of what the import wrote matches the committed archive entry for entry, so the
    // environments, the operations, and the specification sources came back unchanged too.
    const exported = await catalog.exportSystems(IDS);
    expect(await entryNames(exported)).toEqual(ENTRIES);
    for (const entry of ENTRIES) {
      expect(await entryText(exported, entry), entry).toBe(await entryText(committed, entry));
    }
  } finally {
    // Deletes every fixed id rather than a listing, so a failed read cannot replace the case's own failure.
    for (const id of IDS) await catalog.deleteSystem(id).catch(leftBehind(`service ${id}`));
  }
});
