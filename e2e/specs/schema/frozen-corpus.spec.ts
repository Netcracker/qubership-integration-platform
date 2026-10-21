/**
 * The migration floors of the frozen corpus, read against the migration classes and the samples.
 *
 * `sampleFloors` recomputes the distribution over `schemas/src/test/resources/samples/` on every
 * run, so a sample that gains a new declared list turns this file red instead of leaving a floor
 * silently uncovered, and a new migration class shows up here before an import refuses a document.
 * `specs/harness/frozen-corpus.spec.ts` checks that the archives are still the ones that were frozen.
 */
import { test, expect } from "@playwright/test";
import { FROZEN_ARCHIVES, currentMigrationVersions, floorKey, sampleFloors } from "../../fixtures/archives/frozen.js";

test.describe("frozen corpus floors against migrations and samples", { tag: ["@schema"] }, () => {
  test("each document kind has its own migration list", () => {
    // The lists are read off the class names, so a `V109` landing in the tree shows up here rather
    // than in a runtime failure months later.
    expect(currentMigrationVersions("chain")).toEqual([100, 101, 102, 103, 104, 105, 106, 107, 108]);
    expect(currentMigrationVersions("service")).toEqual([100, 101, 102]);
    expect(currentMigrationVersions("mcp")).toEqual([100]);
  });

  for (const archive of FROZEN_ARCHIVES) {
    test(`${archive.name} is ${archive.supported ? "within" : "beyond"} the current list`, () => {
      const current = currentMigrationVersions(archive.kind === "chain" ? "chain" : "service");
      const beyond = archive.floor.filter((version) => !current.includes(version));

      // `FileMigrationService.migrate` refuses a document declaring a version it does not know,
      // because such a document came from a newer platform. That is the whole of the difference
      // between the two kinds of archive here.
      if (archive.supported) expect(beyond).toEqual([]);
      else expect(beyond.length).toBeGreaterThan(0);
    });
  }

  test("the corpus covers every chain floor the samples declare", () => {
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

  test("the corpus covers every service floor the samples declare", () => {
    expect(Object.fromEntries(sampleFloors("service"))).toEqual({ "[100, 101]": 1 });
    expect([...sampleFloors("service").keys()]).toEqual(
      FROZEN_ARCHIVES.filter((archive) => archive.kind === "service").map((archive) =>
        floorKey(archive.floor),
      ),
    );
  });

  test("the MCP sample floor is refused and frozen nowhere", () => {
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
});
