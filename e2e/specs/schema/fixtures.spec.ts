/**
 * The fixture trees: templating, archive layout, and validity against the tracked schemas.
 *
 * No stack. Everything here is a pure function over files this repository tracks, which is the
 * point — a fixture that does not validate breaks the seed project minutes into a run, and this
 * spec catches it in under a second.
 *
 * Both tracked directories, not just `fixtures/chains/`: the seed imports `fixtures/script/` too,
 * so a fixture there costs the same run if it is wrong. The generated `fixtures/axes/` is left out
 * because it is absent in a fresh clone, and the generator validates what it writes.
 */
import fs from "node:fs";
import path from "node:path";
import { test, expect } from "@playwright/test";
import yaml from "js-yaml";
import JSZip from "jszip";
import { QIP_MODEL_DIR } from "../../registry/discriminators.js";
import {
  SCRIPT_FIXTURE_DIR,
  TRACKED_FIXTURE_DIRS,
  assembleArchive,
  assembleFixture,
  assertSubstituted,
  chainFixtureNames,
  readFixtureDocument,
  renderFixture,
  substitute,
} from "../../fixtures/templating.js";
import { EXPORTED_CHAIN_SCHEMA, schemaValidator, validateDocument } from "../../fixtures/validate.js";
import { EXAMPLE_RUN_TOKEN } from "../../support/run.js";

const RUN = EXAMPLE_RUN_TOKEN;

interface FixtureElement {
  name?: unknown;
  properties?: { script?: unknown };
  children?: FixtureElement[];
}

/** Every `script` property a document carries, keyed by the element name, children included. */
function scriptsOf(document: Record<string, unknown>): Array<[string, string]> {
  const content = (document.content ?? {}) as { elements?: FixtureElement[] };
  const found: Array<[string, string]> = [];
  const visit = (elements: readonly FixtureElement[]): void => {
    for (const element of elements) {
      const script = element.properties?.script;
      // Trimmed, because that is the key the engine caches on: two scripts differing only in
      // surrounding whitespace are one compiled class.
      if (typeof script === "string" && script.trim() !== "") {
        found.push([String(element.name), script.trim()]);
      }
      if (element.children) visit(element.children);
    }
  };
  visit(content.elements ?? []);
  return found;
}

/** Every tracked fixture with the directory it came from, so the two layouts read alike below. */
const fixtures = TRACKED_FIXTURE_DIRS.flatMap((dir) =>
  chainFixtureNames(dir).map((name) => ({ dir, name })),
);

test("the fixture set is non-empty", { tag: ["@infra", "@tier1"] }, () => {
  // A spec that iterates nothing passes for the worst possible reason, and every case below is a
  // loop over this list.
  expect(fixtures.length).toBeGreaterThan(0);
});

test("substitution replaces every occurrence and leaves nothing behind", { tag: ["@infra", "@tier1"] }, () => {
  expect(substitute("a-{{RUN}}-b-{{RUN}}", RUN)).toBe(`a-${RUN}-b-${RUN}`);

  for (const { dir, name } of fixtures) {
    const tree = renderFixture(dir, name, RUN);
    expect(tree.size, `${name} has files`).toBeGreaterThan(0);
    expect(() => assertSubstituted(tree)).not.toThrow();
  }
});

test("a fixture carries the run token, so it cannot collide with another run", { tag: ["@infra", "@tier1"] }, () => {
  // Not decoration: an HTTP trigger `contextPath` is a Camel route, and a second route on the same
  // path fails the deployment rather than the assertion.
  for (const { dir, name } of fixtures) {
    const rendered = [...renderFixture(dir, name, RUN).values()].join("\n");
    expect(rendered, `${name} substitutes the run token somewhere`).toContain(RUN);
  }
});

for (const { dir, name } of fixtures) {
  test(`fixture ${name} validates against the tracked schema source`, { tag: ["@infra", "@tier1"] }, () => {
    const tree = renderFixture(dir, name, RUN);
    const fixture = readFixtureDocument(name, tree);
    expect(validateDocument(fixture.document)).toEqual([]);
  });

  test(`fixture ${name} assembles into the archive layout the importer reads`, { tag: ["@infra", "@tier1"] }, async () => {
    const tree = renderFixture(dir, name, RUN);
    const fixture = readFixtureDocument(name, tree);
    const zip = await JSZip.loadAsync(await assembleFixture(name, RUN, dir));

    const entries = Object.values(zip.files).filter((f) => !f.dir).map((f) => f.name).sort();
    const expected = [
      `chains/${fixture.id}/${fixture.id}.chain.cip.yaml`,
      ...[...fixture.companions.keys()].map((rel) => `chains/${fixture.id}/${rel}`),
    ].sort();
    expect(entries).toEqual(expected);
  });
}

test("no two scripts under fixtures/script/ carry the same text", { tag: ["@infra", "@tier1"] }, () => {
  // The rule `registry/elements.ts` states, enforced where it costs nothing. The engine's
  // compiled-script cache is keyed on the trimmed source alone and one language bean serves every
  // per-chain Camel context, so two chains carrying identical script text share one compiled class
  // — and a per-chain reading is then satisfied by another chain's compilation. The comment line at
  // the top of each fixture script is what keeps them apart; this is what notices when one is
  // copied without it.
  //
  // Scoped to the Script corpus, which is the only directory whose specs make per-chain readings
  // about compilation. `fixtures/chains/` and `fixtures/axes/` already share script text across
  // six groups, and no spec there says anything a shared compilation could satisfy.
  const byText = new Map<string, string>();
  for (const name of chainFixtureNames(SCRIPT_FIXTURE_DIR)) {
    for (const [where, script] of scriptsOf(
      readFixtureDocument(name, renderFixture(SCRIPT_FIXTURE_DIR, name, RUN)).document,
    )) {
      const already = byText.get(script);
      expect(already, `${name}/${where} repeats the script text of ${already}`).toBeUndefined();
      byText.set(script, `${name}/${where}`);
    }
  }
  expect(byText.size, "no fixture under fixtures/script/ carries a script").toBeGreaterThan(0);
});

test("EXPORTED_CHAIN_SCHEMA is the chain schema's $id and the validator resolves it", { tag: ["@infra", "@tier1"] }, () => {
  // Validation looks a document's `$schema` up as a schema `$id`, with no rewriting in between.
  const chain = yaml.load(fs.readFileSync(path.join(QIP_MODEL_DIR, "chain.schema.yaml"), "utf-8")) as { $id?: unknown };
  expect(chain.$id).toBe(EXPORTED_CHAIN_SCHEMA);
  expect(schemaValidator().getSchema(EXPORTED_CHAIN_SCHEMA)).toBeDefined();
});

test("an unsubstituted {{RUN}} fails assembly, naming the entry", { tag: ["@infra", "@tier1"] }, async () => {
  const { dir, name } = fixtures[0];
  const tree = renderFixture(dir, name, RUN);
  const document = [...tree.keys()].find((rel) => !rel.includes("/") && rel.endsWith(".yaml"))!;
  tree.set(document, `${tree.get(document)!}\n# left behind: {{RUN}}\n`);

  await expect(assembleArchive(name, tree)).rejects.toThrow(/unsubstituted placeholders/);
  await expect(assembleArchive(name, tree)).rejects.toThrow(/\{\{RUN\}\}/);
});

test("validation names the field a corrupted fixture broke", { tag: ["@infra", "@tier1"] }, () => {
  // The mutation check, kept in the suite rather than run once by hand: a validator that reports
  // nothing is indistinguishable from a fixture set that is correct.
  const { dir, name } = fixtures[0];
  const tree = renderFixture(dir, name, RUN);
  const fixture = readFixtureDocument(name, tree);

  const corrupted = structuredClone(fixture.document) as {
    content: { elements: Array<Record<string, unknown>> };
  };
  corrupted.content.elements[0].name = 42;

  const failures = validateDocument(corrupted);
  expect(failures.length).toBeGreaterThan(0);
  expect(failures.map((f) => f.instancePath)).toContain("/content/elements/0/name");
});
