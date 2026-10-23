/**
 * The micro copy of the corpus beside the classic one, with no stack, and the same for the broker
 * corpus, whose two copies also share the brokers.
 *
 * Both copies are imported into one catalog, so they must not share a chain id, an element id, or a
 * trigger path. The micro copy loads into one Integration, and one chain that fails to load stops
 * the pod, so every reference inside it has to resolve inside it, and it reads no variable the
 * domain lacks when it loads.
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test, expect } from "@playwright/test";
import yaml from "js-yaml";
import {
  elementsOf,
  ENGINE_LOOPBACK,
  escapeRegExp,
  httpTriggerPaths,
  isMicroAddress,
  MICRO_ADDRESS_SUFFIX,
  MICRO_PATH_PREFIX,
  readFixtureDocument,
  readServiceFixture,
  type RenderedTree,
  type ServiceFixture,
} from "../../fixtures/templating.js";
import {
  corpusChainNames,
  fixtureTrees,
  microCorpusTrees,
  microServiceTrees,
  placeholderVariable,
  serviceTrees,
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

const ANY_UUID = new RegExp(UUID_TEXT, "gi");
const LOOPBACK_ROUTE = new RegExp(`${escapeRegExp(ENGINE_LOOPBACK)}/routes/([^"\\s?]+)`, "g");

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
    const elements = elementsOf(document);
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

/** Every UUID written anywhere in the trees. */
function uuidsOf(trees: Array<[string, RenderedTree]>): string[] {
  return trees.flatMap(([, tree]) => [...tree.values()].flatMap((text) => text.match(ANY_UUID) ?? []));
}

test.describe("corpus copies", { tag: ["@harness"] }, () => {
  test.describe("chain corpus", () => {
    // The import reads a specification's source by the file name the specification records, under the
    // service's directory or its `resources/`, and builds a GraphQL library from that source.
    test("every named specification source is a file of its service", () => {
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
    test("the copies of an MCP service carry different identifiers", () => {
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

    test("the copies share no chain, element, path, or service id", async () => {
      const classic = read(await fixtureTrees(corpusChainNames(), EXAMPLE_RUN_TOKEN));
      const micro = read(await microCorpusTrees(EXAMPLE_RUN_TOKEN));
      const shared = (a: string[], b: string[]) => a.filter((each) => b.includes(each));

      expect(micro.chainIds).toHaveLength(classic.chainIds.length);
      expect(shared(classic.chainIds, micro.chainIds)).toEqual([]);
      expect(shared(classic.elementIds, micro.elementIds)).toEqual([]);
      expect(shared(classic.paths, micro.paths)).toEqual([]);
      const classicServices = uuidsOf(serviceTrees(EXAMPLE_RUN_TOKEN));
      expect(classicServices.length).toBeGreaterThan(0);
      expect(shared(classicServices, uuidsOf(microServiceTrees(EXAMPLE_RUN_TOKEN)))).toEqual([]);
    });

    test("remapped chain and element ids are stable UUIDs", async () => {
      const first = read(await microCorpusTrees(EXAMPLE_RUN_TOKEN));
      const second = read(await microCorpusTrees(EXAMPLE_RUN_TOKEN));
      expect([...first.chainIds, ...first.elementIds].filter((id) => !UUID.test(id))).toEqual([]);
      expect(second.chainIds).toEqual(first.chainIds);
      expect(second.elementIds).toEqual(first.elementIds);
    });

    test("every micro reference names an entity of the micro copy", async () => {
      const micro = read(await microCorpusTrees(EXAMPLE_RUN_TOKEN));
      const known = new Set([...micro.chainIds, ...micro.elementIds, ...uuidsOf(microServiceTrees(EXAMPLE_RUN_TOKEN))]);
      expect([...new Set(micro.references)].filter((id) => !known.has(id))).toEqual([]);
    });

    test("micro trigger paths carry the prefix, checkpoint paths none", async () => {
      const micro = read(await microCorpusTrees(EXAMPLE_RUN_TOKEN));
      expect(micro.triggerPaths.length).toBeGreaterThan(0);
      expect(micro.triggerPaths.filter((each) => !each.startsWith(MICRO_PATH_PREFIX))).toEqual([]);

      const checkpointPaths = micro.paths.filter((each) => !micro.triggerPaths.includes(each));
      expect(checkpointPaths.length, "the checkpoint fixture's retry path").toBeGreaterThan(0);
      expect(checkpointPaths.filter((each) => each.includes(MICRO_PATH_PREFIX))).toEqual([]);
    });

    // The micro engine substitutes `#{variable}` once, when the domain loads its routes, so a variable
    // the copy reads has to exist before `seed-micro` deploys, and that seed writes only this one.
    test("the micro copy reads only the variable seed-micro writes", async () => {
      const written = placeholderVariable(EXAMPLE_RUN_TOKEN);
      const micro = read(await microCorpusTrees(EXAMPLE_RUN_TOKEN));
      expect(micro.variables.filter((name) => name !== written)).toEqual([]);
      // The name the seed writes is the one the placeholder fixture renders.
      expect(micro.variables).toContain(written);
    });

    // A chain calls back into the engine on `ENGINE_LOOPBACK`, in its own URIs or through a service
    // address, and `microCopy` rewrites the call to the callee's micro copy.
    test("a micro loopback call addresses its callee's micro copy", async () => {
      const classic = read(await fixtureTrees(corpusChainNames(), EXAMPLE_RUN_TOKEN), services(serviceTrees(EXAMPLE_RUN_TOKEN)));
      const micro = read(await microCorpusTrees(EXAMPLE_RUN_TOKEN), services(microServiceTrees(EXAMPLE_RUN_TOKEN)));
      expect(classic.loopbacks.length).toBeGreaterThan(0);
      expect(classic.loopbacks.filter((each) => !classic.triggerPaths.includes(each))).toEqual([]);
      expect(micro.loopbacks).toHaveLength(classic.loopbacks.length);
      expect(micro.loopbacks.filter((each) => !micro.triggerPaths.includes(each))).toEqual([]);
    });

    // The suite calls a corpus chain on the engine's own port, so the corpus writes no gateway route
    // and keeps every trigger internal.
    test("every HTTP trigger the corpus seeds is internal", async () => {
      const external = (trees: Array<[string, RenderedTree]>) =>
        trees.flatMap(([name, tree]) => {
          const { document } = readFixtureDocument(name, tree);
          return elementsOf(document)
            .filter((element) => element.type === "http-trigger" && element.properties?.externalRoute !== false)
            .map((element) => `${name}: ${String(element.name)}`);
        });
      const classic = await fixtureTrees(corpusChainNames(), EXAMPLE_RUN_TOKEN);
      expect(classic.length).toBeGreaterThan(0);
      expect(external(classic)).toEqual([]);
      expect(external(await microCorpusTrees(EXAMPLE_RUN_TOKEN))).toEqual([]);
    });
  });

  test.describe("broker corpus", () => {
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
    test("the copies share no chain, element, path, or broker address", () => {
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
    test("every micro reference names an entity of the micro copy", () => {
      const microServices = microBrokerServiceTrees(EXAMPLE_RUN_TOKEN);
      const micro = read(microBrokerTrees(EXAMPLE_RUN_TOKEN));
      const known = new Set([...micro.chainIds, ...micro.elementIds, ...uuidsOf(microServices)]);
      expect(microServices.length).toBeGreaterThan(0);
      expect([...new Set(micro.references)].filter((id) => !known.has(id))).toEqual([]);
      expect(uuidsOf(microServices).filter((id) => uuidsOf(brokerServiceTrees(EXAMPLE_RUN_TOKEN)).includes(id))).toEqual([]);
    });

    // An asynchronous operation consumes from or sends to its element's operation path, so that path is a broker address.
    test("the micro copy renames every asynchronous operation address", () => {
      const operationAddresses = (trees: Array<[string, RenderedTree]>) =>
        trees.flatMap(([name, tree]) =>
          elementsOf(readFixtureDocument(name, tree).document)
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
      expect(micro).toEqual(classic.map((each) => `${each}${MICRO_ADDRESS_SUFFIX}`));
    });

    // Both teardowns run side by side in `suite-teardown`, and each deletes the run's consumer groups of
    // its own copy only, the groups a spec opens on a copy's topic included.
    test("each address, and a name built from one, keeps its copy", () => {
      const classic = Object.values(brokerAddresses(brokerTrees(EXAMPLE_RUN_TOKEN))).flat();
      const micro = Object.values(brokerAddresses(microBrokerTrees(EXAMPLE_RUN_TOKEN))).flat();
      expect(micro.filter((name) => !isMicroAddress(name) || !name.includes(EXAMPLE_RUN_TOKEN))).toEqual([]);
      expect(micro.map((name) => `${name}-consumer`).filter((name) => !isMicroAddress(name))).toEqual([]);
      expect(classic.filter((name) => isMicroAddress(name) || !name.includes(EXAMPLE_RUN_TOKEN))).toEqual([]);
      expect(classic.map((name) => `${name}-consumer`).filter((name) => isMicroAddress(name))).toEqual([]);
    });

    test("brokerChain finds the micro copy through the state file", () => {
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
  });
});
