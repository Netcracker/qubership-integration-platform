/**
 * `element-library-controller`, whole: the palette, one descriptor by name, and the types in use.
 *
 * The set of elements `GET /v1/library` offers is **not** asserted here.
 * `specs/api/element-library.spec.ts` already reconciles it against the tracked schema tree under
 * `schemas/qip-model/element/`, and reading the same list twice would earn one row two ways. What
 * this spec asserts is the three endpoints' own behavior: how the hierarchy decides where an
 * element is served, what shape every entry carries, that the by-name read answers the same object,
 * and what makes the types listing a subset of the palette rather than a copy of it.
 *
 * `e2e/AGENTS.md` rule 2 is what puts the file in `global`. `GET /v1/library/elements/types` is
 * `ElementRepository.findAllGroupByType`, `SELECT e.type FROM elements e GROUP BY e.type`, with no
 * chain, snapshot, folder or caller predicate. Its answer changes whenever **any** worker creates or
 * deletes an element of a type nothing else is using, so the round-trip below would be asserting
 * over a list another worker is rewriting if this file sat in `api`.
 *
 * Five shapes measured against the stack rather than assumed:
 *
 * - **`parentRestriction` decides where an element is served.** `LibraryElementsService`
 *   `.getElementsHierarchy` puts an element in its folder's group when the restriction is empty and
 *   under the root's `childElements` map when it is not. That is the whole rule, it partitions the
 *   palette exactly, and it is why the root's own `elements` list is empty on this build: every
 *   element either has a folder or has a parent restriction.
 * - **`childElements` only ever exists at the root.** `addChild(String, ElementDescriptor)` is
 *   reached on `root` and on nothing else, so a group's own `childElements` is always empty and a
 *   reader that walks groups looking for children finds none.
 * - **The entry shape does not vary by section.** `ElementDescriptor` defaults twenty-three fields
 *   to non-null values, so Jackson writes all twenty-three on every element and only five keys are
 *   ever absent. A child element declares a `folder` like any other, even though `parentRestriction`
 *   is what decides it is never drawn in one.
 * - **The by-name read is a projection of the same map, not a second rendering.** Both endpoints
 *   read `LibraryElementsService.elements`, so the descriptor served alone is deep-equal to the one
 *   inside the tree — asserted over every element the palette offers, both partitions.
 * - **The name is the identity.** `GET /v1/library/{name}` looks up by descriptor name only, so a
 *   `type` (`module`, which `catch-2` is served with) and a folder name (`routing`) are both 404s.
 *   And the miss is a bare `ResponseEntity.notFound().build()`: **404 with an empty body**, not the
 *   `{serviceName, errorMessage, errorDate}` envelope the rest of the catalog answers with.
 *
 * **One platform defect, decided as won't fix, stands behind the types listing**:
 * `ElementLibraryController.findAllUsingElementsTypes` maps each type through
 * `libraryElementsService.getElementDescriptor(type)`, which throws `ElementNotFoundException` for
 * a type the library does not offer. The query is catalog-wide and spans snapshot elements as well
 * as chain elements, so a single row anywhere — including inside a snapshot of a chain nobody is
 * looking at — takes the endpoint down for the whole instance. It is not asserted as a `test.fail()`
 * because it is not reproducible on demand: no create path accepts an unknown type, so a spec
 * cannot manufacture the row, and pinning the 500 would pin whatever the stack happens to hold.
 * `presentableTypesInUse` below turns it into a failure that names itself instead.
 */
import { test, expect } from "../../support/fixtures.js";
import {
  CHILD_ELEMENT_FLOOR,
  GROUPED_ELEMENT_FLOOR,
  PALETTE_FLOOR,
} from "../../support/library.js";
import { tokenized } from "../../support/run.js";
import type {
  Catalog,
  ElementDescriptorView,
  ElementTypeInUse,
  LibraryElementGroupView,
  LibraryElementsView,
} from "../../support/catalog.js";

/**
 * Types this spec may create to move the listing, in preference order.
 *
 * A type the seed corpus or a sibling spec uses would already be in the listing before the
 * round-trip starts, so the case would assert a transition that never happens. None of these
 * appears in `fixtures/chains/` or in any `createElement` call in the suite, and the case picks the
 * first that is genuinely free at the moment it runs rather than trusting that list to stay true.
 */
const SPARE_TYPES = ["xslt", "mail-sender", "file-read", "file-write", "jms-sender", "pubsub-sender"];

/**
 * The keys every descriptor carries, whatever the element.
 *
 * `ElementDescriptor` initializes each of these to a non-null default, so Jackson writes them all
 * and a reader may take them without a guard. The five in `OPTIONAL_KEYS` are the ones left null
 * unless the element's YAML sets them, and `@JsonInclude` drops those from the answer. Both lists
 * are asserted, because the pair is what makes the shape stable: the first says nothing silently
 * became optional, the second says nothing was silently added.
 */
const REQUIRED_KEYS = [
  "allowedChildren", "allowedInContainers", "container", "customTabs", "deprecated", "folder",
  "inputEnabled", "inputQuantity", "mandatoryInnerElement", "name", "oldStyleContainer", "ordered",
  "outputEnabled", "parentRestriction", "priorityProperty", "properties", "queryProperties",
  "referenceProperties", "referencedByAnotherElement", "reuseReferenceProperty", "title", "type",
  "unsupported",
];

const OPTIONAL_KEYS = [
  "colorType", "description", "descriptionFormatter", "designContainerParameters", "designParameters",
];

/** `ElementType`, by its `@JsonProperty` names rather than its Java constants. */
const ELEMENT_TYPES = [
  "module", "trigger", "composite-trigger", "system", "container", "swimlane", "reuse",
  "reuse-reference",
];

/** `Quantity`, likewise. */
const QUANTITIES = ["any", "one-or-zero", "one-or-many", "two-or-many", "one"];

/** The four buckets `ElementProperties` declares, which the editor draws as tabs. */
const PROPERTY_BUCKETS = ["advanced", "async", "common", "hidden"];

/** The keys whose value is a boolean on every element. */
const BOOLEAN_KEYS = [
  "allowedInContainers", "container", "deprecated", "inputEnabled", "mandatoryInnerElement",
  "oldStyleContainer", "ordered", "outputEnabled", "referencedByAnotherElement", "unsupported",
];

/** The keys whose value is an array on every element. */
const ARRAY_KEYS = ["customTabs", "parentRestriction", "queryProperties", "referenceProperties"];

/** Every element the palette offers, whichever of the two partitions serves it. */
function paletteElements(library: LibraryElementsView): Map<string, ElementDescriptorView> {
  const all = new Map<string, ElementDescriptorView>();
  for (const element of groupedElements(library)) all.set(element.name, element);
  for (const [name, element] of Object.entries(library.childElements)) all.set(name, element);
  return all;
}

/** The elements the groups carry, at every depth. */
function groupedElements(node: LibraryElementsView): ElementDescriptorView[] {
  return [...node.elements, ...node.groups.flatMap(groupedElements)];
}

/** The groups, at every depth. */
function allGroups(node: LibraryElementsView): LibraryElementGroupView[] {
  return node.groups.flatMap((group) => [group, ...allGroups(group)]);
}

/**
 * The types listing, read so that its one interesting failure explains itself.
 *
 * A 500 here is the defect in the file header rather than anything this spec did, so the message
 * carries the query that finds the offending row. Without it the reader gets
 * `Element descriptor 'x' not found` and no way to tell whose element `x` is.
 */
async function presentableTypesInUse(catalog: Catalog): Promise<ElementTypeInUse[]> {
  const response = await catalog.raw("get", "/v1/library/elements/types");
  expect(
    response.status(),
    "the element-type listing failed. It maps every type in `catalog.elements` through the " +
      "library and throws on one the library does not offer, so a single row anywhere — a " +
      "snapshot element of an unrelated chain included — takes it down for the whole instance. " +
      "Find it with:\n" +
      "  SELECT e.id, e.type, e.chain_id, e.snapshot_id FROM catalog.elements e\n" +
      "  WHERE e.type NOT IN (<the names GET /v1/library offers>);\n" +
      "Body: " +
      (await response.text()),
  ).toBe(200);
  return (await response.json()) as ElementTypeInUse[];
}

test("the palette partitions on `parentRestriction`, and nothing is served twice", { tag: ["@catalog", "@tier1"] }, async ({ catalog }) => {
  const library = await catalog.library();

  expect(Object.keys(library).sort()).toEqual(["childElements", "elements", "groups"]);

  const grouped = groupedElements(library);
  const children = Object.entries(library.childElements);

  // Both floors first: `[].filter()` is `[]`, so either partition coming back empty would satisfy
  // every assertion below while proving nothing about the rule.
  expect(grouped.length, "the palette's groups carry no elements at all").toBeGreaterThan(
    GROUPED_ELEMENT_FLOOR,
  );
  expect(children.length, "the palette offers no child elements at all").toBeGreaterThan(
    CHILD_ELEMENT_FLOOR,
  );

  // The rule, both ways round. `getElementsHierarchy` sends an element to its folder's group when
  // the restriction is empty and to the root's `childElements` map when it is not, so either
  // direction failing means an element is placed where no reader expects to find it.
  expect(
    grouped.filter((element) => element.parentRestriction.length > 0).map((element) => element.name),
    "grouped elements that restrict their parent, which belong under `childElements`",
  ).toEqual([]);
  expect(
    children.filter(([, element]) => element.parentRestriction.length === 0).map(([name]) => name),
    "child elements with no parent restriction, which belong in a group",
  ).toEqual([]);

  // One element, one home. A name in both partitions would make every by-name reader ambiguous and
  // would double-count the palette.
  const names = [...grouped.map((element) => element.name), ...children.map(([name]) => name)];
  expect(new Set(names).size, "an element is served in two places at once").toBe(names.length);

  // And the key of the map is the element's name rather than its `type` — `catch-2` is served with
  // `type: "module"`, so a reader keying on the type would collapse most of the map into one entry.
  for (const [key, element] of children) expect(element.name, `childElements key ${key}`).toBe(key);
});

test("every entry carries the same descriptor shape, whichever section serves it", { tag: ["@catalog", "@tier1"] }, async ({ catalog }) => {
  const palette = paletteElements(await catalog.library());
  expect(palette.size, "the palette offered nothing to describe").toBeGreaterThan(PALETTE_FLOOR);

  for (const [name, element] of palette) {
    const keys = Object.keys(element);
    expect(
      REQUIRED_KEYS.filter((key) => !keys.includes(key)),
      `${name} is missing keys every element is supposed to carry`,
    ).toEqual([]);
    expect(
      keys.filter((key) => !REQUIRED_KEYS.includes(key) && !OPTIONAL_KEYS.includes(key)),
      `${name} carries a key this spec has never seen`,
    ).toEqual([]);

    const entry = element as unknown as Record<string, unknown>;
    for (const key of BOOLEAN_KEYS) expect(typeof entry[key], `${name}.${key}`).toBe("boolean");
    for (const key of ARRAY_KEYS) expect(Array.isArray(entry[key]), `${name}.${key} is not an array`).toBe(true);
    for (const key of ["name", "title", "folder", "priorityProperty", "reuseReferenceProperty"]) {
      expect(typeof entry[key], `${name}.${key}`).toBe("string");
    }

    // `folder` is on every element, including the ones served under `childElements` rather than in
    // a group. Placement is decided by `parentRestriction` alone, so a child element declares a
    // folder it is never drawn in.
    expect(element.folder, `${name} declares no folder`).toBeTruthy();

    // Both enums, by the names Jackson writes rather than by the Java constants.
    expect(ELEMENT_TYPES, `${name}.type`).toContain(element.type);
    expect(QUANTITIES, `${name}.inputQuantity`).toContain(entry["inputQuantity"]);

    // The four property buckets are always all four, empty ones included, so the editor draws the
    // same tabs for every element and a reader indexes them without a guard.
    expect(Object.keys(element.properties).sort(), `${name}.properties`).toEqual(PROPERTY_BUCKETS);
    for (const bucket of PROPERTY_BUCKETS) {
      for (const property of element.properties[bucket]) {
        expect(typeof property.name, `${name}.properties.${bucket}[].name`).toBe("string");
      }
    }

    // `allowedChildren` is a map of child name to `Quantity`, and it is the container's half of the
    // rule `parentRestriction` states from the child's side.
    for (const [child, quantity] of Object.entries(element.allowedChildren)) {
      expect(QUANTITIES, `${name}.allowedChildren.${child}`).toContain(quantity);
    }
  }

  // Both lists have to stay meaningful. A key called optional that no element carries is a dead
  // entry, and one that every element carries belongs in `REQUIRED_KEYS` — left here it lets the
  // unexpected-key filter above wave through a field that has quietly become mandatory.
  const optionalSeen = OPTIONAL_KEYS.filter((key) =>
    [...palette.values()].some((element) => key in element),
  );
  expect(optionalSeen, "keys this spec calls optional that no element carries").toEqual(OPTIONAL_KEYS);
  const alwaysPresent = OPTIONAL_KEYS.filter((key) =>
    [...palette.values()].every((element) => key in element),
  );
  expect(alwaysPresent, "keys this spec calls optional that every element carries").toEqual([]);
});

test("a group carries its folder's identity, its own elements, and never a child element", { tag: ["@catalog", "@tier2"] }, async ({ catalog }) => {
  const library = await catalog.library();
  const groups = allGroups(library);

  expect(groups.length, "the palette has no groups").toBeGreaterThan(5);

  for (const group of groups) {
    // `LibraryElementGroup` unwraps the folder onto the group, so these are the folder's fields
    // rather than a nested object a reader has to descend into.
    expect(group.name, `a group with no name: ${JSON.stringify(Object.keys(group))}`).toBeTruthy();
    expect(group.title, `group ${group.name} has no title`).toBeTruthy();
    // A group nobody can put anything in is a group the editor draws empty.
    expect(group.elements.length, `group ${group.name} is empty`).toBeGreaterThan(0);
    // `addChild(name, element)` is reached on the root and nowhere else, so this map is the root's
    // alone. A reader walking groups for child elements finds none, and has to look at the root.
    expect(Object.keys(group.childElements), `group ${group.name} carries child elements`).toEqual([]);
  }

  expect(new Set(groups.map((group) => group.name)).size, "two groups share a name").toBe(groups.length);

  // The nesting rule, asserted in the direction this build exercises: a group appears at the root
  // exactly when it names no parent. The other direction — a nested group whose `parent` names the
  // group it sits under — has nothing to assert over, because `folders.yaml` declares no `parent`
  // on this build and every group is at the top level. Asserting it anyway would pass vacuously.
  for (const group of library.groups) {
    expect(group.parent, `root group ${group.name} names a parent it is not nested under`).toBeUndefined();
  }
  expect(allGroups(library).length, "a group nested under another one appeared").toBe(library.groups.length);
});

test("every element the palette offers is addressable by name and answers the same object", { tag: ["@catalog", "@tier1"] }, async ({ catalog }) => {
  const library = await catalog.library();
  const palette = paletteElements(library);
  expect(palette.size, "the palette offered nothing to address").toBeGreaterThan(PALETTE_FLOOR);

  // Both endpoints read `LibraryElementsService.elements`, so this is deep equality and not a
  // field-by-field comparison: a key the tree carries and the by-name read drops would be a reader
  // of one endpoint seeing an element the other does not describe.
  const answers = await Promise.all(
    [...palette.keys()].map(async (name) => {
      const response = await catalog.libraryElement(name);
      return { name, status: response.status(), body: response.ok() ? await response.json() : null };
    }),
  );

  expect(answers.filter((answer) => answer.status !== 200).map((answer) => answer.name)).toEqual([]);
  const differing = answers
    .filter((answer) => JSON.stringify(answer.body) !== JSON.stringify(palette.get(answer.name)))
    .map((answer) => answer.name);
  expect(differing, "elements the by-name read describes differently from the palette").toEqual([]);
});

test("the by-name read looks up a name, and misses with an empty 404", { tag: ["@catalog", "@tier1"] }, async ({ catalog }) => {
  const library = await catalog.library();
  const palette = paletteElements(library);

  // `catch-2` is what makes the point: it is served, and it is served with `type: "module"`. So
  // `module` looks like an address and is not one.
  expect(palette.get("catch-2")?.type, "catch-2 no longer carries a type distinct from its name").toBe("module");

  const misses = [
    // A `type`, which several elements carry and no element is named.
    "module",
    // A group name. Folders and elements share one path segment and only elements answer.
    "routing",
    // A name nothing has ever carried.
    "no-such-library-element",
  ];
  expect(misses.filter((name) => palette.has(name)), "a miss candidate is a real element").toEqual([]);

  for (const name of misses) {
    const response = await catalog.libraryElement(name);
    expect(response.status(), `GET /v1/library/${name}`).toBe(404);
    // `ResponseEntity.notFound().build()` bypasses the exception handler, so there is no
    // `{serviceName, errorMessage, errorDate}` envelope here — unlike, say,
    // `GET /v1/catalog/diagnostic/validations/{id}`, whose 404 quotes the id it could not find.
    expect(await response.text(), `GET /v1/library/${name} answered a body`).toBe("");
  }

  // And the element the misses are contrasted against does answer, so the 404s above are the
  // lookup failing rather than the endpoint being gone.
  expect((await catalog.libraryElement("catch-2")).status()).toBe(200);
});

test("the types listing is the palette projected through the catalog's own elements", { tag: ["@catalog", "@tier1"] }, async ({ catalog }) => {
  const inUse = await presentableTypesInUse(catalog);
  const palette = paletteElements(await catalog.library());

  expect(inUse.length, "no element type is in use anywhere in the catalog").toBeGreaterThan(0);

  for (const row of inUse) {
    expect(Object.keys(row).sort(), `row ${JSON.stringify(row)}`).toEqual(["elementTitle", "elementType"]);
    const descriptor = palette.get(row.elementType);
    expect(descriptor, `type ${row.elementType} is in use and the palette does not offer it`).toBeDefined();
    // The title is read from the library at request time rather than stored with the element, so
    // this is the projection: rename an element in its descriptor and this listing renames with it.
    expect(descriptor?.title, `title of ${row.elementType}`).toBe(row.elementTitle);
  }

  // `GROUP BY e.type` cannot repeat a type, and a duplicate would double every entry in the UI's
  // element filter.
  const types = inUse.map((row) => row.elementType);
  expect(new Set(types).size, "the listing repeats a type").toBe(types.length);

  // A **strict** subset, which is the whole difference between this endpoint and `GET /v1/library`:
  // it answers what the catalog uses, not what the editor offers. This is also the floor the
  // round-trip below stands on — with no spare type there is no transition to observe.
  const unused = [...palette.keys()].filter((name) => !types.includes(name));
  expect(
    unused.length,
    "every element the palette offers is in use somewhere, so the listing is not a subset",
  ).toBeGreaterThan(0);
  expect(inUse.length).toBeLessThan(palette.size);
});

test("a type enters the listing when an element of it exists and leaves when it is deleted", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const before = await presentableTypesInUse(catalog);
  const used = new Set(before.map((row) => row.elementType));
  const spare = SPARE_TYPES.find((type) => !used.has(type));
  expect(
    spare,
    `every spare type is already in use (${SPARE_TYPES.join(", ")}), so this case cannot observe a ` +
      "type entering the listing. Pick another element the corpus does not place.",
  ).toBeDefined();
  if (spare === undefined) return;

  const title = paletteElements(await catalog.library()).get(spare)?.title;
  expect(title, `the palette no longer offers ${spare}`).toBeTruthy();

  const chain = await catalog.createChain(tokenized(run, "lib-types"), folder.id);
  const element = await catalog.createElement(chain.id, spare);

  // The transition itself, which is where the endpoint's contract lives. Nothing about this chain
  // is deployed, snapshotted or connected, so the only change is that one row of `catalog.elements`
  // now carries the type.
  const during = await presentableTypesInUse(catalog);
  expect(during, `${spare} did not enter the listing when an element of it was created`).toContainEqual({
    elementType: spare,
    elementTitle: title,
  });

  // Everything that was in use still is: the listing is derived per request, not accumulated.
  expect(during.map((row) => row.elementType)).toEqual(expect.arrayContaining([...used]));

  await catalog.deleteElement(chain.id, element.id);

  const after = await presentableTypesInUse(catalog);
  expect(
    after.map((row) => row.elementType),
    `${spare} stayed in the listing after its only element was deleted`,
  ).not.toContain(spare);
  expect(after.map((row) => row.elementType)).toEqual(expect.arrayContaining([...used]));
});
