/**
 * The frozen corpus, checked without a stack: the checksums and the floor each archive is filed
 * under. `fixtures.spec.ts` checks the archive layout the frozen archives assemble into.
 *
 * `legacy-import.spec.ts` imports these archives; this file is what says they are still the
 * archives that were frozen. A corpus quietly regenerated from today's exporter passes every
 * import assertion there and proves nothing, so the guard has to sit somewhere the import cannot
 * reach: off disk, in a project that needs no services. `specs/schema/frozen-corpus.spec.ts` reads
 * the floors against the migration classes and the samples.
 */
import { test, expect } from "@playwright/test";
import fs from "node:fs";
import {
  CHECKSUM_FILE,
  FROZEN_ARCHIVES,
  FROZEN_ARCHIVE_DIR,
  checksumProblems,
  computeChecksums,
  floorKey,
  floorOfFile,
  frozenDocumentPath,
  frozenFiles,
  readChecksums,
} from "../../fixtures/archives/frozen.js";

test.describe("frozen corpus", () => {
  test("FROZEN_ARCHIVES names every archive directory", () => {
    expect(
      fs
        .readdirSync(FROZEN_ARCHIVE_DIR, { withFileTypes: true })
        .filter((entry) => entry.isDirectory())
        .map((entry) => entry.name)
        .sort(),
    ).toEqual(FROZEN_ARCHIVES.map((archive) => archive.name).sort());
  });

  test("every frozen file is in the manifest with its own hash", () => {
    expect(checksumProblems(readChecksums(), computeChecksums())).toEqual([]);
  });

  test("the manifest is the rendering of the tree, byte for byte", () => {
    // The digests can all match while the file is unreadable — duplicated lines, a stale ordering, a
    // hand-edited comment. `npm run frozen-checksums` writes this file, and this is the assertion
    // that it was the thing that wrote it.
    const rendered = `${[...computeChecksums()].map(([rel, hash]) => `${hash}  ${rel}`).join("\n")}\n`;
    expect(fs.readFileSync(CHECKSUM_FILE, "utf-8")).toBe(rendered);
  });

  test("a changed, missing, or unlisted file fails the manifest", () => {
    // The mutation check, kept in the suite rather than run once by hand: the guard above is only
    // worth having if a change actually breaks it, and nothing else here would notice. It calls the
    // same function the guard calls, so deleting that function takes this case with it.
    const rel = frozenFiles()[0];
    // The tree stands in for the manifest, so the case says the same thing whatever state the real
    // manifest is in: a run over a genuinely changed corpus must still fail here for its own reason.
    const manifest = computeChecksums();

    const changed = new Map(manifest);
    changed.set(rel, "0".repeat(64));
    expect(checksumProblems(manifest, changed)).toEqual([
      `${rel}: changed without its checksum changing in the same commit`,
    ]);

    // The two directions comparing digests alone cannot see.
    const gone = new Map(manifest);
    gone.delete(rel);
    expect(checksumProblems(manifest, gone)).toEqual([
      `${rel}: is in the manifest with no file behind it`,
    ]);
    expect(checksumProblems(new Map(), manifest)).toContain(
      `${rel}: is in the tree with no entry in the manifest`,
    );
  });

  for (const archive of FROZEN_ARCHIVES) {
    test(`${archive.name} declares the floor it is filed under`, () => {
      // The directory name is a label; this is the reading that makes it true. A document swapped
      // for one at another floor leaves the corpus claiming coverage it does not have.
      expect(floorKey(floorOfFile(frozenDocumentPath(archive)))).toBe(floorKey(archive.floor));
    });

    test(`${archive.name} carries the run token`, () => {
      // A frozen document names things on a shared stack. Without the token the teardown sweep
      // cannot see them, and a crashed run leaves a chain and a root folder behind for the next one.
      expect(fs.readFileSync(frozenDocumentPath(archive), "utf-8")).toContain("e2e-{{RUN}}-");
    });
  }
});
