/**
 * The `restartWith` record: the one handle on a container running settings no committed file carries.
 *
 * `env/compose.ts` writes the record before the recreate that applies the settings and drops it after
 * the restart that puts the committed ones back, and `env/provision/compose.ts` recreates whatever
 * is still recorded. The whole mechanism turns on a single reading — what a file that will not parse means —
 * and the dangerous answer is the tidy one: read as empty, a half-written record hands provisioning a
 * stack it wrongly believes is clean, and the next run's `service-type-roundtrip` aborts on a flag
 * nobody set.
 *
 * No stack. Every function here takes the file as a parameter, so each case runs against a file of
 * its own in a temporary directory.
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test, expect } from "@playwright/test";
import {
  describeOverride,
  forgetOverride,
  overrideNotice,
  readOverrides,
  recordOverride,
  type ServiceOverride,
} from "../../env/overrides.js";
import { EXAMPLE_RUN_TOKEN } from "../../support/run.js";
import { whileCollectingErrors } from "../../support/console-errors.js";

/** A file path in a directory of this case's own, so nothing here can read another case's record. */
function overridesFile(): string {
  return path.join(fs.mkdtempSync(path.join(os.tmpdir(), "e2e-overrides-")), ".e2e-overrides.json");
}

test("a stack nobody has restarted is a missing file, and it reads as no overrides", { tag: ["@infra", "@tier1"] }, async () => {
  expect(await readOverrides(overridesFile())).toEqual({});
});

test("a recorded override names its settings, its run and its time", { tag: ["@infra", "@tier1"] }, async () => {
  const file = overridesFile();
  const original = process.env.E2E_RUN;
  process.env.E2E_RUN = EXAMPLE_RUN_TOKEN;
  try {
    await recordOverride("runtime-catalog", { CIP_EXPORT_LEGACY_FORMAT: "true" }, file);
  } finally {
    if (original === undefined) delete process.env.E2E_RUN;
    else process.env.E2E_RUN = original;
  }

  const overrides = await readOverrides(file);
  expect(Object.keys(overrides)).toEqual(["runtime-catalog"]);
  expect(overrides["runtime-catalog"].settings).toEqual({ CIP_EXPORT_LEGACY_FORMAT: "true" });
  // The run is the half a report can act on: it names who left the container dirty.
  expect(overrides["runtime-catalog"].run).toBe(EXAMPLE_RUN_TOKEN);
  expect(Date.parse(overrides["runtime-catalog"].at)).not.toBeNaN();

  // The write goes through a staging file and a rename, and the staging file is not residue of its
  // own: a `.tmp` left in the directory is a second record nothing reads and nothing clears.
  expect(fs.readdirSync(path.dirname(file))).toEqual([path.basename(file)]);
});

test("a truncated record is read for the services it still names, not as a clean stack", { tag: ["@infra", "@tier1"] }, async () => {
  const file = overridesFile();
  // A write cut halfway: the role is there, the settings object is not closed. Read as `{}`, the
  // catalog keeps `CIP_EXPORT_LEGACY_FORMAT=true` and provisioning recreates nothing.
  fs.writeFileSync(file, '{\n  "runtime-catalog": {\n    "settings": { "CIP_EXPORT_LEGACY');

  let salvaged: Record<string, ServiceOverride> = {};
  const said = await whileCollectingErrors(async () => {
    salvaged = await readOverrides(file);
  });

  expect(Object.keys(salvaged)).toEqual(["runtime-catalog"]);
  // The settings are dropped on purpose: the restore is a recreate from the committed configuration,
  // which discards whatever the container was started with either way.
  expect(salvaged["runtime-catalog"].settings).toEqual({});
  expect(said.join("\n")).toContain("does not parse");
  expect(said.join("\n")).toContain("Restoring runtime-catalog");
});

test("a record naming no service says so rather than passing for a clean stack", { tag: ["@infra", "@tier1"] }, async () => {
  const file = overridesFile();
  fs.writeFileSync(file, "{");

  let salvaged: Record<string, ServiceOverride> = { placeholder: { settings: {}, at: "now" } };
  const said = await whileCollectingErrors(async () => {
    salvaged = await readOverrides(file);
  });

  // Empty, and loud. There is nothing to recreate, and that is the one case where an empty answer
  // and a clean stack are the same reading — so the line is what separates them for the reader.
  expect(salvaged).toEqual({});
  expect(said.join("\n")).toContain("It names no service");
});

test("forgetting a service rewrites a file that would not parse", { tag: ["@infra", "@tier1"] }, async () => {
  const file = overridesFile();
  fs.writeFileSync(file, '{ "runtime-catalog": {}, "engine": ');

  await whileCollectingErrors(async () => {
    await forgetOverride("runtime-catalog", file);
  });

  // The salvaged record is written back as JSON, so the next read is an ordinary parse rather than a
  // second salvage — and the service that was not restored is still recorded.
  expect(JSON.parse(fs.readFileSync(file, "utf-8"))).toEqual({
    engine: { settings: {}, at: "an unrecorded time" },
  });
  expect(await readOverrides(file)).toEqual({ engine: { settings: {}, at: "an unrecorded time" } });
});

test("a file that cannot be read fails the run rather than reading as no overrides", { tag: ["@infra", "@tier1"] }, async () => {
  // A directory in the file's place stands for every read failure that is not "no file": a
  // permission, a mount that went away, a name something else took. Each is a fact about a file that
  // exists, and swallowing it reports a stack nobody has restarted.
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "e2e-overrides-"));
  await expect(readOverrides(directory)).rejects.toThrow();
});

test("an override is described by what it left behind, even with no settings recorded", { tag: ["@infra", "@tier1"] }, () => {
  const at = "2026-09-09T10:00:00.000Z";
  expect(describeOverride("qip-runtime-catalog", { settings: { A: "1", B: "2" }, run: EXAMPLE_RUN_TOKEN, at }))
    .toBe(`A, B on qip-runtime-catalog, left by run ${EXAMPLE_RUN_TOKEN} at ${at}`);

  // The salvaged shape: the role survived a file that would not parse and the settings did not. The
  // reader gets a sentence rather than `clearing , left on qip-engine`, which reads as a bug in the
  // provisioner instead of as a record it could not fully recover.
  const salvaged = describeOverride("qip-engine", { settings: {}, at: "an unrecorded time" });
  expect(salvaged).toContain("settings the record no longer names");
  expect(salvaged).toContain("run unknown");
});

test("the mode that recreates nothing still names the record and the run that left it", { tag: ["@infra", "@tier1"] }, () => {
  const notice = overrideNotice(
    "qip-runtime-catalog",
    { settings: { CIP_EXPORT_LEGACY_FORMAT: "true" }, run: EXAMPLE_RUN_TOKEN, at: "2026-09-09T10:00:00.000Z" },
    "/repo/e2e/.e2e-overrides.json",
  );
  // The recovery handle is on disk and nothing else points the reader at it: under
  // `E2E_PROVISION=never` the symptom is `service-type-roundtrip` refusing to start on a flag the
  // developer never set.
  expect(notice).toContain("/repo/e2e/.e2e-overrides.json");
  expect(notice).toContain(`run ${EXAMPLE_RUN_TOKEN}`);
  expect(notice).toContain("CIP_EXPORT_LEGACY_FORMAT");
  expect(notice).toContain("E2E_PROVISION unset");
});

test("a file that parses into something that is not a record takes the same salvage path", { tag: ["@infra", "@tier1"] }, async () => {
  // Syntax is not the only way to be wrong, and each of these is wrong in its own way. `[]` reads as
  // a clean stack over a container still running what a killed run set; the other three throw inside
  // provisioning, which is the crash the salvage path exists to replace.
  const wrong: Array<[string, string]> = [
    ["[]", "a list"],
    ["null", "null"],
    ['{ "runtime-catalog": null }', "not an object"],
    ['{ "runtime-catalog": { "at": "2026-09-09T10:00:00.000Z" } }', "names no settings"],
    ['{ "runtime-catalog": { "settings": {} } }', "names no time"],
  ];

  for (const [text, reason] of wrong) {
    const file = overridesFile();
    fs.writeFileSync(file, text);

    let salvaged: Record<string, ServiceOverride> = {};
    const said = await whileCollectingErrors(async () => {
      salvaged = await readOverrides(file);
    });

    expect(said.join("\n"), text).toContain(reason);
    // And the reading is the salvaged one: every role the text still names is restored, and a text
    // naming none says so rather than passing for a stack nobody has restarted.
    expect(Object.keys(salvaged), text).toEqual(text.includes("runtime-catalog") ? ["runtime-catalog"] : []);
  }
});

test("a record naming settings, a time and a run is read as it stands", { tag: ["@infra", "@tier1"] }, async () => {
  const file = overridesFile();
  const at = "2026-09-09T10:00:00.000Z";
  fs.writeFileSync(
    file,
    JSON.stringify({ "runtime-catalog": { settings: { CIP_EXPORT_LEGACY_FORMAT: "true" }, run: EXAMPLE_RUN_TOKEN, at } }),
  );

  // The shape check has to let a good record through untouched: salvaging one would drop the
  // settings the provisioning log names, over a file with nothing wrong with it.
  const said = await whileCollectingErrors(async () => {
    expect(await readOverrides(file)).toEqual({
      "runtime-catalog": { settings: { CIP_EXPORT_LEGACY_FORMAT: "true" }, run: EXAMPLE_RUN_TOKEN, at },
    });
  });
  expect(said).toEqual([]);
});
