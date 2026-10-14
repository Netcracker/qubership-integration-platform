/**
 * The micro copy of the corpus beside the classic one, with no stack, and the same for the broker
 * corpus, whose two copies also share the brokers.
 *
 * Both copies are imported into one catalog, so they must not share a chain id, an element id, or a
 * trigger path. The micro copy loads into one Integration, and one chain that fails to load stops
 * the pod, so the copy holds only what `runtime-micro` reads, and every reference inside it has to
 * resolve inside it.
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { test, expect } from "@playwright/test";
import yaml from "js-yaml";
import {
  CHAIN_FIXTURE_DIR,
  corpusFixtureNames,
  ENGINE_LOOPBACK,
  httpTriggerPaths,
  isMicroAddress,
  MICRO_FIXTURES,
  MICRO_PATH_PREFIX,
  microCopy,
  readFixtureDocument,
  readServiceFixture,
  SCRIPT_FIXTURE_DIR,
  type RenderedTree,
  type ServiceFixture,
} from "../../fixtures/templating.js";
import { axisFixtureName, axisFixtures, METHOD_ECHO_URI } from "../../fixtures/axis-generator.js";
import {
  fixtureTrees,
  flatten,
  microCorpusTrees,
  microServiceTrees,
  PLACEHOLDER_FIXTURE,
  placeholderVariable,
  serviceTrees,
  type FixtureElement,
} from "../../support/corpus.js";
import {
  brokerChain,
  brokerCorpusOf,
  brokerServiceTrees,
  brokerTrees,
  kafkaGroupsIn,
  microBrokerServiceTrees,
  microBrokerTrees,
  sftpRootsOf,
} from "../../support/brokers.js";
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

/** What the copy's chains hold; `known` are the services their loopback calls are read through. */
function read(trees: Array<[string, RenderedTree]>, known: readonly ServiceFixture[] = []): Copy {
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
    for (const text of tree.values()) copy.references.push(...(text.match(ANY_UUID) ?? []));
    copy.loopbacks.push(...loopbacksOf(tree, known));
  }
  return copy;
}

/**
 * The fixtures a runtime file reads through `seedChain` or `triggerOf`: each literal name, the
 * constant `MASKED_FIXTURE`, and, for a file that builds a name with `axisFixtureName`, every
 * generated axis fixture of each family it names in a `family: "…"` literal. The files that do so
 * iterate over every value the generator declares for the axes they cover; together they read
 * every axis of those families.
 */
function fixturesReadBy(source: string): string[] {
  const read = [
    ...[...source.matchAll(/seedChain\(\s*[^,]+,\s*"([^"]+)"\s*\)/g)].map((match) => match[1]),
    ...[...source.matchAll(/triggerOf\(\s*"([^"]+)"\s*\)/g)].map((match) => match[1]),
  ];
  if (/seedChain\(\s*[^,]+,\s*MASKED_FIXTURE\s*\)/.test(source)) read.push("masking");
  if (/seedChain\(\s*[^,]+,\s*PLACEHOLDER_FIXTURE\s*\)/.test(source)) read.push(PLACEHOLDER_FIXTURE);
  if (/axisFixtureName\(/.test(source)) {
    const families = new Set([...source.matchAll(/family: "([^"]+)"/g)].map((match) => match[1]));
    read.push(
      ...axisFixtures
        .filter((each) => families.has(each.family) && !each.handWritten)
        .map(axisFixtureName),
    );
  }
  return read;
}

/** The URL each operation of a service is called on: the address, with the path for http. */
function operationUrls(service: ServiceFixture): Map<string, string> {
  return new Map(
    service.operations.map((operation) => [
      operation.id,
      service.protocol === "HTTP" ? `${service.address}${operation.path}` : service.address,
    ]),
  );
}

/** The `contextPath` each `ENGINE_LOOPBACK` call of a chain addresses, in its own URIs or through a service operation it calls. */
function loopbacksOf(tree: RenderedTree, services: readonly ServiceFixture[]): string[] {
  const text = [...tree.values()].join("\n");
  const urls = services.flatMap((service) =>
    [...operationUrls(service)].filter(([operation]) => text.includes(operation)).map(([, url]) => url),
  );
  return [text, ...urls].flatMap((each) => [...each.matchAll(LOOPBACK_ROUTE)].map((match) => match[1]));
}

/** The `service` fixtures among `trees`, leaving out context and MCP services. */
function ofServiceKind(trees: Array<[string, RenderedTree]>): Array<[string, RenderedTree]> {
  return trees.filter(([name, tree]) => readFixtureDocument(name, tree).kind === "service");
}

/** The service fixtures, in the classic copy or in the micro one, as a chain calls them. */
function services(trees: Array<[string, RenderedTree]>): ServiceFixture[] {
  return ofServiceKind(trees).map(([name, tree]) => readServiceFixture(name, tree));
}

/** Every chain fixture of the classic corpus: the tracked ones and the generated axis chains. */
function corpusChainNames(): string[] {
  return [
    ...corpusFixtureNames([CHAIN_FIXTURE_DIR, SCRIPT_FIXTURE_DIR]),
    ...axisFixtures.filter((each) => !each.handWritten).map(axisFixtureName),
  ];
}

/** The fixtures `names` reach through loopback calls, transitively, and not in `names`. */
async function loopbackCallees(names: readonly string[]): Promise<string[]> {
  const trees = new Map(await fixtureTrees(corpusChainNames(), EXAMPLE_RUN_TOKEN));
  const byPath = new Map<string, string>();
  for (const [name, tree] of trees) {
    for (const contextPath of httpTriggerPaths(readFixtureDocument(name, tree).document)) byPath.set(contextPath, name);
  }
  const known = services(serviceTrees(EXAMPLE_RUN_TOKEN));
  const reached = new Set(names);
  const pending = [...names];
  while (pending.length > 0) {
    for (const contextPath of loopbacksOf(trees.get(pending.pop()!)!, known)) {
      const callee = byPath.get(contextPath);
      if (callee === undefined || reached.has(callee)) continue;
      reached.add(callee);
      pending.push(callee);
    }
  }
  return [...reached].filter((each) => !names.includes(each));
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

test("the micro copy holds exactly the fixtures the runtime-micro files read, and the ones those call", { tag: ["@infra", "@tier1"] }, async () => {
  const files = fs.readdirSync(RUNTIME_DIR).filter((file) => file.endsWith(".spec.ts"));
  const read = [
    ...new Set(
      files.flatMap((file) => fixturesReadBy(withoutClassicOnly(fs.readFileSync(path.join(RUNTIME_DIR, file), "utf-8")))),
    ),
  ];
  expect([...MICRO_FIXTURES].sort()).toEqual([...read, ...(await loopbackCallees(read))].sort());

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

// The import reads a specification's source by the file name the specification records, under the
// service's directory or its `resources/`, and builds a GraphQL library from that source.
test("every specification source a service names is a file of the service, in both copies", { tag: ["@infra", "@tier1"] }, () => {
  for (const trees of [serviceTrees(EXAMPLE_RUN_TOKEN), microServiceTrees(EXAMPLE_RUN_TOKEN)]) {
    for (const [name, tree] of ofServiceKind(trees)) {
      const files = [...readFixtureDocument(name, tree).companions]
        .filter(([rel]) => rel.endsWith(".specification.cip.yaml"))
        .flatMap(([, source]) => (yaml.load(source) as { content: { specificationSources: Array<{ fileName: string }> } }).content.specificationSources)
        .map((each) => each.fileName);
      expect(files.length, name).toBeGreaterThan(0);
      expect(files.filter((file) => !tree.has(file) && !tree.has(`resources/${file}`)), name).toEqual([]);
    }
  }
});

// The catalog keeps an MCP service identifier unique, and both copies are imported into one catalog.
test("the copies of an MCP service carry different identifiers", { tag: ["@infra", "@tier1"] }, () => {
  const identifiers = (trees: Array<[string, RenderedTree]>) =>
    trees
      .map(([name, tree]) => readFixtureDocument(name, tree))
      .filter((each) => each.kind === "mcp-service")
      .map((each) => (each.document.content as { identifier: string }).identifier);
  const classic = identifiers(serviceTrees(EXAMPLE_RUN_TOKEN));
  const micro = identifiers(microServiceTrees(EXAMPLE_RUN_TOKEN));
  expect(classic.length).toBeGreaterThan(0);
  expect(micro).toHaveLength(classic.length);
  expect(micro.filter((each) => classic.includes(each))).toEqual([]);
});

/** Every UUID written anywhere in the trees. */
function uuidsOf(trees: Array<[string, RenderedTree]>): string[] {
  return trees.flatMap(([, tree]) => [...tree.values()].flatMap((text) => text.match(ANY_UUID) ?? []));
}

test("the copies share no chain id, element id, path, or service id", { tag: ["@infra", "@tier1"] }, async () => {
  const classic = read(await fixtureTrees(MICRO_FIXTURES, EXAMPLE_RUN_TOKEN));
  const micro = read(await microCorpusTrees(EXAMPLE_RUN_TOKEN));
  const shared = (a: string[], b: string[]) => a.filter((each) => b.includes(each));

  expect(micro.chainIds).toHaveLength(MICRO_FIXTURES.length);
  expect(shared(classic.chainIds, micro.chainIds)).toEqual([]);
  expect(shared(classic.elementIds, micro.elementIds)).toEqual([]);
  expect(shared(classic.paths, micro.paths)).toEqual([]);
  const classicServices = uuidsOf(serviceTrees(EXAMPLE_RUN_TOKEN));
  expect(classicServices.length).toBeGreaterThan(0);
  expect(shared(classicServices, uuidsOf(microServiceTrees(EXAMPLE_RUN_TOKEN)))).toEqual([]);
});

test("every remapped chain and element id is a UUID, the same on every render", { tag: ["@infra", "@tier1"] }, async () => {
  const first = read(await microCorpusTrees(EXAMPLE_RUN_TOKEN));
  const second = read(await microCorpusTrees(EXAMPLE_RUN_TOKEN));
  expect([...first.chainIds, ...first.elementIds].filter((id) => !UUID.test(id))).toEqual([]);
  expect(second.chainIds).toEqual(first.chainIds);
  expect(second.elementIds).toEqual(first.elementIds);
});

test("every reference in the micro copy names a chain, an element, or a service of the micro copy", { tag: ["@infra", "@tier1"] }, async () => {
  const micro = read(await microCorpusTrees(EXAMPLE_RUN_TOKEN));
  const known = new Set([...micro.chainIds, ...micro.elementIds, ...uuidsOf(microServiceTrees(EXAMPLE_RUN_TOKEN))]);
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

// The two echoes and every fixture that calls one, directly or through a service, whether the micro
// copy holds them yet or not.
const LOOPBACK_FIXTURES = [
  "method-echo",
  "http-echo",
  "service-call-graphql-no-before",
  ...axisFixtures.filter((each) => each.properties?.uri === METHOD_ECHO_URI || each.family === "service-call").map(axisFixtureName),
];

test("a loopback call in the micro copy addresses the micro copy of its callee", { tag: ["@infra", "@tier1"] }, async () => {
  const classic = read(await fixtureTrees(LOOPBACK_FIXTURES, EXAMPLE_RUN_TOKEN), services(serviceTrees(EXAMPLE_RUN_TOKEN)));
  const micro = read(
    [...(await microCorpusTrees(EXAMPLE_RUN_TOKEN)), ...(await microCopies(LOOPBACK_FIXTURES))],
    services(microServiceTrees(EXAMPLE_RUN_TOKEN)),
  );
  expect(classic.loopbacks.length).toBeGreaterThan(0);
  expect(classic.loopbacks.filter((each) => !classic.triggerPaths.includes(each))).toEqual([]);
  expect(micro.loopbacks.length).toBeGreaterThanOrEqual(classic.loopbacks.length);
  expect(micro.loopbacks.filter((each) => !micro.triggerPaths.includes(each))).toEqual([]);
});

// The suite calls a corpus chain on the engine's own port, so the corpus writes no gateway route
// and keeps every trigger internal.
test("every HTTP trigger the corpus seeds, in both copies, is internal", { tag: ["@infra", "@tier1"] }, async () => {
  const names = corpusChainNames();
  const external = (trees: Array<[string, RenderedTree]>) =>
    trees.flatMap(([name, tree]) => {
      const { document } = readFixtureDocument(name, tree);
      return flatten(((document.content as { elements?: FixtureElement[] })?.elements) ?? [])
        .filter((element) => element.type === "http-trigger" && element.properties?.externalRoute !== false)
        .map((element) => `${name}: ${String(element.name)}`);
    });
  const classic = await fixtureTrees(names, EXAMPLE_RUN_TOKEN);
  expect(classic.length).toBeGreaterThanOrEqual(MICRO_FIXTURES.length);
  expect(external(classic)).toEqual([]);
  expect(external(await microCorpusTrees(EXAMPLE_RUN_TOKEN))).toEqual([]);
});

// ---------------------------------------------------------------------------
// The micro copy of the broker corpus
// ---------------------------------------------------------------------------

/** Every broker address a copy consumes from or sends to, by kind. */
function brokerAddresses(trees: Array<[string, RenderedTree]>): Record<string, string[]> {
  const documents = trees.map(([name, tree]) => readFixtureDocument(name, tree).document);
  const corpus = brokerCorpusOf(EXAMPLE_RUN_TOKEN, trees, []);
  const { queues, senderExchanges } = corpus.rabbitmqTopology;
  const { subscriptions, senderTopics } = corpus.pubsubTopology;
  return {
    "kafka topics": corpus.kafkaTopics,
    "kafka groups": kafkaGroupsIn(documents),
    "rabbitmq queues": queues.flatMap((each) => [each.queue, ...(each.deadLetterQueue ? [each.deadLetterQueue] : [])]),
    "rabbitmq exchanges": [
      ...senderExchanges,
      ...queues.flatMap((each) => [each.exchange, ...(each.deadLetterExchange ? [each.deadLetterExchange] : [])]),
    ],
    "pubsub topics and subscriptions": [
      ...subscriptions.flatMap((each) => [each.topic, each.subscription]),
      ...senderTopics.map((each) => each.topic),
    ],
    "sftp trees": sftpRootsOf(corpus.chains),
  };
}

// Both copies run at once on one set of brokers: a shared topic, exchange, or subscription would
// deliver one message to both engines, and a shared group would split a topic's partitions between them.
test("the broker copies share no chain id, element id, trigger path, or broker address", { tag: ["@infra", "@tier1"] }, () => {
  const classicTrees = brokerTrees(EXAMPLE_RUN_TOKEN);
  const microTrees = microBrokerTrees(EXAMPLE_RUN_TOKEN);
  const classic = read(classicTrees);
  const micro = read(microTrees);
  const shared = (a: string[], b: string[]) => a.filter((each) => b.includes(each));

  expect(micro.chainIds).toHaveLength(classicTrees.length);
  expect([...micro.chainIds, ...micro.elementIds].filter((id) => !UUID.test(id))).toEqual([]);
  expect(shared(classic.chainIds, micro.chainIds)).toEqual([]);
  expect(shared(classic.elementIds, micro.elementIds)).toEqual([]);
  expect(shared(classic.triggerPaths, micro.triggerPaths)).toEqual([]);

  const microAddresses = brokerAddresses(microTrees);
  for (const [kind, names] of Object.entries(brokerAddresses(classicTrees))) {
    expect(names.length, kind).toBeGreaterThan(0);
    expect(microAddresses[kind], kind).toHaveLength(names.length);
    expect(shared(names, microAddresses[kind]), kind).toEqual([]);
  }
});

// The broker chains that name an AsyncAPI service call the service copy their seed imports.
test("every reference in the micro broker copy names its own chain, element, or service", { tag: ["@infra", "@tier1"] }, () => {
  const microServices = microBrokerServiceTrees(EXAMPLE_RUN_TOKEN);
  const micro = read(microBrokerTrees(EXAMPLE_RUN_TOKEN));
  const known = new Set([...micro.chainIds, ...micro.elementIds, ...uuidsOf(microServices)]);
  expect(microServices.length).toBeGreaterThan(0);
  expect([...new Set(micro.references)].filter((id) => !known.has(id))).toEqual([]);
  expect(uuidsOf(microServices).filter((id) => uuidsOf(brokerServiceTrees(EXAMPLE_RUN_TOKEN)).includes(id))).toEqual([]);
});

// An asynchronous operation consumes from or sends to its element's operation path, so that path is a broker address.
test("the micro copy renames the topic, exchange, group, and queue of every asynchronous operation", { tag: ["@infra", "@tier1"] }, () => {
  const operationAddresses = (trees: Array<[string, RenderedTree]>) =>
    trees.flatMap(([name, tree]) =>
      flatten(((readFixtureDocument(name, tree).document.content as { elements?: FixtureElement[] })?.elements) ?? [])
        .filter((element) => element.type === "async-api-trigger" || element.type === "service-call")
        .flatMap((element) => {
          const properties = element.properties ?? {};
          const async = (properties.integrationOperationAsyncProperties ?? {}) as Record<string, unknown>;
          return [properties.integrationOperationPath, async.groupId, async.queues].filter((each) => typeof each === "string");
        }),
    );
  const classic = operationAddresses(brokerTrees(EXAMPLE_RUN_TOKEN));
  const micro = operationAddresses(microBrokerTrees(EXAMPLE_RUN_TOKEN));
  expect(classic.length, "no broker chain calls an asynchronous operation").toBeGreaterThan(0);
  expect(micro).toEqual(classic.map((each) => `${each}-micro`));
});

// Both teardowns run side by side in `suite-teardown`, and each deletes the run's consumer groups of
// its own copy only, the groups a spec opens on a copy's topic included.
test("every broker address of the micro copy, and a name a spec builds from one, belongs to the micro copy", { tag: ["@infra", "@tier1"] }, () => {
  const classic = Object.values(brokerAddresses(brokerTrees(EXAMPLE_RUN_TOKEN))).flat();
  const micro = Object.values(brokerAddresses(microBrokerTrees(EXAMPLE_RUN_TOKEN))).flat();
  expect(micro.filter((name) => !isMicroAddress(name) || !name.includes(EXAMPLE_RUN_TOKEN))).toEqual([]);
  expect(micro.map((name) => `${name}-consumer`).filter((name) => !isMicroAddress(name))).toEqual([]);
  expect(classic.filter((name) => isMicroAddress(name) || !name.includes(EXAMPLE_RUN_TOKEN))).toEqual([]);
  expect(classic.map((name) => `${name}-consumer`).filter((name) => isMicroAddress(name))).toEqual([]);
});

test("brokerChain returns the micro copy on a micro project, from the state file the project names", { tag: ["@infra", "@tier1"] }, () => {
  const classic = brokerCorpusOf(EXAMPLE_RUN_TOKEN, brokerTrees(EXAMPLE_RUN_TOKEN), brokerServiceTrees(EXAMPLE_RUN_TOKEN));
  const micro = {
    ...brokerCorpusOf(EXAMPLE_RUN_TOKEN, microBrokerTrees(EXAMPLE_RUN_TOKEN), microBrokerServiceTrees(EXAMPLE_RUN_TOKEN)),
    domain: "e2e-example-brokers-micro",
  };
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "e2e-brokers-micro-state-"));
  const file = path.join(dir, "brokers-micro.json");
  fs.writeFileSync(file, JSON.stringify(micro));
  try {
    const fixture = "kafka-sender-basic";
    const of = (corpus: typeof classic) => corpus.chains.find((each) => each.fixture === fixture)!.id;
    expect(brokerChain(classic, fixture, {}).id).toBe(of(classic));
    expect(brokerChain(classic, fixture, { engineKind: "classic", microDomainState: file }).id).toBe(of(classic));
    const chain = brokerChain(classic, fixture, { engineKind: "micro", microDomainState: file });
    expect(chain.id).toBe(of(micro));
    expect(chain.id).not.toBe(of(classic));
    expect(isMicroAddress(chain.broker.topics as string)).toBe(true);
    expect(() => brokerChain(classic, "no-such-fixture", { engineKind: "micro", microDomainState: file })).toThrow(/micro copy/);
    expect(() => brokerChain(classic, fixture, { engineKind: "micro", microDomainState: path.join(dir, "absent.json") })).toThrow(
      /brokers-micro-seed did not run/,
    );
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
