/**
 * The fixture trees: templating, archive layout, and the references between fixtures. The layout
 * check covers the frozen archives too.
 *
 * No stack. Everything here is a pure function over files this repository tracks, which is the
 * point: a fixture that does not assemble breaks the seed project minutes into a run, and this spec
 * catches it in under a second. `specs/schema/fixtures.spec.ts` validates the same fixtures against
 * the schema sources.
 */
import path from "node:path";
import { test, expect } from "@playwright/test";
import JSZip from "jszip";
import { FROZEN_ARCHIVES, FROZEN_ARCHIVE_DIR } from "../../fixtures/archives/frozen.js";
import {
  BROKER_SERVICE_FIXTURE_DIR,
  DOCUMENT_KINDS,
  SCRIPT_FIXTURE_DIR,
  SERVICE_FIXTURE_DIR,
  TRACKED_FIXTURE_DIRS,
  assembleArchive,
  assembleFixture,
  assertSubstituted,
  chainFixtureNames,
  corpusFixtureNames,
  elementsOf,
  readFixtureDocument,
  readServiceFixture,
  renderFixture,
  substitute,
  type FixtureElement as ChainElement,
} from "../../fixtures/templating.js";
import { SERVICE_IMPORTS, UNRECORDED_FIXTURES, seedLogging } from "../../support/corpus.js";
import { validateDocument } from "../../fixtures/validate.js";
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

test.describe("fixtures", () => {
  test("substitution leaves no placeholder in any fixture", () => {
    expect(substitute("a-{{RUN}}-b-{{RUN}}", RUN)).toBe(`a-${RUN}-b-${RUN}`);

    for (const { dir, name } of fixtures) {
      const tree = renderFixture(dir, name, RUN);
      expect(tree.size, `${name} has files`).toBeGreaterThan(0);
      expect(() => assertSubstituted(tree)).not.toThrow();
    }
  });

  test("every fixture carries the run token", () => {
    // Without it two runs collide: an HTTP trigger `contextPath` is a Camel route, and a second route on the same
    // path fails the deployment rather than the assertion.
    for (const { dir, name } of fixtures) {
      const rendered = [...renderFixture(dir, name, RUN).values()].join("\n");
      expect(rendered, `${name} substitutes the run token somewhere`).toContain(RUN);
    }
  });

  // The frozen archives are imported through the same assembler, so they get the same check.
  const assembled = [...fixtures, ...FROZEN_ARCHIVES.map(({ name }) => ({ dir: FROZEN_ARCHIVE_DIR, name }))];

  for (const { dir, name } of assembled) {
    test(`${path.basename(dir)}/${name} assembles into the archive layout the importer reads`, async () => {
      const tree = renderFixture(dir, name, RUN);
      const fixture = readFixtureDocument(name, tree);
      const zip = await JSZip.loadAsync(await assembleFixture(name, RUN, dir));

      const entries = Object.values(zip.files).filter((f) => !f.dir).map((f) => f.name).sort();
      const parent = `${DOCUMENT_KINDS[fixture.kind].parentDir}/${fixture.id}`;
      const expected = [
        `${parent}/${fixture.id}.${fixture.kind}.cip.yaml`,
        ...[...fixture.companions.keys()].map((rel) => `${parent}/${rel}`),
      ].sort();
      // Full paths: an entry at the zip root imports nothing and answers success.
      expect(entries).toEqual(expected);
    });
  }

  test("every unrecorded fixture is seeded with session logging off", () => {
    // A name the seed does not read leaves the renamed fixture at DEBUG, the setup that ran the
    // chart's OpenSearch out of memory.
    const seeded = corpusFixtureNames();
    for (const name of UNRECORDED_FIXTURES) {
      expect(seeded, `${name} names no fixture the seed reads`).toContain(name);
      expect(seedLogging(name).sessionsLoggingLevel, name).toBe("OFF");
    }
  });

  test("no two scripts in one fixtures/script/ chain share text", () => {
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

  test.describe("service references", () => {
    const SERVICE_DIRS = [SERVICE_FIXTURE_DIR, BROKER_SERVICE_FIXTURE_DIR];

    /** Every service fixture rendered, with its kind. */
    const serviceDocuments = SERVICE_DIRS.flatMap((dir) =>
      chainFixtureNames(dir).map((service) => {
        const tree = renderFixture(dir, service, RUN);
        return { service, tree, ...readFixtureDocument(service, tree) };
      }),
    );

    /** The elements of every tracked chain fixture, by fixture name. */
    function trackedChainElements(): Array<[string, ChainElement]> {
      return fixtures
        .filter(({ dir }) => !SERVICE_DIRS.includes(dir))
        .flatMap(({ dir, name }) => {
          const { document } = readFixtureDocument(name, renderFixture(dir, name, RUN));
          return elementsOf(document).map((element) => [name, element] as [string, ChainElement]);
        });
    }

    test("every operation a chain names is in a service fixture", () => {
      // A seeded service call or trigger compiles against the service the seed imports, so an id that
      // names none imports cleanly and fails the deploy minutes into a run.
      const operations = new Map(
        serviceDocuments
          .filter((each) => each.kind === "service")
          .flatMap(({ service, tree }) => {
            const fixture = readServiceFixture(service, tree);
            return fixture.operations.map((operation) => [operation.id, { ...operation, systemId: fixture.id }] as const);
          }),
      );
      const references = { "service-call": 0, "async-api-trigger": 0, "http-trigger": 0 };
      for (const [name, element] of trackedChainElements()) {
        const properties = element.properties ?? {};
        const type = element.type;
        if (
          type !== "service-call" &&
          type !== "async-api-trigger" &&
          !(type === "http-trigger" && properties.integrationOperationId !== undefined)
        ) {
          continue;
        }
        const operation = operations.get(String(properties.integrationOperationId));
        expect(operation, `${name}/${String(element.name)} names no operation of a service fixture`).toBeDefined();
        const method = type === "http-trigger" ? properties.httpMethodRestrict : properties.integrationOperationMethod;
        expect(
          {
            integrationSystemId: properties.integrationSystemId,
            integrationSpecificationGroupId: properties.integrationSpecificationGroupId,
            integrationSpecificationId: properties.integrationSpecificationId,
            integrationOperationPath: properties.integrationOperationPath,
            method,
          },
          `${name}/${String(element.name)}`,
        ).toEqual({
          integrationSystemId: operation!.systemId,
          integrationSpecificationGroupId: operation!.groupId,
          integrationSpecificationId: operation!.specificationId,
          integrationOperationPath: operation!.path,
          method: operation!.method,
        });
        references[type]++;
      }
      expect(references["service-call"], "no tracked chain carries a service call").toBeGreaterThan(0);
      expect(references["async-api-trigger"], "no tracked chain carries an async-api trigger").toBeGreaterThan(0);
      expect(references["http-trigger"], "no tracked chain carries an implementing trigger").toBeGreaterThan(0);
    });

    test("every context or MCP service named is a fixture of its kind", () => {
      const ofKind = (kind: string) => new Set(serviceDocuments.filter((each) => each.kind === kind).map((each) => each.id));
      const contexts = ofKind("context-service");
      const mcps = ofKind("mcp-service");
      let named = 0;
      for (const [name, element] of trackedChainElements()) {
        const properties = element.properties ?? {};
        const where = `${name}/${String(element.name)}`;
        if (properties.contextServiceId !== undefined) {
          expect(contexts.has(String(properties.contextServiceId)), `${where} names no context service fixture`).toBe(true);
          named++;
        }
        for (const id of (properties.mcpServiceIds as string[] | undefined) ?? []) {
          expect(mcps.has(id), `${where} names no MCP service fixture`).toBe(true);
          named++;
        }
      }
      expect(named, "no tracked chain names a context or MCP service").toBeGreaterThan(0);
    });

    test("the seed imports every kind of service fixture", () => {
      expect(serviceDocuments.filter((each) => SERVICE_IMPORTS[each.kind] === undefined).map((each) => each.service)).toEqual([]);
    });
  });

  test("an unsubstituted {{RUN}} fails assembly, naming the entry", async () => {
    const { dir, name } = fixtures[0];
    const tree = renderFixture(dir, name, RUN);
    const document = [...tree.keys()].find((rel) => !rel.includes("/") && rel.endsWith(".yaml"))!;
    tree.set(document, `${tree.get(document)!}\n# left behind: {{RUN}}\n`);

    await expect(assembleArchive(name, tree)).rejects.toThrow(/unsubstituted placeholders/);
    await expect(assembleArchive(name, tree)).rejects.toThrow(/\{\{RUN\}\}/);
  });

  test("validation names the field a corrupted fixture broke", () => {
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
});
