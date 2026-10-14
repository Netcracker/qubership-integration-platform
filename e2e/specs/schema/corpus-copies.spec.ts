/**
 * The micro copy of the corpus beside the classic one, with no stack.
 *
 * Both copies are imported into one catalog, so they must not share a chain id, an element id, or a
 * trigger path. The micro copy loads into one Integration, and one chain that fails to load stops
 * the pod, so the copy holds only what `runtime-micro` reads, and every reference inside it has to
 * resolve inside it.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { test, expect } from "@playwright/test";
import {
  CHAIN_FIXTURE_DIR,
  corpusFixtureNames,
  ENGINE_LOOPBACK,
  httpTriggerPaths,
  MICRO_FIXTURES,
  MICRO_PATH_PREFIX,
  microCopy,
  readFixtureDocument,
  SCRIPT_FIXTURE_DIR,
  type RenderedTree,
} from "../../fixtures/templating.js";
import { axisFixtureName, axisFixtures, METHOD_ECHO_URI } from "../../fixtures/axis-generator.js";
import {
  fixtureTrees,
  flatten,
  microCorpusTrees,
  PLACEHOLDER_FIXTURE,
  placeholderVariable,
  type FixtureElement,
} from "../../support/corpus.js";
import { CLASSIC_ONLY_RUNTIME_FILES } from "../../env/target-setup.js";
import { UUID, UUID_TEXT } from "../../support/absent.js";
import { EXAMPLE_RUN_TOKEN } from "../../support/run.js";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const RUNTIME_DIR = path.join(HERE, "..", "runtime");
const ANY_UUID = new RegExp(UUID_TEXT, "gi");
const LOOPBACK_ROUTE = new RegExp(`${ENGINE_LOOPBACK.replaceAll(".", "\\.")}/routes/([^"\\s?]+)`, "g");

interface Copy {
  chainIds: string[];
  elementIds: string[];
  /** Every `contextPath` value, of a trigger or of a checkpoint. */
  paths: string[];
  triggerPaths: string[];
  /** Every UUID written anywhere in the copy's files. */
  references: string[];
  /** The name of every `#{name}` placeholder in an element property. */
  variables: string[];
  /** The `contextPath` every `ENGINE_LOOPBACK` call addresses. */
  loopbacks: string[];
}

function read(trees: Array<[string, RenderedTree]>): Copy {
  const copy: Copy = { chainIds: [], elementIds: [], paths: [], triggerPaths: [], references: [], variables: [], loopbacks: [] };
  for (const [name, tree] of trees) {
    const { id, document } = readFixtureDocument(name, tree);
    copy.chainIds.push(id);
    const elements = flatten(((document.content as { elements?: FixtureElement[] })?.elements) ?? []);
    for (const element of elements) {
      if (typeof element.id === "string") copy.elementIds.push(element.id);
      const contextPath = element.properties?.contextPath;
      if (typeof contextPath === "string") copy.paths.push(contextPath);
      const properties = JSON.stringify(element.properties ?? {});
      copy.variables.push(...[...properties.matchAll(/#\{([^}]+)\}/g)].map((match) => match[1]));
    }
    copy.triggerPaths.push(...httpTriggerPaths(document));
    for (const text of tree.values()) {
      copy.references.push(...(text.match(ANY_UUID) ?? []));
      copy.loopbacks.push(...[...text.matchAll(LOOPBACK_ROUTE)].map((match) => match[1]));
    }
  }
  return copy;
}

/**
 * The fixtures a runtime file reads through `seedChain`: each literal name, the constant
 * `MASKED_FIXTURE`, and, for a file that builds an `http-trigger` name with `axisFixtureName`,
 * every generated `http-trigger` axis fixture. The two files that do so iterate over every value
 * of the axes they cover, and the generator declares no other `http-trigger` axis.
 */
function fixturesReadBy(source: string): string[] {
  const read = [...source.matchAll(/seedChain\(\s*[^,]+,\s*"([^"]+)"\s*\)/g)].map((match) => match[1]);
  if (/seedChain\(\s*[^,]+,\s*MASKED_FIXTURE\s*\)/.test(source)) read.push("masking");
  if (/axisFixtureName\(/.test(source) && source.includes('family: "http-trigger"')) {
    read.push(
      ...axisFixtures
        .filter((each) => each.family === "http-trigger" && !each.handWritten)
        .map(axisFixtureName),
    );
  }
  return read;
}

/** `source` without its `@classic-only` tests, each cut from its declaration line to its closing `});`. */
function withoutClassicOnly(source: string): string {
  const kept: string[] = [];
  let closing: string | undefined;
  for (const line of source.split("\n")) {
    if (closing !== undefined) {
      if (line === closing) closing = undefined;
      continue;
    }
    const declaration = /^(\s*)test(\.\w+)?\(/.exec(line);
    if (declaration && line.includes('"@classic-only"')) closing = `${declaration[1]}});`;
    else kept.push(line);
  }
  return kept.join("\n");
}

test("the micro copy holds exactly the fixtures the runtime-micro files read", { tag: ["@infra", "@tier1"] }, () => {
  const files = fs
    .readdirSync(RUNTIME_DIR)
    .filter((file) => file.endsWith(".spec.ts") && !CLASSIC_ONLY_RUNTIME_FILES.includes(file));
  const read = new Set(
    files.flatMap((file) => fixturesReadBy(withoutClassicOnly(fs.readFileSync(path.join(RUNTIME_DIR, file), "utf-8")))),
  );
  expect([...MICRO_FIXTURES].sort()).toEqual([...read].sort());
  // A classic-only file that no longer exists would keep its fixtures out of the comparison above.
  const missing = CLASSIC_ONLY_RUNTIME_FILES.filter((file) => !fs.existsSync(path.join(RUNTIME_DIR, file)));
  expect(missing, "classic-only runtime files that are not in specs/runtime/").toEqual([]);

  // A tagged test's reads are left out, and the reads around it are kept.
  const source = [
    'const shared = seedChain(state, "kept-a");',
    'test("micro", { tag: ["@engine", "@tier1"] }, async () => {',
    '  seedChain(state, "kept-b");',
    "});",
    'test("classic", { tag: ["@engine", "@tier1", "@classic-only"], annotation: { type: "classic-only", description: "a reason" } }, async () => {',
    "  await withBuilt(catalog, async () => {",
    '    seedChain(state, "dropped");',
    "  });",
    "});",
    'test.describe("group", () => {',
    '  test("nested classic", { tag: ["@engine", "@tier1", "@classic-only"] }, async () => {',
    '    seedChain(state, "dropped-nested");',
    "  });",
    '  test("nested micro", { tag: ["@engine", "@tier1"] }, async () => {',
    '    seedChain(state, "kept-c");',
    "  });",
    "});",
  ].join("\n");
  expect(fixturesReadBy(withoutClassicOnly(source))).toEqual(["kept-a", "kept-b", "kept-c"]);
});

test("the copies share no chain id, element id, or path", { tag: ["@infra", "@tier1"] }, async () => {
  const classic = read(await fixtureTrees(MICRO_FIXTURES, EXAMPLE_RUN_TOKEN));
  const micro = read(await microCorpusTrees(EXAMPLE_RUN_TOKEN));
  const shared = (a: string[], b: string[]) => a.filter((each) => b.includes(each));

  expect(micro.chainIds).toHaveLength(MICRO_FIXTURES.length);
  expect(shared(classic.chainIds, micro.chainIds)).toEqual([]);
  expect(shared(classic.elementIds, micro.elementIds)).toEqual([]);
  expect(shared(classic.paths, micro.paths)).toEqual([]);
});

test("every remapped chain and element id is a UUID, the same on every render", { tag: ["@infra", "@tier1"] }, async () => {
  const first = read(await microCorpusTrees(EXAMPLE_RUN_TOKEN));
  const second = read(await microCorpusTrees(EXAMPLE_RUN_TOKEN));
  expect([...first.chainIds, ...first.elementIds].filter((id) => !UUID.test(id))).toEqual([]);
  expect(second.chainIds).toEqual(first.chainIds);
  expect(second.elementIds).toEqual(first.elementIds);
});

test("every reference in the micro copy names a chain or an element of the micro copy", { tag: ["@infra", "@tier1"] }, async () => {
  const micro = read(await microCorpusTrees(EXAMPLE_RUN_TOKEN));
  const known = new Set([...micro.chainIds, ...micro.elementIds]);
  expect([...new Set(micro.references)].filter((id) => !known.has(id))).toEqual([]);
});

test("micro trigger paths carry the prefix with no leading slash, and checkpoint paths carry none", { tag: ["@infra", "@tier1"] }, async () => {
  const micro = read(await microCorpusTrees(EXAMPLE_RUN_TOKEN));
  expect(micro.triggerPaths.length).toBeGreaterThan(0);
  expect(micro.triggerPaths.filter((each) => !each.startsWith(MICRO_PATH_PREFIX))).toEqual([]);

  const checkpointPaths = micro.paths.filter((each) => !micro.triggerPaths.includes(each));
  expect(checkpointPaths.length, "the checkpoint fixture's retry path").toBeGreaterThan(0);
  expect(checkpointPaths.filter((each) => each.includes(MICRO_PATH_PREFIX))).toEqual([]);
});

/** The micro copy of each named fixture, whether `MICRO_FIXTURES` lists it or not. */
async function microCopies(names: readonly string[]): Promise<Array<[string, RenderedTree]>> {
  return (await fixtureTrees(names, EXAMPLE_RUN_TOKEN)).map(([name, tree]) => [name, microCopy(name, tree)]);
}

// The micro engine substitutes `#{variable}` once, when the domain loads its routes, so a variable
// the copy reads has to exist before `seed-micro` deploys, and that seed writes only this one.
test("the micro copy reads no common variable but the one seed-micro writes", { tag: ["@infra", "@tier1"] }, async () => {
  const written = placeholderVariable(EXAMPLE_RUN_TOKEN);
  const micro = read(await microCorpusTrees(EXAMPLE_RUN_TOKEN));
  expect(micro.variables.filter((name) => name !== written)).toEqual([]);
  // The name the seed writes is the one the placeholder fixture renders.
  expect(read(await microCopies([PLACEHOLDER_FIXTURE])).variables).toEqual([written]);
});

// The method echo and every fixture that calls it, whether the micro copy holds them yet or not.
const LOOPBACK_FIXTURES = [
  "method-echo",
  ...axisFixtures.filter((each) => each.properties?.uri === METHOD_ECHO_URI).map(axisFixtureName),
];

test("a loopback call in the micro copy addresses the micro copy of its callee", { tag: ["@infra", "@tier1"] }, async () => {
  const classic = read(await fixtureTrees(LOOPBACK_FIXTURES, EXAMPLE_RUN_TOKEN));
  const micro = read([...(await microCorpusTrees(EXAMPLE_RUN_TOKEN)), ...(await microCopies(LOOPBACK_FIXTURES))]);
  expect(classic.loopbacks.length).toBeGreaterThan(0);
  expect(classic.loopbacks.filter((each) => !classic.triggerPaths.includes(each))).toEqual([]);
  expect(micro.loopbacks.length).toBeGreaterThanOrEqual(classic.loopbacks.length);
  expect(micro.loopbacks.filter((each) => !micro.triggerPaths.includes(each))).toEqual([]);
});

// The suite calls a corpus chain on the engine's own port, so the corpus writes no gateway route
// and keeps every trigger internal.
test("every HTTP trigger the corpus seeds, in both copies, is internal", { tag: ["@infra", "@tier1"] }, async () => {
  const names = [
    ...corpusFixtureNames([CHAIN_FIXTURE_DIR, SCRIPT_FIXTURE_DIR]),
    ...axisFixtures.filter((each) => !each.handWritten).map(axisFixtureName),
  ];
  const external = (trees: Array<[string, RenderedTree]>) =>
    trees.flatMap(([name, tree]) => {
      const { document } = readFixtureDocument(name, tree);
      return flatten(((document.content as { elements?: FixtureElement[] })?.elements) ?? [])
        .filter((element) => element.type === "http-trigger" && element.properties?.externalRoute !== false)
        .map((element) => `${name}: ${String(element.name)}`);
    });
  const classic = await fixtureTrees(names, EXAMPLE_RUN_TOKEN);
  expect(classic.length).toBeGreaterThan(MICRO_FIXTURES.length);
  expect(external(classic)).toEqual([]);
  expect(external(await microCorpusTrees(EXAMPLE_RUN_TOKEN))).toEqual([]);
});
