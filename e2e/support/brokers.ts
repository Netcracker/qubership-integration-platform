/**
 * The broker fixture corpus: imported once, deployed once, torn down once — outside the shared
 * corpus in `support/corpus.ts` on purpose.
 *
 * The engine runs pre-deploy connectivity checks by default, so a broker chain sitting in
 * `fixtures/chains/` would stall the whole corpus whenever a broker is down. `fixtures/brokers/` is
 * imported only by the `brokers-seed` project, which the `brokers` project depends on, so a broker
 * outage fails one project rather than every one of them.
 *
 * Nothing on either side of a broker chain declares its topology. Camel's `spring-rabbitmq` defaults
 * `autoDeclareProducer` to `false` and `elements/rabbitmq-trigger-2/template.hbs` pins
 * `autoDeclare=false`, so the consumer declares neither the queue, the binding, nor the
 * `x-dead-letter-*` arguments. And `KafkaTopicAndConnectionCheckAction` throws a **retriable**
 * exception when a topic is absent, so a deploy started before the topic exists sits at
 * `PROCESSING` with `PREDEPLOY_CHECK_ERROR` and retries every 30 s rather than failing outright.
 * This module is what creates both before the seed deploys anything, read off the fixture elements
 * themselves rather than off a second, parallel declaration that could drift from the element that
 * uses it.
 */
import path from "node:path";
import { fileURLToPath } from "node:url";
import fs from "node:fs";
import { expect } from "@playwright/test";
import { Kafka, type Admin } from "kafkajs";
import amqp from "amqplib";
import Client from "ssh2-sftp-client";
import type { Catalog, LoggingProperties } from "./catalog.js";
import type { Env, Overlay } from "../env/index.js";
import { kafkaHostBrokers, pubsubHostUrl, rabbitmqHostUrl, sftpHostAddress } from "../env/containers.js";
import { BROKERS_MICRO_CORPUS_STATE_FILE } from "../env/k8s.js";
import { stateFileName } from "../env/target.js";
import {
  assembleTrees,
  asyncOperationOf,
  BROKER_SERVICE_FIXTURE_DIR,
  BROKERS_FIXTURE_DIR,
  chainFixtureNames,
  corpusFixtureNames,
  elementsOf,
  isMicroAddress,
  microCopy,
  readFixtureDocument,
  renderFixture,
  type RenderedTree,
} from "../fixtures/templating.js";
import {
  corpusRequestTimeout,
  deleteService,
  deleteServiceRaw,
  deployCorpus,
  importServices,
  projectUse,
  waitForDeployed,
  waitForRoutes,
  waitForUndeployed,
  type ProjectUse,
  type SeedChain,
  type SeededService,
} from "./corpus.js";
import { deployToDomain, tokenedDomain } from "./deployable.js";
import { keepEntities } from "./fixtures.js";
import { noteChain } from "./diagnostics.js";
import { sleep } from "./poll.js";
import { readStateFile, writeStateFile } from "./state-file.js";

export { BROKERS_FIXTURE_DIR };

const HERE = path.dirname(fileURLToPath(import.meta.url));

/** Where the brokers seed leaves what it imported, mirroring `support/corpus.ts`'s `CORPUS_STATE_FILE`. */
export const BROKERS_CORPUS_STATE_FILE = path.join(HERE, "..", stateFileName(".e2e-brokers-corpus.json"));

/** The same session logging the shared corpus deploys with: `sftp-trigger-2` has no other observable. */
export const BROKERS_LOGGING: LoggingProperties = {
  sessionsLoggingLevel: "DEBUG",
  logLoggingLevel: "INFO",
  logPayload: ["HEADERS", "PROPERTIES", "BODY"],
  logPayloadEnabled: false,
  dptEventsEnabled: false,
  maskingEnabled: false,
  sessionLogDetails: "OFF",
};

// Host-side addresses for the specs' own producers and consumers, read once from the per-target
// table in `env/containers.ts`. The chain fixtures address the in-cluster listener instead
// (`kafka:29092`).
export const KAFKA_HOST_BROKERS = kafkaHostBrokers();
export const PUBSUB_HOST_URL = pubsubHostUrl();
export const RABBITMQ_HOST_URL = rabbitmqHostUrl();
/** The SFTP server's one user, host-side. The chains address the same server as `sftp-server:22`. */
export const SFTP_HOST_CONFIG = {
  ...sftpHostAddress(),
  username: "e2e",
  password: "e2epass",
};

function splitNames(value: unknown): string[] {
  if (typeof value !== "string") return [];
  return value.split(",").map((each) => each.trim()).filter(Boolean);
}

/**
 * Every Kafka topic a `kafka-trigger-2` or `kafka-sender-2` element names, or a Kafka operation
 * addresses, across the given documents.
 */
export function kafkaTopicsIn(documents: readonly Record<string, unknown>[]): string[] {
  const topics = new Set<string>();
  for (const document of documents) {
    for (const element of elementsOf(document)) {
      const operation = asyncOperationOf(element);
      if (operation?.protocol === "kafka") {
        for (const topic of splitNames(operation.path)) topics.add(topic);
      }
      if (element.type !== "kafka-trigger-2" && element.type !== "kafka-sender-2") continue;
      for (const topic of splitNames(element.properties?.topics)) topics.add(topic);
    }
  }
  return [...topics].sort();
}

/** Every consumer group a `kafka-trigger-2` or a Kafka `async-api-trigger` element names, across the given documents. */
export function kafkaGroupsIn(documents: readonly Record<string, unknown>[]): string[] {
  const groups = new Set<string>();
  for (const document of documents) {
    for (const element of elementsOf(document)) {
      const operation = asyncOperationOf(element);
      let groupId: unknown;
      if (element.type === "kafka-trigger-2") groupId = element.properties?.groupId;
      else if (element.type === "async-api-trigger" && operation?.protocol === "kafka") groupId = operation.groupId;
      if (typeof groupId === "string" && groupId) groups.add(groupId);
    }
  }
  return [...groups].sort();
}

/** One queue a `rabbitmq-trigger-2` element declares, with the binding and dead-letter setup it needs. */
export interface RabbitmqQueue {
  queue: string;
  exchange: string;
  exchangeType: string;
  routingKey: string;
  deadLetterExchange?: string;
  deadLetterExchangeType?: string;
  deadLetterQueue?: string;
  deadLetterRoutingKey?: string;
}

export interface RabbitmqTopology {
  queues: RabbitmqQueue[];
  /** Exchanges a `rabbitmq-sender-2` element publishes to, decorative for anything but the publish. */
  senderExchanges: string[];
}

/**
 * Every RabbitMQ object a `rabbitmq-trigger-2` or `rabbitmq-sender-2` element needs, or an AMQP
 * operation does, from the given documents. An `async-api-trigger` consumes its queues off a fanout
 * exchange named by the operation path, and a `service-call` publishes to that exchange.
 */
export function rabbitmqTopologyIn(documents: readonly Record<string, unknown>[]): RabbitmqTopology {
  const queues: RabbitmqQueue[] = [];
  const senderExchanges = new Set<string>();
  for (const document of documents) {
    for (const element of elementsOf(document)) {
      const properties = element.properties ?? {};
      const operation = asyncOperationOf(element);
      if (element.type === "rabbitmq-trigger-2") {
        const exchange = String(properties.exchange ?? "");
        const exchangeType = String(properties.exchangeType ?? "direct");
        const routingKey = String(properties.routingKey ?? "");
        const deadLetterExchange = properties.deadLetterExchange as string | undefined;
        for (const queue of splitNames(properties.queues)) {
          queues.push({
            queue,
            exchange,
            exchangeType,
            routingKey,
            deadLetterExchange,
            deadLetterExchangeType: properties.deadLetterExchangeType as string | undefined,
            deadLetterQueue: properties.deadLetterQueue as string | undefined,
            deadLetterRoutingKey: properties.deadLetterRoutingKey as string | undefined,
          });
        }
      } else if (element.type === "rabbitmq-sender-2") {
        const exchange = properties.exchange;
        if (typeof exchange === "string" && exchange) senderExchanges.add(exchange);
      } else if (operation?.protocol === "amqp") {
        const exchange = String(operation.path ?? "");
        if (element.type === "service-call") {
          if (exchange) senderExchanges.add(exchange);
          continue;
        }
        for (const queue of splitNames(operation.queues)) {
          queues.push({ queue, exchange, exchangeType: "fanout", routingKey: "" });
        }
      }
    }
  }
  return { queues, senderExchanges: [...senderExchanges].sort() };
}

/** The `Overlay` names the given documents' elements need, so the seed starts only what a fixture uses. */
function overlaysNeededBy(documents: readonly Record<string, unknown>[]): Overlay[] {
  const needed = new Set<Overlay>();
  for (const document of documents) {
    for (const element of elementsOf(document)) {
      const protocol = asyncOperationOf(element)?.protocol;
      if (element.type === "kafka-trigger-2" || element.type === "kafka-sender-2" || protocol === "kafka") {
        needed.add("kafka");
      }
      if (element.type === "rabbitmq-trigger-2" || element.type === "rabbitmq-sender-2" || protocol === "amqp") {
        needed.add("rabbitmq");
      }
      if (element.type === "pubsub-trigger" || element.type === "pubsub-sender") needed.add("pubsub");
      if (
        element.type === "sftp-trigger-2" ||
        element.type === "sftp-download" ||
        element.type === "sftp-upload"
      ) {
        needed.add("sftp");
      }
    }
  }
  return [...needed].sort();
}

/** One `pubsub-trigger` subscription, and the topic it has to be bound to at creation time. */
export interface PubsubSubscription {
  projectId: string;
  topic: string;
  subscription: string;
}

export interface PubsubTopology {
  subscriptions: PubsubSubscription[];
  /** Topics a `pubsub-sender` element publishes to. No subscription of its own is implied. */
  senderTopics: Array<{ projectId: string; topic: string }>;
}

/**
 * Every pub/sub object a `pubsub-trigger` or `pubsub-sender` element needs, from the given
 * documents.
 *
 * `pubsub-trigger`'s schema carries no topic field at all — only `destinationName`, a subscription
 * name — so there is no element property this function can read a topic out of. The fixtures name
 * the topic and the subscription with the same string: a topic and a subscription are different
 * resource types in Pub/Sub, so the two names do not collide, and reusing `destinationName` keeps
 * the topology derivable from the element the way `kafkaTopicsIn` and `rabbitmqTopologyIn` are,
 * rather than adding a second, driftable declaration this schema has no room for.
 */
function pubsubTopologyIn(documents: readonly Record<string, unknown>[]): PubsubTopology {
  const subscriptions: PubsubSubscription[] = [];
  const senderTopics: Array<{ projectId: string; topic: string }> = [];
  for (const document of documents) {
    for (const element of elementsOf(document)) {
      const properties = element.properties ?? {};
      const projectId = properties.projectId;
      const destinationName = properties.destinationName;
      if (typeof projectId !== "string" || !projectId || typeof destinationName !== "string" || !destinationName) {
        continue;
      }
      if (element.type === "pubsub-trigger") {
        subscriptions.push({ projectId, topic: destinationName, subscription: destinationName });
      } else if (element.type === "pubsub-sender") {
        senderTopics.push({ projectId, topic: destinationName });
      }
    }
  }
  return { subscriptions, senderTopics };
}

async function pubsubRequest(path: string, init: RequestInit = {}): Promise<Response> {
  return fetch(`${PUBSUB_HOST_URL}${path}`, init);
}

/**
 * Creates every topic and subscription the topology names, then confirms each exists.
 *
 * A subscription only receives what is published **after** it exists — Pub/Sub does not replay a
 * topic's backlog to a subscription created later, the same "create the binding before producing"
 * rule `ensureRabbitmqTopology` follows for its queue-to-exchange bindings. So the seed creates the
 * subscription before any spec publishes, not merely the topic.
 */
export async function ensurePubsubTopology(topology: PubsubTopology): Promise<void> {
  const topics = new Set<string>();
  for (const each of topology.senderTopics) topics.add(`${each.projectId}/${each.topic}`);
  for (const each of topology.subscriptions) topics.add(`${each.projectId}/${each.topic}`);
  if (topics.size === 0 && topology.subscriptions.length === 0) return;

  for (const key of topics) {
    const [projectId, topic] = key.split("/");
    const response = await pubsubRequest(`/v1/projects/${projectId}/topics/${topic}`, { method: "PUT" });
    if (!response.ok && response.status !== 409) {
      throw new Error(`creating pub/sub topic ${topic} answered ${response.status}: ${await response.text()}`);
    }
  }
  for (const entry of topology.subscriptions) {
    const response = await pubsubRequest(
      `/v1/projects/${entry.projectId}/subscriptions/${entry.subscription}`,
      {
        method: "PUT",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ topic: `projects/${entry.projectId}/topics/${entry.topic}` }),
      },
    );
    if (!response.ok && response.status !== 409) {
      throw new Error(
        `creating pub/sub subscription ${entry.subscription} answered ${response.status}: ${await response.text()}`,
      );
    }
  }

  // The seed's own assertion, the same reason `ensureKafkaTopics` confirms the broker lists what it
  // created: a spec's own client would otherwise create a missing topic implicitly on first publish,
  // so "the message arrived" alone would not prove this step worked.
  for (const key of topics) {
    const [projectId, topic] = key.split("/");
    const response = await pubsubRequest(`/v1/projects/${projectId}/topics/${topic}`);
    if (!response.ok) throw new Error(`the emulator still does not list topic ${topic} after creating it`);
  }
  for (const entry of topology.subscriptions) {
    const response = await pubsubRequest(`/v1/projects/${entry.projectId}/subscriptions/${entry.subscription}`);
    if (!response.ok) {
      throw new Error(`the emulator still does not list subscription ${entry.subscription} after creating it`);
    }
  }
}

async function deletePubsubTopology(topology: PubsubTopology): Promise<void> {
  for (const entry of topology.subscriptions) {
    await pubsubRequest(`/v1/projects/${entry.projectId}/subscriptions/${entry.subscription}`, {
      method: "DELETE",
    }).catch(() => {});
  }
  const topics = new Set<string>();
  for (const each of topology.senderTopics) topics.add(`${each.projectId}/${each.topic}`);
  for (const each of topology.subscriptions) topics.add(`${each.projectId}/${each.topic}`);
  for (const key of topics) {
    const [projectId, topic] = key.split("/");
    await pubsubRequest(`/v1/projects/${projectId}/topics/${topic}`, { method: "DELETE" }).catch(() => {});
  }
}

/**
 * The SFTP trees a corpus copy's chains write under: the run directory under `/upload` that each
 * `connectUrl` names, `/upload/<run>` in the classic copy and `/upload/<run>-micro` in the micro one.
 */
export function sftpRootsOf(chains: readonly BrokerChain[]): string[] {
  const roots = new Set<string>();
  for (const chain of chains) {
    const connectUrl = chain.broker.connectUrl;
    if (typeof connectUrl !== "string") continue;
    roots.add(connectUrl.slice(connectUrl.indexOf("/")).split("/").slice(0, 3).join("/"));
  }
  return [...roots].sort();
}

/**
 * Removes every file `files.spec.ts` dropped or uploaded for one corpus copy.
 *
 * Every SFTP fixture's `connectUrl` nests under `/upload/{{RUN}}/<fixture>` (`fixtures/brokers/sftp-*`),
 * so one recursive delete of each root is the whole copy's residue: `atmoz/sftp` carries no named
 * volume in `infrastructure/docker/docker-compose.sftp.yml`, so a healthy, left-running container keeps
 * every run's files until something removes them. A run whose corpus never wrote under a root still
 * connects and finds `exists()` answers `false`; the connect is unconditional, so a fixture set that
 * never brought the `sftp` overlay up at all fails here instead of finding nothing.
 */
async function deleteSftpResidue(
  roots: readonly string[],
  timeoutMs = 15_000,
  settleMs = 2_000,
): Promise<void> {
  const client = new Client();
  await client.connect(SFTP_HOST_CONFIG);
  try {
    // Every SFTP fixture sets `autoCreate: true`, so a consumer that has not yet noticed its route
    // is gone can recreate a root between the delete and the moment teardown returns.
    await deleteUntilGone(
      "sftp directory",
      async () => {
        const present: string[] = [];
        for (const dir of roots) if (await client.exists(dir)) present.push(dir);
        return present;
      },
      async (present) => {
        for (const dir of present) await client.rmdir(dir, true);
      },
      timeoutMs,
      settleMs,
    );
  } finally {
    await client.end();
  }
}

/**
 * Deletes what `present` lists until it has listed nothing for `settleMs` straight, and throws
 * naming what is left once `timeoutMs` runs out. A consumer that has not stopped yet can recreate
 * what was just deleted, so the first delete is not trusted.
 */
async function deleteUntilGone(
  what: string,
  present: () => Promise<string[]>,
  remove: (items: string[]) => Promise<void>,
  timeoutMs: number,
  settleMs: number,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  let settledSince: number | null = null;
  while (Date.now() < deadline) {
    const left = await present();
    if (left.length === 0) {
      settledSince ??= Date.now();
      if (Date.now() - settledSince >= settleMs) return;
    } else {
      settledSince = null;
      await remove(left).catch(() => {});
    }
    await sleep(500);
  }
  const left = await present();
  if (left.length) {
    throw new Error(`${what} kept coming back after delete, within ${timeoutMs} ms: ${left.join(", ")}`);
  }
}

/** Every Kafka admin call below shares this clientId and connects to the same host-side listener. */
const KAFKA_ADMIN_CLIENT_ID = "e2e-brokers-seed";

/** Connects a Kafka admin client, runs `fn` against it, and disconnects whether `fn` throws or not. */
async function withKafkaAdmin<T>(fn: (admin: Admin) => Promise<T>): Promise<T> {
  const admin = new Kafka({ clientId: KAFKA_ADMIN_CLIENT_ID, brokers: [KAFKA_HOST_BROKERS] }).admin();
  await admin.connect();
  try {
    return await fn(admin);
  } finally {
    await admin.disconnect();
  }
}

/** How long a created topic may take to show in the broker's topic list. */
const KAFKA_TOPIC_LIST_TIMEOUT = 15_000;

/** Creates every topic, then confirms the broker actually lists it before returning. */
export async function ensureKafkaTopics(topics: readonly string[]): Promise<void> {
  if (topics.length === 0) return;
  await withKafkaAdmin(async (admin) => {
    // No leader wait: KRaft answers CreateTopics before its metadata holds every new topic, and the
    // metadata read that wait sends then fails on UNKNOWN_TOPIC_OR_PARTITION, which kafkajs does not
    // retry (the broker seeds, creating 11 topics each at once, failed on it in two runs in a row).
    await admin.createTopics({ topics: topics.map((topic) => ({ topic, numPartitions: 1 })), waitForLeaders: false });
    // The seed's own assertion: a topic this step failed to create would otherwise surface later, as
    // a deployment the engine's missing-topic check keeps at PROCESSING.
    await expect
      .poll(
        async () => {
          const known = new Set(await admin.listTopics());
          return topics.filter((topic) => !known.has(topic));
        },
        { timeout: KAFKA_TOPIC_LIST_TIMEOUT, message: "topics the broker still does not list after creating them" },
      )
      .toEqual([]);
  });
}

/**
 * Waits until every named consumer group has an attached, `Stable` member.
 *
 * `DEPLOYED` is not consuming, the same fact the RabbitMQ topology section measures a lag for.
 * `KafkaBGConsumer` logs `Starting Kafka consumer on topic: <topic>` when the consumer **thread**
 * starts, not once it has joined its group — measured, a message published right after `seedBrokers`
 * returned without this wait went unconsumed on some topics and not others (`autoOffsetReset:
 * latest` has nothing to redeliver once the record is past), while the same publish always worked
 * once every group had settled. Polling `describeGroups` here closes most of that gap for Kafka. A group
 * turns `Stable` a few milliseconds before its consumer looks up the offset to start from, so a case that
 * publishes right after this wait reads its own new topic from `earliest`.
 * RabbitMQ measures the same "`DEPLOYED` is not listening yet" lag but does not poll the management
 * API for it: a durable queue buffers regardless, and three live runs held `sessions.byExternalId`'s
 * own timeout budget over the measured worst-case attach time with no flake.
 */
/** The default budget `waitForKafkaConsumers` polls for, exported so a caller can size its own timeout on it. */
export const KAFKA_CONSUMER_ATTACH_TIMEOUT = 30_000;

export async function waitForKafkaConsumers(
  groupIds: readonly string[],
  timeoutMs = KAFKA_CONSUMER_ATTACH_TIMEOUT,
): Promise<void> {
  if (groupIds.length === 0) return;
  await withKafkaAdmin(async (admin) => {
    const deadline = Date.now() + timeoutMs;
    let lastReport = "no attempt";
    while (Date.now() < deadline) {
      const { groups } = await admin.describeGroups([...groupIds]);
      const notReady = groups.filter((group) => group.state !== "Stable" || group.members.length === 0);
      if (notReady.length === 0) return;
      lastReport = notReady
        .map((group) => `${group.groupId}=${group.state} (${group.members.length} member(s))`)
        .join(", ");
      await sleep(500);
    }
    throw new Error(`kafka consumer group(s) did not attach within ${timeoutMs} ms: ${lastReport}`);
  });
}

/**
 * Deletes every topic, then confirms they stay gone for `settleMs` before returning.
 *
 * The overlay turns broker-side auto-creation off, as production does, so a consumer still running
 * after `undeployAll` returned cannot recreate a topic with its metadata requests. Deletion itself
 * is asynchronous on the broker, which is what the confirmation waits out.
 */
export async function deleteKafkaTopics(
  topics: readonly string[],
  timeoutMs = 15_000,
  settleMs = 2_000,
): Promise<void> {
  if (topics.length === 0) return;
  await withKafkaAdmin(async (admin) => {
    await deleteUntilGone(
      "kafka topic(s)",
      async () => {
        const known = new Set(await admin.listTopics());
        return topics.filter((topic) => known.has(topic));
      },
      async (present) => {
        await admin.deleteTopics({ topics: present });
      },
      timeoutMs,
      settleMs,
    );
  });
}

/**
 * How long the micro copy's consumer groups get to empty after its domain is deleted. Measured on
 * DD k8s: the groups still had members 15 s after the delete, and the teardown that waited for them
 * took 78 s in all.
 */
export const MICRO_GROUP_LEAVE_TIMEOUT = 120_000;

/**
 * Deletes every consumer group whose id carries the run token.
 *
 * Not just `kafkaGroupsIn`'s trigger-declared groups: a spec's own verification consumer also opens
 * one — `kafka.spec.ts`'s kafka-sender-2 case and `async-api.spec.ts`'s kafka service-call cases —
 * and every fixture and spec-side group id is named with the run token by the same convention
 * `deleteKafkaTopics` relies on for topics. `admin.deleteGroups` only removes an empty group, so this
 * runs after the corpus is undeployed and its topics are gone — but "undeployed" is not "empty":
 * `KafkaJS` throws `KafkaJSDeleteGroupsError` for a group a member has not finished leaving yet, the
 * same wind-down asynchrony `deleteKafkaTopics` already retries through. So this redeletes whatever
 * is still listed on the next pass, rather than letting one busy group fail the whole teardown.
 * `suite-teardown` removes the classic and the micro copy side by side, so each removes only the
 * groups of its own copy (`micro`), and a group the other copy still consumes in is left to it.
 */
async function deleteKafkaConsumerGroups(
  run: string,
  micro: boolean,
  timeoutMs = micro ? MICRO_GROUP_LEAVE_TIMEOUT : 15_000,
): Promise<void> {
  await withKafkaAdmin(async (admin) => {
    const deadline = Date.now() + timeoutMs;
    let lastFailure = "no attempt";
    while (Date.now() < deadline) {
      const { groups } = await admin.listGroups();
      const ours = groups
        .map((group) => group.groupId)
        .filter((id) => id.includes(run) && isMicroAddress(id) === micro);
      if (ours.length === 0) return;
      try {
        await admin.deleteGroups(ours);
        return;
      } catch (cause) {
        lastFailure = String(cause);
      }
      await sleep(500);
    }
    throw new Error(`kafka consumer group(s) for run ${run} were not deleted within ${timeoutMs} ms: ${lastFailure}`);
  });
}

/**
 * A channel with a no-op `error` listener attached.
 *
 * amqplib closes a channel on a protocol-level failure — a redeclare with a mismatched argument
 * answers `406 PRECONDITION_FAILED`, say — by emitting `'error'` on the channel from inside
 * `accept()`'s `ChannelClose` case (`amqplib/lib/channel.js`), synchronously, in the same call stack
 * as the socket's own `'data'` handler. That is not a promise rejection an `await` can catch:
 * Node's `EventEmitter` re-throws an `'error'` event with no listener out of the call that emitted
 * it, which crashes the worker before any `try`/`catch` around the failed operation runs. Measured
 * against the local broker with the exact shape both callers below use: a queue redeclared with a
 * mismatched argument, on a channel with no `'error'` listener, exits the process on `UNCAUGHT
 * EXCEPTION: Error: Channel closed by server: 406 …` before either function's `catch` executes. The
 * listener itself does nothing — attaching one is what keeps Node from treating the event as
 * unhandled.
 */
export async function openChannel(connection: Awaited<ReturnType<typeof amqp.connect>>): Promise<amqp.Channel> {
  const channel = await connection.createChannel();
  channel.on("error", () => {});
  return channel;
}

/**
 * Declares every queue, exchange, binding and dead-letter object the topology names, then confirms
 * each queue exists.
 *
 * Dead-lettering lands on the **queue**'s arguments, never on the element: Camel applies
 * `deadLetterExchange`/`deadLetterQueue`/`deadLetterRoutingKey` only when it declares the queue
 * itself, and the trigger's `autoDeclare=false` means it never does.
 */
export async function ensureRabbitmqTopology(topology: RabbitmqTopology): Promise<void> {
  if (topology.queues.length === 0 && topology.senderExchanges.length === 0) return;
  const connection = await amqp.connect(RABBITMQ_HOST_URL);
  const channel = await openChannel(connection);
  try {
    for (const exchange of topology.senderExchanges) {
      await channel.assertExchange(exchange, "direct", { durable: true });
    }
    for (const entry of topology.queues) {
      await channel.assertExchange(entry.exchange, entry.exchangeType, { durable: true });
      if (entry.deadLetterExchange) {
        await channel.assertExchange(entry.deadLetterExchange, entry.deadLetterExchangeType ?? "direct", {
          durable: true,
        });
      }
      const args: Record<string, string> = {};
      if (entry.deadLetterExchange) args["x-dead-letter-exchange"] = entry.deadLetterExchange;
      if (entry.deadLetterRoutingKey) args["x-dead-letter-routing-key"] = entry.deadLetterRoutingKey;
      await channel.assertQueue(entry.queue, { durable: true, arguments: args });
      await channel.bindQueue(entry.queue, entry.exchange, entry.routingKey);
      if (entry.deadLetterQueue && entry.deadLetterExchange) {
        await channel.assertQueue(entry.deadLetterQueue, { durable: true });
        await channel.bindQueue(entry.deadLetterQueue, entry.deadLetterExchange, entry.deadLetterRoutingKey ?? "");
      }
    }
    // The seed's own assertion, the way `ensureKafkaTopics` proves the broker holds the topic: a
    // chain against a missing queue reports DEPLOYED with a null error while the listener
    // restart-loops, so nothing downstream would say this step failed.
    for (const entry of topology.queues) await channel.checkQueue(entry.queue);
  } finally {
    // `.catch(() => {})` on both closes: a redeclare against an object a previous, undeleted run
    // left with different arguments answers `PRECONDITION_FAILED` above and closes the channel as
    // part of the AMQP protocol (openChannel's own comment is what keeps that from crashing the
    // process first), and closing an already-closed channel throws a second, uninformative error
    // that a bare `finally` would let replace the first one — and skip `connection.close()`
    // entirely.
    await channel.close().catch(() => {});
    await connection.close().catch(() => {});
  }
}

/**
 * Deletes every queue, exchange and dead-letter object the topology names, and reports every
 * object it could not delete instead of only logging it.
 *
 * `deleteQueue`/`deleteExchange` against an object that was never declared are idempotent — RabbitMQ
 * answers them with no error, which is why a partial-seed teardown (the state file is written before
 * `ensureRabbitmqTopology` runs, so a seed that died mid-topology can name an object that was never
 * created) needs no special case here. What does close the **channel**, the way `openChannel`'s
 * comment describes for `ensureRabbitmqTopology`, is a genuine protocol-level error — deleting an
 * exclusive or in-use object under conditions RabbitMQ refuses, say. Every delete after that one
 * would reject on the closed channel too, so a failure reopens a fresh channel, with its own
 * listener, before the next object rather than losing the rest of the topology to one bad delete.
 * The caller's own `.catch()` only fires on a rejected promise, so this throws once every object has
 * been attempted, naming every one that failed, instead of resolving regardless of how many deletes
 * actually succeeded.
 */
async function deleteRabbitmqTopology(topology: RabbitmqTopology): Promise<void> {
  if (topology.queues.length === 0 && topology.senderExchanges.length === 0) return;
  const connection = await amqp.connect(RABBITMQ_HOST_URL);
  let channel = await openChannel(connection);
  const failures: string[] = [];
  const attempt = async (what: string, op: (channel: amqp.Channel) => Promise<unknown>): Promise<void> => {
    try {
      await op(channel);
    } catch (cause) {
      failures.push(`${what} was not deleted: ${String(cause)}`);
      channel = await openChannel(connection);
    }
  };
  try {
    for (const entry of topology.queues) {
      await attempt(`rabbitmq queue ${entry.queue}`, (channel) => channel.deleteQueue(entry.queue));
      if (entry.deadLetterQueue) {
        const deadLetterQueue = entry.deadLetterQueue;
        await attempt(`rabbitmq dead-letter queue ${deadLetterQueue}`, (channel) => channel.deleteQueue(deadLetterQueue));
      }
      if (entry.deadLetterExchange) {
        const deadLetterExchange = entry.deadLetterExchange;
        await attempt(`rabbitmq dead-letter exchange ${deadLetterExchange}`, (channel) =>
          channel.deleteExchange(deadLetterExchange),
        );
      }
      await attempt(`rabbitmq exchange ${entry.exchange}`, (channel) => channel.deleteExchange(entry.exchange));
    }
    for (const exchange of topology.senderExchanges) {
      await attempt(`rabbitmq sender exchange ${exchange}`, (channel) => channel.deleteExchange(exchange));
    }
  } finally {
    await channel.close().catch(() => {});
    await connection.close().catch(() => {});
  }
  if (failures.length > 0) throw new Error(failures.join("; "));
}

/**
 * The element types a broker fixture is built around, one per chain (the trigger or the sender under
 * test) — what `describe()` reads a chain's own addressing properties off, so a spec asks for a
 * topic, exchange, queue or connect URL instead of restating it beside `tokenized(corpus.run, …)`.
 */
const BROKER_ELEMENT_TYPES = new Set([
  "kafka-trigger-2",
  "kafka-sender-2",
  "rabbitmq-trigger-2",
  "rabbitmq-sender-2",
  "pubsub-trigger",
  "pubsub-sender",
  "sftp-trigger-2",
  "sftp-download",
  "sftp-upload",
  "async-api-trigger",
  "service-call",
]);

/** One broker fixture chain, addressed the way `SeedChain` is — `contextPath` is `""` for a chain no HTTP call reaches. */
export interface BrokerChain extends SeedChain {
  /**
   * The chain's own broker element properties — already run-token-substituted, since `describe()`
   * reads them off the same rendered document `assembleCorpus` produced. A spec reads
   * `chain.broker.topics`, `.exchange`, `.routingKey`, `.deadLetterExchange`, `.destinationName`,
   * `.connectUrl` or whatever else the fixture set, rather than re-deriving the name from a string
   * literal that could drift from the fixture that actually declares it.
   */
  broker: Record<string, unknown>;
}

function describe(fixture: string, document: Record<string, unknown>): BrokerChain {
  const elements = elementsOf(document);
  const trigger = elements.find((each) => each.type === "http-trigger");
  const contextPath = (trigger?.properties?.contextPath as string | undefined) ?? "";
  const brokerElement = elements.find((each) => BROKER_ELEMENT_TYPES.has(each.type as string));

  const byName = new Map<string, string>();
  for (const element of elements) {
    if (typeof element.name !== "string" || typeof element.id !== "string") continue;
    const already = byName.get(element.name);
    if (already !== undefined) {
      throw new Error(
        `broker fixture ${fixture} names two elements ${JSON.stringify(element.name)} ` +
          `(${already} and ${element.id}): a spec addresses an element by name`,
      );
    }
    byName.set(element.name, element.id);
  }

  return {
    fixture,
    id: String(document.id),
    name: String(document.name),
    contextPath,
    elements: Object.fromEntries(byName),
    broker: brokerElement?.properties ?? {},
  };
}

export interface SeededBrokerCorpus {
  run: string;
  chains: BrokerChain[];
  /** The AsyncAPI services the chains name, imported before them. */
  services: SeededService[];
  kafkaTopics: string[];
  rabbitmqTopology: RabbitmqTopology;
  pubsubTopology: PubsubTopology;
}

/** The micro copy of the broker corpus, and the micro domain it is deployed to. */
export interface MicroBrokerCorpus extends SeededBrokerCorpus {
  domain: string;
}

/** The broker fixtures rendered with `run`, as the classic copy holds them. */
export function brokerTrees(
  run: string,
  fixtures: readonly string[] = corpusFixtureNames([BROKERS_FIXTURE_DIR]),
): Array<[string, RenderedTree]> {
  return fixtures.map((name) => [name, renderFixture(BROKERS_FIXTURE_DIR, name, run)]);
}

/** The broker fixtures rendered with `run` and turned into the micro copy, whose addresses are its own. */
export function microBrokerTrees(run: string): Array<[string, RenderedTree]> {
  return brokerTrees(run).map(([name, tree]) => [name, microCopy(name, tree)]);
}

/** The services the broker chains name, rendered with `run`. */
export function brokerServiceTrees(run: string): Array<[string, RenderedTree]> {
  return chainFixtureNames(BROKER_SERVICE_FIXTURE_DIR).map((name) => [
    name,
    renderFixture(BROKER_SERVICE_FIXTURE_DIR, name, run),
  ]);
}

/** The services the micro copy of the broker chains names. */
export function microBrokerServiceTrees(run: string): Array<[string, RenderedTree]> {
  return brokerServiceTrees(run).map(([name, tree]) => [name, microCopy(name, tree)]);
}

function documentsOf(trees: ReadonlyArray<readonly [string, RenderedTree]>): Record<string, unknown>[] {
  return trees.map(([name, tree]) => readFixtureDocument(name, tree).document);
}

/** The chains, services, and topology a copy of the broker corpus declares: pure reads off its documents. */
export function brokerCorpusOf(
  run: string,
  trees: ReadonlyArray<readonly [string, RenderedTree]>,
  services: ReadonlyArray<readonly [string, RenderedTree]>,
): SeededBrokerCorpus {
  const documents = documentsOf(trees);
  return {
    run,
    chains: documents.map((document, index) => describe(trees[index][0], document)),
    services: services.map(([name, tree]) => {
      const { kind, id, document } = readFixtureDocument(name, tree);
      return { kind, id, name: String(document.name) };
    }),
    kafkaTopics: kafkaTopicsIn(documents),
    rabbitmqTopology: rabbitmqTopologyIn(documents),
    pubsubTopology: pubsubTopologyIn(documents),
  };
}

/** One copy of the broker corpus as `importBrokerCopy` imports it. */
interface BrokerCopy {
  trees: ReadonlyArray<readonly [string, RenderedTree]>;
  services: ReadonlyArray<readonly [string, RenderedTree]>;
  archiveName: string;
  /** Where the copy is recorded for its teardown. */
  stateFile: string;
}

/**
 * Brings up the overlays a copy needs, creates its topics and queues, deletes its chains and the
 * services they name, imports the services and then the chains, and raises the chains' logging.
 *
 * Order matters the way it does for the shared corpus, plus one step ahead of it: the topology has
 * to exist **before** the deploy, because `KafkaTopicAndConnectionCheckAction` and a missing
 * RabbitMQ queue are both discovered at deploy time, not at import time.
 */
async function importBrokerCopy(
  catalog: Catalog,
  env: Env,
  corpus: SeededBrokerCorpus,
  { trees, services, archiveName, stateFile }: BrokerCopy,
): Promise<void> {
  for (const overlay of overlaysNeededBy(documentsOf(trees))) await env.ensureOverlay(overlay);

  // Recorded now, before any of the topology below exists, not only once the seed returns. Without
  // the early write, a failure anywhere from here to the return (creating a topic, the import, the
  // deploy, the consumer wait) leaves the run's topics, queues and subscriptions on the broker with
  // nothing on disk naming them: the broker teardown runs whether or not the seed passed, but only
  // the state file tells it what to remove.
  writeStateFile(stateFile, corpus);

  await ensureKafkaTopics(corpus.kafkaTopics);
  await ensureRabbitmqTopology(corpus.rabbitmqTopology);
  await ensurePubsubTopology(corpus.pubsubTopology);

  for (const chain of corpus.chains) await catalog.deleteChain(chain.id).catch(() => {});
  for (const service of corpus.services) await deleteService(catalog, service.kind, service.id).catch(() => {});
  await importServices(catalog, services, `services-${archiveName}`);

  const { archive } = await assembleTrees(trees);
  const response = await catalog.importChains(archive, archiveName, { timeout: corpusRequestTimeout(trees.length) });
  expect(response.status(), `the broker corpus import answered ${response.status()}`).toBe(200);
  const imported = (await response.json()) as { chains: Array<{ id: string; status: string }> };
  expect(
    imported.chains.map((each) => each.id).sort(),
    "the import wrote a different set of broker chains than the fixtures declare",
  ).toEqual(corpus.chains.map((each) => each.id).sort());

  for (const chain of corpus.chains) await catalog.saveLoggingProperties(chain.id, BROKERS_LOGGING);
}

/**
 * Imports the broker fixtures, deploys them to the classic engine, and waits for a route on
 * whichever chains carry an HTTP trigger and for every Kafka consumer group.
 */
export async function seedBrokers(
  catalog: Catalog,
  env: Env,
  run: string,
  fixtures: readonly string[] = corpusFixtureNames([BROKERS_FIXTURE_DIR]),
): Promise<SeededBrokerCorpus> {
  const trees = brokerTrees(run, fixtures);
  const services = brokerServiceTrees(run);
  const corpus = brokerCorpusOf(run, trees, services);
  await importBrokerCopy(catalog, env, corpus, {
    trees,
    services,
    archiveName: `brokers-${run}.zip`,
    stateFile: BROKERS_CORPUS_STATE_FILE,
  });

  await deployCorpus(catalog, corpus.chains);
  await waitForDeployed(catalog, corpus.chains);
  await waitForRoutes(env, corpus.chains);

  // A kafka-trigger-2 chain with no HTTP trigger carries no route for the wait above to gate on, and
  // `DEPLOYED` is not consuming: see `waitForKafkaConsumers`.
  await waitForKafkaConsumers(kafkaGroupsIn(documentsOf(trees)));
  return corpus;
}

/**
 * Imports the micro copy of the broker fixtures and deploys it in one batch to a micro domain of
 * its own, then waits for its routes and its Kafka consumer groups as `seedBrokers` does.
 *
 * A domain apart from `seed-micro`'s: one chain that fails to load takes its whole pod down, and a
 * broker outage would then fail every `runtime-micro` case.
 */
export async function seedMicroBrokers(catalog: Catalog, env: Env, run: string): Promise<MicroBrokerCorpus> {
  const trees = microBrokerTrees(run);
  const services = microBrokerServiceTrees(run);
  const target = tokenedDomain(run, "brokers-micro");
  const corpus: MicroBrokerCorpus = { ...brokerCorpusOf(run, trees, services), domain: target.domain };
  await importBrokerCopy(catalog, env, corpus, {
    trees,
    services,
    archiveName: `brokers-micro-${run}.zip`,
    stateFile: BROKERS_MICRO_CORPUS_STATE_FILE,
  });

  await deployToDomain(catalog, target, corpus.chains);
  await waitForKafkaConsumers(kafkaGroupsIn(documentsOf(trees)));
  return corpus;
}

/**
 * Deletes each chain's logging properties and then the chain, adding a line to `failures` for each
 * one left. A chain whose properties survived is kept, for the reason `teardownCorpus` keeps one.
 */
async function deleteBrokerChains(
  catalog: Catalog,
  chains: readonly BrokerChain[],
  failures: string[],
  { undeployFirst }: { undeployFirst: boolean },
): Promise<void> {
  const kept = new Set<string>();
  for (const chain of chains) {
    await catalog.deleteLoggingProperties(chain.id).catch((cause: unknown) => {
      failures.push(`${chain.name} (${chain.id}) kept its logging properties, so it was kept: ${String(cause)}`);
      kept.add(chain.id);
    });
    if (!undeployFirst) continue;
    await catalog.undeployAll(chain.id).catch((cause: unknown) => {
      console.error(`[teardown] ${chain.name} (${chain.id}) was not undeployed before its delete: ${String(cause)}`);
    });
  }
  for (const chain of chains) {
    if (kept.has(chain.id)) continue;
    // `ChainController.deleteById` answers 404 for an unknown chain, which is what every chain named
    // here looks like when the seed failed before the import — the state file is written early (see
    // `importBrokerCopy`), so it can name chains the corpus never actually created. That is not residue.
    const response = await catalog.raw("delete", `/v1/chains/${chain.id}`);
    if (response.status() === 404) continue;
    if (!response.ok()) {
      failures.push(`${chain.name} (${chain.id}) was not deleted: ${response.status()}`);
    }
  }
}

/**
 * Deletes a copy's services after its chains. A 404 is a service the seed never imported, read the
 * way `deleteBrokerChains` reads one.
 */
async function deleteBrokerServices(catalog: Catalog, services: readonly SeededService[], failures: string[]): Promise<void> {
  for (const service of services) {
    const response = await deleteServiceRaw(catalog, service.kind, service.id);
    if (response.status() === 404) continue;
    if (!response.ok()) failures.push(`the service ${service.name} (${service.id}) was not deleted: ${response.status()}`);
  }
}

/** Deletes a copy's topics, its consumer groups, its RabbitMQ and Pub/Sub topology, and its SFTP trees. */
async function deleteBrokerTopology(corpus: SeededBrokerCorpus, micro: boolean, failures: string[]): Promise<void> {
  await deleteKafkaTopics(corpus.kafkaTopics).catch((cause: unknown) => {
    failures.push(`kafka topics ${corpus.kafkaTopics.join(", ")} were not deleted: ${String(cause)}`);
  });
  await deleteKafkaConsumerGroups(corpus.run, micro).catch((cause: unknown) => {
    failures.push(`kafka consumer groups for run ${corpus.run} were not deleted: ${String(cause)}`);
  });
  await deleteRabbitmqTopology(corpus.rabbitmqTopology).catch((cause: unknown) => {
    failures.push(`rabbitmq topology was not deleted: ${String(cause)}`);
  });
  await deletePubsubTopology(corpus.pubsubTopology).catch((cause: unknown) => {
    failures.push(`pubsub topology was not deleted: ${String(cause)}`);
  });
  const roots = sftpRootsOf(corpus.chains);
  await deleteSftpResidue(roots).catch((cause: unknown) => {
    failures.push(`sftp files under ${roots.join(", ")} were not deleted: ${String(cause)}`);
  });
}

/** Removes `file` when nothing failed, and otherwise throws naming the residue and the kept file. */
function finishTeardown(what: string, corpus: SeededBrokerCorpus, file: string, failures: readonly string[]): void {
  if (failures.length === 0) {
    fs.rmSync(file, { force: true });
    return;
  }
  throw new Error(
    `the ${what} teardown left residue of run ${corpus.run}: ${failures.join("; ")}. ` +
      `${file} is kept, because it is what still names it.`,
  );
}

/** Undeploys and deletes the broker corpus and its services, and drops the topics, queues and files the run created. */
export async function teardownBrokers(catalog: Catalog, corpus: SeededBrokerCorpus): Promise<void> {
  if (keepEntities()) {
    console.log(`[keep] ${corpus.chains.length} broker chains left behind for run ${corpus.run}`);
    for (const chain of corpus.chains) console.log(`[keep]   chain ${chain.name} (${chain.id})`);
    for (const service of corpus.services) console.log(`[keep]   service ${service.name} (${service.id})`);
    return;
  }

  const failures: string[] = [];
  await deleteBrokerChains(catalog, corpus.chains, failures, { undeployFirst: true });
  await deleteBrokerServices(catalog, corpus.services, failures);
  // A consumer the engine has not stopped yet still holds its group, its queue, its subscription or
  // its directory, so the broker topology goes only once the engine runs none of the corpus.
  await waitForUndeployed(catalog, corpus.chains).catch((cause: unknown) => {
    failures.push(`the engine still ran broker chains after their undeploy: ${String(cause)}`);
  });
  await deleteBrokerTopology(corpus, false, failures);
  finishTeardown("brokers seed", corpus, BROKERS_CORPUS_STATE_FILE, failures);
}

/**
 * Deletes the micro copy's logging properties, chains and services, then its domain, then its
 * topology, as `teardownMicroCorpus` and `teardownBrokers` do. It reads only the catalog and the
 * state file, because `suite-teardown` runs it with a classic `env`.
 */
export async function teardownMicroBrokers(catalog: Catalog, corpus: MicroBrokerCorpus): Promise<void> {
  if (keepEntities()) {
    console.log(
      `[keep] ${corpus.chains.length} micro broker chains and domain ${corpus.domain} left behind for run ${corpus.run}`,
    );
    return;
  }

  const failures: string[] = [];
  await deleteBrokerChains(catalog, corpus.chains, failures, { undeployFirst: false });
  await deleteBrokerServices(catalog, corpus.services, failures);
  await catalog.deleteCustomResource(corpus.domain).catch((cause: unknown) => {
    failures.push(`the micro domain ${corpus.domain} was not deleted: ${String(cause)}`);
  });
  await deleteBrokerTopology(corpus, true, failures);
  finishTeardown("brokers-micro seed", corpus, BROKERS_MICRO_CORPUS_STATE_FILE, failures);
}

/** Writes what the brokers seed imported, through a staging file and a rename — the same reason `writeCorpusState` does. */
export function writeBrokersCorpusState(corpus: SeededBrokerCorpus): void {
  writeStateFile(BROKERS_CORPUS_STATE_FILE, corpus);
}

/**
 * One broker fixture chain by the fixture directory it came from — `support/corpus.ts`'s
 * `seedChain`, over the broker corpus instead of the shared one. A micro project gets the chain's
 * micro copy, from the state file its `microDomainState` names (`brokers-micro-seed`'s by default).
 */
export function brokerChain(corpus: SeededBrokerCorpus, fixture: string, use: ProjectUse = projectUse()): BrokerChain {
  const micro = use.engineKind === "micro";
  const copy = micro ? readMicroBrokersCorpusState(use.microDomainState) : corpus;
  const found = copy.chains.find((each) => each.fixture === fixture);
  if (!found) {
    throw new Error(`the seeded broker corpus${micro ? "'s micro copy" : ""} holds no chain from fixture ${fixture}`);
  }
  noteChain({ id: found.id, name: found.name, contextPath: found.contextPath });
  return found;
}

/** What the brokers seed imported, or a failure naming the seed rather than the spec that asked. */
export function readBrokersCorpusState(): SeededBrokerCorpus {
  const corpus = readStateFile<SeededBrokerCorpus>(BROKERS_CORPUS_STATE_FILE);
  if (!corpus) {
    throw new Error(
      `no readable brokers corpus at ${BROKERS_CORPUS_STATE_FILE}: brokers-seed did not run, ran no ` +
        `tests, or was killed partway through writing the file`,
    );
  }
  return corpus;
}

/** The micro copy of the broker corpus `file` records, or a failure naming `brokers-micro-seed`. */
export function readMicroBrokersCorpusState(file: string = BROKERS_MICRO_CORPUS_STATE_FILE): MicroBrokerCorpus {
  const corpus = readStateFile<MicroBrokerCorpus>(file);
  if (!corpus) {
    throw new Error(
      `no readable micro copy of the brokers corpus at ${file}: brokers-micro-seed did not run, ran no ` +
        `tests, or was killed partway through writing the file`,
    );
  }
  return corpus;
}
