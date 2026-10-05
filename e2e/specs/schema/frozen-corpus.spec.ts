/**
 * The frozen corpus, checked without a stack: the checksums, the floors, and the migration lists
 * the floors are read against.
 *
 * `legacy-import.spec.ts` imports these archives; this file is what says they are still the
 * archives that were frozen. A corpus quietly regenerated from today's exporter passes every
 * import assertion there and proves nothing, so the guard has to sit somewhere the import cannot
 * reach — off disk, in the project that needs no services.
 *
 * The floor facts are asserted rather than commented. `sampleFloors` recomputes the distribution
 * over `schemas/src/test/resources/samples/` on every run, so a sample that gains a new declared
 * list turns the corpus red instead of leaving a floor silently uncovered.
 */
import { test, expect } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import JSZip from "jszip";
import {
  CHECKSUM_FILE,
  FROZEN_ARCHIVES,
  FROZEN_ARCHIVE_DIR,
  checksumProblems,
  computeChecksums,
  currentMigrationVersions,
  floorKey,
  floorOfFile,
  frozenDocumentPath,
  frozenFiles,
  readChecksums,
  sampleFloors,
} from "../../fixtures/archives/frozen.js";
import { assembleFixture } from "../../fixtures/templating.js";
import { EXAMPLE_RUN_TOKEN } from "../../support/run.js";

const RUN = EXAMPLE_RUN_TOKEN;

test("the corpus is non-empty and every entry has a directory", { tag: ["@infra", "@tier1"] }, () => {
  // Every case below loops over this list, so an empty one would pass all of them.
  expect(FROZEN_ARCHIVES.length).toBeGreaterThan(0);
  for (const archive of FROZEN_ARCHIVES) {
    expect(fs.existsSync(path.join(FROZEN_ARCHIVE_DIR, archive.name)), archive.name).toBe(true);
  }
  expect(
    fs
      .readdirSync(FROZEN_ARCHIVE_DIR, { withFileTypes: true })
      .filter((entry) => entry.isDirectory())
      .map((entry) => entry.name)
      .sort(),
  ).toEqual(FROZEN_ARCHIVES.map((archive) => archive.name).sort());
});

test("every frozen file is in the manifest and still hashes to what it says", { tag: ["@infra", "@tier1"] }, () => {
  expect(checksumProblems(readChecksums(), computeChecksums())).toEqual([]);
});

test("the manifest is exactly what the tree renders, byte for byte", { tag: ["@infra", "@tier1"] }, () => {
  // The digests can all match while the file is unreadable — duplicated lines, a stale ordering, a
  // hand-edited comment. `npm run frozen-checksums` writes this file, and this is the assertion
  // that it was the thing that wrote it.
  const rendered = `${[...computeChecksums()].map(([rel, hash]) => `${hash}  ${rel}`).join("\n")}\n`;
  expect(fs.readFileSync(CHECKSUM_FILE, "utf-8")).toBe(rendered);
});

test("a changed frozen file is caught by the manifest", { tag: ["@infra", "@tier1"] }, () => {
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
  test(`${archive.name} declares the floor it is filed under`, { tag: ["@infra", "@tier1"] }, () => {
    // The directory name is a label; this is the reading that makes it true. A document swapped
    // for one at another floor leaves the corpus claiming coverage it does not have.
    expect(floorKey(floorOfFile(frozenDocumentPath(archive)))).toBe(floorKey(archive.floor));
  });

  test(`${archive.name} assembles into the archive layout the importer reads`, { tag: ["@infra", "@tier1"] }, async () => {
    const zip = await JSZip.loadAsync(await assembleFixture(archive.name, RUN, FROZEN_ARCHIVE_DIR));
    const entries = Object.values(zip.files).filter((file) => !file.dir).map((file) => file.name);

    expect(entries).toHaveLength(1);
    // The full path, not the basename. An archive whose entry sits at the zip root imports
    // nothing and answers success, so a basename assertion is green over that failure.
    const parent = archive.kind === "chain" ? "chains" : "services";
    const postfix = archive.kind === "chain" ? ".chain.qip.yaml" : ".service.qip.yaml";
    expect(entries[0]).toMatch(
      new RegExp(`^${parent}/([0-9a-f-]{36})/\\1${postfix.replace(/\./g, "\\.")}$`),
    );
  });

  test(`${archive.name} carries the run token, so a run can sweep what it imports`, { tag: ["@infra", "@tier1"] }, () => {
    // A frozen document names things on a shared stack. Without the token the teardown sweep
    // cannot see them, and a crashed run leaves a chain and a root folder behind for the next one.
    expect(fs.readFileSync(frozenDocumentPath(archive), "utf-8")).toContain("e2e-{{RUN}}-");
  });
}

test("there are three independent migration lists, and each floor is read against its own", { tag: ["@infra", "@tier1"] }, () => {
  // The lists are read off the class names, so a `V109` landing in the tree shows up here rather
  // than in a runtime failure months later.
  expect(currentMigrationVersions("chain")).toEqual([100, 101, 102, 103, 104, 105, 106, 107, 108]);
  expect(currentMigrationVersions("service")).toEqual([100, 101, 102]);
  expect(currentMigrationVersions("mcp")).toEqual([100]);
});

for (const archive of FROZEN_ARCHIVES) {
  test(`${archive.name} is ${archive.supported ? "within" : "beyond"} the current list`, { tag: ["@infra", "@tier1"] }, () => {
    const current = currentMigrationVersions(archive.kind === "chain" ? "chain" : "service");
    const beyond = archive.floor.filter((version) => !current.includes(version));

    // `FileMigrationService.migrate` refuses a document declaring a version it does not know,
    // because such a document came from a newer platform. That is the whole of the difference
    // between the two kinds of archive here.
    if (archive.supported) expect(beyond).toEqual([]);
    else expect(beyond.length).toBeGreaterThan(0);
  });
}

test("the corpus covers every distinct chain floor the samples declare", { tag: ["@infra", "@tier1"] }, () => {
  const floors = sampleFloors("chain");

  // The measured distribution, recomputed on every run rather than quoted. `__SHOULD_FAIL`
  // documents are excluded, which is why `[100, 101, 108]` counts 1 here and 2 in the raw
  // directory: `chain/group__SHOULD_FAIL.yaml` is the other document at that floor, so the
  // distribution and the exclusion are not independent facts.
  expect(Object.fromEntries(floors)).toEqual({
    "[100, 101]": 37,
    "[100, 101, 102, 103]": 1,
    "[100, 101, 108]": 1,
    [floorKey([...Array.from({ length: 30 }, (_, index) => index + 1), 100, 101, 102, 103, 104, 105])]: 1,
  });

  expect([...floors.keys()].sort()).toEqual(
    FROZEN_ARCHIVES.filter((archive) => archive.kind === "chain")
      .map((archive) => floorKey(archive.floor))
      .sort(),
  );
});

test("the corpus covers every distinct service floor the samples declare", { tag: ["@infra", "@tier1"] }, () => {
  expect(Object.fromEntries(sampleFloors("service"))).toEqual({ "[100, 101]": 1 });
  expect([...sampleFloors("service").keys()]).toEqual(
    FROZEN_ARCHIVES.filter((archive) => archive.kind === "service").map((archive) =>
      floorKey(archive.floor),
    ),
  );
});

test("the MCP sample's envelope is not a valid MCP floor, so it is not frozen as one", { tag: ["@infra", "@tier1"] }, () => {
  // `[100, 101, 102]` appears on no chain document at all — it comes from the context-service and
  // MCP samples. Against the MCP list, which holds `V100` alone, it is a document from a newer
  // platform, so freezing it would freeze a refusal rather than a floor.
  expect(Object.fromEntries(sampleFloors("mcp-service"))).toEqual({ "[100, 101, 102]": 1 });
  expect(Object.fromEntries(sampleFloors("context-service"))).toEqual({ "[100, 101, 102]": 1 });

  const mcp = currentMigrationVersions("mcp");
  expect([100, 101, 102].filter((version) => !mcp.includes(version))).toEqual([101, 102]);
  // And nothing is frozen at that floor. `archive.kind` is `"chain" | "service"`, so asking whether
  // it holds `"mcp"` asks a question the type has already answered; the floor is what a new archive
  // would actually carry.
  expect(
    FROZEN_ARCHIVES.filter((archive) => floorKey(archive.floor) === floorKey([100, 101, 102])).map(
      (archive) => archive.name,
    ),
    "an archive is frozen at a floor the MCP migration list refuses, so it pins a refusal",
  ).toEqual([]);
});
