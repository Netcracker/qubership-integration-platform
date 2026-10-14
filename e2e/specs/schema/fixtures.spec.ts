/**
 * The fixture trees: templating, archive layout, and validity against the tracked schemas.
 *
 * No stack. Everything here is a pure function over files this repository tracks, which is the
 * point — a fixture that does not validate breaks the seed project minutes into a run, and this
 * spec catches it in under a second.
 *
 * Every tracked directory, not just `fixtures/chains/`: the seed imports `fixtures/script/` and
 * `fixtures/services/` too, so a fixture there costs the same run if it is wrong. The generated `fixtures/axes/` is left out
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
  SERVICE_FIXTURE_DIR,
  TRACKED_FIXTURE_DIRS,
  assembleArchive,
  assembleFixture,
  assertSubstituted,
  chainFixtureNames,
  readFixtureDocument,
  readServiceFixture,
  renderFixture,
  substitute,
} from "../../fixtures/templating.js";
import { flatten, type FixtureElement as ChainElement } from "../../support/corpus.js";
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
    // A service's specification group and specifications travel beside it, each with a schema of its own.
    for (const [rel, source] of fixture.companions) {
      if (!rel.endsWith(".cip.yaml")) continue;
      expect(validateDocument(yaml.load(source)), rel).toEqual([]);
    }
  });

  test(`fixture ${name} assembles into the archive layout the importer reads`, { tag: ["@infra", "@tier1"] }, async () => {
    const tree = renderFixture(dir, name, RUN);
    const fixture = readFixtureDocument(name, tree);
    const zip = await JSZip.loadAsync(await assembleFixture(name, RUN, dir));

    const entries = Object.values(zip.files).filter((f) => !f.dir).map((f) => f.name).sort();
    const parent = `${fixture.kind}s/${fixture.id}`;
    const expected = [
      `${parent}/${fixture.id}.${fixture.kind}.cip.yaml`,
      ...[...fixture.companions.keys()].map((rel) => `${parent}/${rel}`),
    ].sort();
    expect(entries).toEqual(expected);
  });
}

test("no two scripts in one chain under fixtures/script/ carry the same text", { tag: ["@infra", "@tier1"] }, () => {
  // The rule `registry/elements.ts` states, enforced where it costs nothing. The engine keys a
  // compiled script on its trimmed source, in a cache that belongs to the chain's Camel context, so
  // two scripts in one chain with identical text share one compiled class, and a reading about one
  // of them is satisfied by the compilation of the other. The comment line at the top of each
  // fixture script keeps them apart; this check notices when one is copied without it.
  //
  // Scoped to the Script corpus, which is the only directory whose specs make readings about
  // compilation.
  let scripts = 0;
  for (const name of chainFixtureNames(SCRIPT_FIXTURE_DIR)) {
    const byText = new Map<string, string>();
    for (const [where, script] of scriptsOf(
      readFixtureDocument(name, renderFixture(SCRIPT_FIXTURE_DIR, name, RUN)).document,
    )) {
      const already = byText.get(script);
      expect(already, `${name}/${where} repeats the script text of ${already}`).toBeUndefined();
      byText.set(script, `${name}/${where}`);
    }
    scripts += byText.size;
  }
  expect(scripts, "no fixture under fixtures/script/ carries a script").toBeGreaterThan(0);
});

test("every service call in a tracked chain names an operation of a service fixture", { tag: ["@infra", "@tier1"] }, () => {
  // A seeded service call compiles against the service the seed imports, so an id that names none
  // imports cleanly and fails the deploy minutes into a run.
  const operations = new Map(
    chainFixtureNames(SERVICE_FIXTURE_DIR).flatMap((service) => {
      const fixture = readServiceFixture(service, renderFixture(SERVICE_FIXTURE_DIR, service, RUN));
      return fixture.operations.map((operation) => [operation.id, { ...operation, systemId: fixture.id }] as const);
    }),
  );
  let calls = 0;
  for (const { dir, name } of fixtures.filter(({ dir }) => dir !== SERVICE_FIXTURE_DIR)) {
    const { document } = readFixtureDocument(name, renderFixture(dir, name, RUN));
    const elements = flatten(((document.content as { elements?: ChainElement[] })?.elements) ?? []);
    for (const element of elements.filter((each) => each.type === "service-call")) {
      const properties = element.properties ?? {};
      const operation = operations.get(String(properties.integrationOperationId));
      expect(operation, `${name}/${String(element.name)} calls no operation of a service fixture`).toBeDefined();
      expect(
        {
          integrationSystemId: properties.integrationSystemId,
          integrationSpecificationGroupId: properties.integrationSpecificationGroupId,
          integrationSpecificationId: properties.integrationSpecificationId,
          integrationOperationPath: properties.integrationOperationPath,
          integrationOperationMethod: properties.integrationOperationMethod,
        },
        `${name}/${String(element.name)}`,
      ).toEqual({
        integrationSystemId: operation!.systemId,
        integrationSpecificationGroupId: operation!.groupId,
        integrationSpecificationId: operation!.specificationId,
        integrationOperationPath: operation!.path,
        integrationOperationMethod: operation!.method,
      });
      calls++;
    }
  }
  expect(calls, "no tracked chain carries a service call").toBeGreaterThan(0);
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
