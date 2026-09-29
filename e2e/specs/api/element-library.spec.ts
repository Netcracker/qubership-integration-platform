/**
 * The catalog's element library against the element schemas.
 *
 * Two enumerations of the same thing exist, maintained by different hands: the library the catalog
 * serves to the editor, and the schema tree every other spec in this suite enumerates from. Where
 * they disagree, a whole element is invisible to the coverage registry, which walks the schemas, so
 * an element the library offers with no schema behind it is a hole it can never see on its own.
 * This spec is the only reading that looks from the other side.
 *
 * **Measure the shape before comparing.** `GET /v1/library` answers three keys and only one of them
 * is the obvious one. `elements` is an empty array, so a comparison reading it alone finds zero
 * names and passes vacuously. `groups[].elements[]` carries the top-level entries and misses the
 * children, which leaves a symmetric difference in the thirties. The names are the union of the
 * group entries and the keys of `childElements`, which is a **dict** rather than a list.
 *
 * The library's `type` field is not the identity: `catch-2` is served with `type: "module"`. The
 * name is, and it is what the schema files are named after.
 *
 * Only the contract is asserted here: this spec calls `GET /v1/library` and asserts the element set
 * and nothing else.
 */
import { test, expect } from "../../support/fixtures.js";
import { CHILD_ELEMENT_FLOOR, GROUPED_ELEMENT_FLOOR } from "../../support/library.js";
import { SUPERSEDED, elementRegistry, supersededReason } from "../../registry/elements.js";
import {
  ELEMENT_SCHEMA_DIR,
  collectElementSchemaFiles,
  elementNameOf,
} from "../../registry/discriminators.js";

/**
 * The mutation seam, the same one `coverage.spec.ts` uses. It proves the schema
 * direction goes red when the tree grows an element the library does not offer, and nothing may
 * write into `schemas/` to arrange it.
 *
 * The path is resolved against Playwright's working directory, which is `e2e/`, so it is written
 * without one — an `e2e/` prefix here throws ENOENT before a single case runs:
 *
 *     cd e2e && E2E_ELEMENT_SCHEMA_DIR=fixtures/schema-mutation npx playwright test --project=api
 */
const schemaDir = process.env.E2E_ELEMENT_SCHEMA_DIR ?? ELEMENT_SCHEMA_DIR;

/**
 * Library entries with no schema at all: legacy element types the editor still offers.
 *
 * They are the reason this spec exists. Every one of them is invisible to a reading that enumerates
 * the schema tree, and the list is short enough to carry literally rather than behind a pattern —
 * a pattern would absorb the eleventh silently, which is the event worth failing on.
 */
const LIBRARY_ONLY = [
  "chain-call",
  "chain-trigger",
  "kafka",
  "kafka-sender",
  "mapper",
  "rabbitmq",
  "rabbitmq-sender",
  "scheduler",
  "sftp-trigger",
  "unsupported",
];

/**
 * Schemas the library does not offer as an element.
 *
 * `container` is a structural base rather than a placeable element; `scs-sender` and `sds-trigger`
 * describe elements this build does not serve. `element.schema.yaml` is not among them because it
 * is never in the list to begin with — `collectElementSchemaFiles` excludes it exactly as
 * `SchemaResolver` does. Include it and `element` reads as a spurious fourth name here, since it is
 * the abstract base every element `allOf`s rather than an element type.
 */
const SCHEMA_ONLY = ["container", "scs-sender", "sds-trigger"];

interface LibraryElement {
  name: string;
  type: string;
  deprecated: boolean;
}

interface Library {
  elements: LibraryElement[];
  groups: Array<{ name: string; elements: LibraryElement[] }>;
  childElements: Record<string, LibraryElement>;
}

function libraryElementNames(library: Library): string[] {
  return [
    ...library.groups.flatMap((group) => group.elements.map((element) => element.name)),
    ...Object.keys(library.childElements),
  ].sort();
}

function schemaElementNames(): string[] {
  return collectElementSchemaFiles(schemaDir).map(elementNameOf).sort();
}

test("the library's three keys, so the comparison below is not reading one of them alone", { tag: ["@catalog", "@tier1"] }, async ({ catalog }) => {
  const library = await catalog.call<Library>("get", "/v1/library");

  // Reading `elements` alone yields no names and a vacuous pass. It is empty on this build, and a
  // build that starts filling it has changed the shape this spec reduces.
  expect(library.elements, "top-level `elements` is empty; the names live elsewhere").toEqual([]);

  // Reading the groups alone drops the children and leaves a difference in the thirties.
  const grouped = library.groups.flatMap((group) => group.elements);
  expect(grouped.length).toBeGreaterThan(GROUPED_ELEMENT_FLOOR);

  // A dict, not a list. `Object.keys` on a list would answer indices and the comparison would then
  // report every child element as missing a schema.
  expect(Array.isArray(library.childElements)).toBe(false);
  expect(Object.keys(library.childElements).length).toBeGreaterThan(CHILD_ELEMENT_FLOOR);

  // The key is the element's name, not its `type`: `catch-2` is served with `type: "module"`.
  for (const [key, child] of Object.entries(library.childElements)) {
    expect(child.name, `childElements key ${key}`).toBe(key);
  }

  // The union is longer than the groups alone, and this is what says so: `libraryElementNames`
  // concatenates without dedup, so a length comparison against `grouped.length` is arithmetic on
  // the line above rather than a reading. What the two cases below depend on is that the children
  // carry names the groups do not, and only a set difference can report that.
  const groupedNames = new Set(grouped.map((element) => element.name));
  const childrenOnly = Object.keys(library.childElements).filter(
    (name) => !groupedNames.has(name),
  );
  expect(
    childrenOnly.length,
    "every child element is also a top-level group entry, so reading the groups alone would lose " +
      "nothing and the difference this spec was written around is gone",
  ).toBeGreaterThan(20);
});

test("every library entry that is not schema-less legacy has a schema", { tag: ["@catalog", "@tier1"] }, async ({ catalog }) => {
  const library = await catalog.call<Library>("get", "/v1/library");
  const schemas = new Set(schemaElementNames());

  // This case's own floor, and it has to be here rather than in the shape case above: `[].filter()`
  // is `[]`, so a library that answered nothing would satisfy the assertion below while claiming
  // that every element it offers is documented.
  const offered = libraryElementNames(library);
  expect(offered.length, "the library offered nothing, so it has nothing to document").toBeGreaterThan(50);

  const undocumented = offered
    .filter((name) => !schemas.has(name))
    .filter((name) => !LIBRARY_ONLY.includes(name));

  expect(undocumented, "library elements with no schema, beyond the known legacy set").toEqual([]);
});

test("every schema is a library entry, beyond the three the library does not offer", { tag: ["@catalog", "@tier1"] }, async ({ catalog }) => {
  const library = await catalog.call<Library>("get", "/v1/library");
  const offered = new Set(libraryElementNames(library));

  // This case's own floor, over its own input. Non-emptiness rather than a count: the mutation seam
  // points `schemaDir` at a directory holding a single probe schema, and a floor sized on the real
  // tree would fail there for the wrong reason instead of reporting the probe as unoffered.
  const schemas = schemaElementNames();
  expect(
    schemas,
    `no element schemas were collected from ${schemaDir}, so "every schema is offered" is a claim ` +
      `about nothing`,
  ).not.toEqual([]);

  const unoffered = schemas
    .filter((name) => !offered.has(name))
    .filter((name) => !SCHEMA_ONLY.includes(name));

  expect(unoffered, "element schemas the library does not offer").toEqual([]);
});

test("both exception lists are still exceptions", { tag: ["@catalog", "@tier1"] }, async ({ catalog }) => {
  // An allowlist that has stopped matching is the failure mode of an allowlist: it hides the day a
  // legacy element grows a schema, or a missing one starts being served, and both are news.
  const library = await catalog.call<Library>("get", "/v1/library");
  const offered = new Set(libraryElementNames(library));
  const schemas = new Set(schemaElementNames());

  expect(LIBRARY_ONLY.filter((name) => !offered.has(name)), "no longer in the library").toEqual([]);
  expect(LIBRARY_ONLY.filter((name) => schemas.has(name)), "now has a schema").toEqual([]);
  expect(SCHEMA_ONLY.filter((name) => !schemas.has(name)), "no longer a schema").toEqual([]);
  expect(SCHEMA_ONLY.filter((name) => offered.has(name)), "now offered by the library").toEqual([]);
});

test("the registry's superseded map is the library's deprecated set, and every deprecated row says so", { tag: ["@catalog", "@tier1"] }, async ({ catalog }) => {
  // The flag the product publishes, not a `(deprecated)` title suffix: that suffix marks 18 schemas,
  // misses the schema-less entries the library still offers, and says nothing on `on-fallback`.
  const library = await catalog.call<Library>("get", "/v1/library");
  const byName = new Map(
    [...library.groups.flatMap((group) => group.elements), ...Object.values(library.childElements)].map((element) => [element.name, element]),
  );
  const deprecated = [...byName.values()].filter((element) => element.deprecated).map((element) => element.name);
  expect(new Set(deprecated).size, "the library flags nothing deprecated, so the map is compared against nothing").toBeGreaterThan(20);
  expect(Object.keys(SUPERSEDED).sort(), "SUPERSEDED against the library's deprecated flag").toEqual([...new Set(deprecated)].sort());

  for (const [family, successor] of Object.entries(SUPERSEDED)) {
    if (successor === null) continue;
    expect(byName.get(successor)?.deprecated, `${family}'s successor ${successor} is offered and current`).toBe(false);
  }

  // A deprecated row is covered where a test still runs it, and otherwise carries the superseded reason.
  const stray = elementRegistry
    .filter((entry) => entry.family in SUPERSEDED && entry.status === "not-covered")
    .filter((entry) => entry.reason !== supersededReason(entry.family))
    .map((entry) => `${entry.family} ${entry.axisPath ?? ""}`.trim());
  expect(stray, "deprecated rows with a reason other than the superseded one").toEqual([]);
});
