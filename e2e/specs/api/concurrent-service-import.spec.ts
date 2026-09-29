/**
 * Concurrent export and import cycles must not interfere with each other.
 *
 * Four lanes each create their own services, export them, delete them, and import the same bytes
 * back, while every lane reads the same global `GET /v1/systems` list the others are mutating.
 *
 * The spec doubles as the guard on this suite's isolation rule: a case asserts only over what it
 * created, never over the list itself. A case written the other way passes alone and fails here.
 *
 * Ported onto the shared helpers with no change in behavior — the same six hops, the same
 * assertions. What the port buys is that the services now carry the run token, so a lane that dies
 * between the delete and the import leaves residue the sweep can find rather than a name nothing
 * recognizes.
 */
import { test, expect } from "../../support/fixtures.js";
import { tokenized } from "../../support/run.js";
import { entryNames } from "../../support/zip.js";

for (const lane of [1, 2, 3, 4]) {
  test(`lane ${lane}: a full export and import cycle sees only its own services`, { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
    const token = tokenized(run, `iso-${lane}`);
    const mine: string[] = [];

    try {
      for (const type of ["EXTERNAL", "INTERNAL", "IMPLEMENTED"]) {
        const created = await catalog.createSystem(`${token} ${type}`, type, token);
        mine.push(created.id);
      }
      mine.sort();

      const ours = async () => {
        const all = await catalog.listSystems();
        return all.map((system) => system.id).filter((id) => mine.includes(id)).sort();
      };
      expect(await ours()).toEqual(mine);

      const zip = await catalog.exportSystems(mine);
      expect(await entryNames(zip)).toEqual(mine.map((id) => `services/${id}/${id}.service.qip.yaml`).sort());

      // Delete before importing: an import over a live id is an update, so the assertion below
      // would pass whether the import created the services, updated them, or did nothing.
      for (const id of mine) await catalog.deleteSystem(id);
      expect(await ours()).toEqual([]);

      // The original bytes, not a re-zipped tree: an archive whose entries sit at the zip root
      // imports as 204 with an empty body and turns a no-op into a green run. The status is pinned
      // exactly for that reason — `< 300` accepts the 204 this line exists to catch.
      const imported = await catalog.importArchive("/v1/import/system", zip);
      expect(imported.status(), await imported.text()).toBe(200);
      expect(await ours()).toEqual(mine);
    } finally {
      for (const id of mine) await catalog.deleteSystem(id).catch(() => {});
    }
  });
}
