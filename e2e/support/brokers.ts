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
import { KAFKA_HOST_BROKERS, PUBSUB_HOST_URL, SFTP_HOST, SFTP_PORT } from "../env/compose.js";
import { assembleCorpus, corpusFixtureNames, BROKERS_FIXTURE_DIR } from "../fixtures/templating.js";
import { deployCorpus, waitForDeployed, waitForRoutes, flatten, type FixtureElement, type SeedChain } from "./corpus.js";
import { keepEntities } from "./fixtures.js";
import { noteChain } from "./diagnostics.js";
import { sleep } from "./poll.js";
import { readStateFile, writeStateFile } from "./state-file.js";

export { BROKERS_FIXTURE_DIR };

const HERE = path.dirname(fileURLToPath(import.meta.url));

/** Where the brokers seed leaves what it imported, mirroring `support/corpus.ts`'s `CORPUS_STATE_FILE`. */
export const BROKERS_CORPUS_STATE_FILE = path.join(HERE, "..", ".e2e-brokers-corpus.json");

/** The same session logging the shared corpus deploys with: `sftp-trigger-2` and `quartz-scheduler` have no other observable. */
export const BROKERS_LOGGING: LoggingProperties = {
  sessionsLoggingLevel: "DEBUG",
  logLoggingLevel: "INFO",
  logPayload: ["HEADERS", "PROPERTIES", "BODY"],
  logPayloadEnabled: false,
  dptEventsEnabled: false,
  maskingEnabled: false,
  sessionLogDetails: "OFF",
};

// Re-exported for the specs that need their own producer or consumer against the host-side
// listener — `env/compose.ts` is what already owns these addresses, to size its overlay readiness
// probes on them, so this module reads them rather than carrying a second copy. The chain fixtures
// themselves address the container-internal listener instead (`kafka:29092`), which is the "two
// addresses for one broker" split this module's own header documents.
export { KAFKA_HOST_BROKERS, PUBSUB_HOST_URL };
export const RABBITMQ_HOST_URL = process.env.QIP_RABBITMQ_URL ?? "amqp://guest:guest@localhost:5672";
/** `docker-compose.sftp.yml`'s one user, host-side. The chains address the same server as `sftp-server:22`. */
export const SFTP_HOST_CONFIG = {
  host: SFTP_HOST,
  port: SFTP_PORT,
  username: "e2e",
  password: "e2epass",
};

function elementsOf(document: Record<string, unknown>): FixtureElement[] {
  const content = (document.content ?? {}) as { elements?: FixtureElement[] };
  return flatten(content.elements ?? []);
}

function splitNames(value: unknown): string[] {
  if (typeof value !== "string") return [];
  return value.split(",").map((each) => each.trim()).filter(Boolean);
}

/** Every Kafka topic a `kafka-trigger-2` or `kafka-sender-2` element names, across the given documents. */
export function kafkaTopicsIn(documents: readonly Record<string, unknown>[]): string[] {
  const topics = new Set<string>();
  for (const document of documents) {
    for (const element of elementsOf(document)) {
      if (element.type !== "kafka-trigger-2" && element.type !== "kafka-sender-2") continue;
      for (const topic of splitNames(element.properties?.topics)) topics.add(topic);
    }
  }
  return [...topics].sort();
}

/** Every consumer group a `kafka-trigger-2` element names, across the given documents. */
function kafkaGroupsIn(documents: readonly Record<string, unknown>[]): string[] {
  const groups = new Set<string>();
  for (const document of documents) {
    for (const element of elementsOf(document)) {
      if (element.type !== "kafka-trigger-2") continue;
      const groupId = element.properties?.groupId;
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

/** Every RabbitMQ object a `rabbitmq-trigger-2` or `rabbitmq-sender-2` element needs, from the given documents. */
export function rabbitmqTopologyIn(documents: readonly Record<string, unknown>[]): RabbitmqTopology {
  const queues: RabbitmqQueue[] = [];
  const senderExchanges = new Set<string>();
  for (const document of documents) {
    for (const element of elementsOf(document)) {
      const properties = element.properties ?? {};
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
      if (element.type === "kafka-trigger-2" || element.type === "kafka-sender-2") needed.add("kafka");
      if (element.type === "rabbitmq-trigger-2" || element.type === "rabbitmq-sender-2") needed.add("rabbitmq");
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
 * Removes every file `files.spec.ts` dropped or uploaded for this run.
 *
 * Every SFTP fixture's `connectUrl` nests under `/upload/{{RUN}}/<fixture>` (`fixtures/brokers/sftp-*`),
 * so one recursive delete of `/upload/<run>` is the whole run's residue: `atmoz/sftp` carries no
 * named volume in `infrastructure/docker-compose.sftp.yml`, so a healthy, left-running container
 * keeps every run's files until something removes them. A run whose corpus never wrote under the
 * directory still connects and finds `exists()` answers `false`; the connect is unconditional, so a
 * fixture set that never brought the `sftp` overlay up at all fails here instead of finding nothing.
 */
async function deleteSftpResidue(
  run: string,
  timeoutMs = 15_000,
  settleMs = 2_000,
): Promise<void> {
  const client = new Client();
  await client.connect(SFTP_HOST_CONFIG);
  try {
    const dir = `/upload/${run}`;
    // Every SFTP fixture sets `autoCreate: true`, so a consumer that has not yet noticed its route
    // is gone can recreate this directory between the delete and the moment teardown returns.
    await deleteUntilGone(
      "sftp directory",
      async () => ((await client.exists(dir)) ? [dir] : []),
      async () => {
        await client.rmdir(dir, true);
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

/** Creates every topic, then confirms the broker actually lists it before returning. */
export async function ensureKafkaTopics(topics: readonly string[]): Promise<void> {
  if (topics.length === 0) return;
  await withKafkaAdmin(async (admin) => {
    await admin.createTopics({ topics: topics.map((topic) => ({ topic, numPartitions: 1 })) });
    // The seed's own assertion. With broker-side auto-creation on, a spec's own `kafkajs` producer
    // would create a missing topic itself, so "the message arrived" alone proves nothing about this
    // step.
    const known = new Set(await admin.listTopics());
    const missing = topics.filter((topic) => !known.has(topic));
    if (missing.length) {
      throw new Error(`the broker still does not list ${missing.join(", ")} after creating them`);
    }
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
 * once every group had settled. Polling `describeGroups` here is what closes that gap for Kafka.
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
 * Deletes every topic, then confirms none of them come back before returning.
 *
 * Measured: a plain `deleteTopics` with no confirmation left every corpus trigger topic back after
 * a full brokers run — `undeployAll` returning is not the consumer thread stopping (the same
 * asynchronous gap `waitForKafkaConsumers` waits out on the way up), and broker-side auto-creation
 * is on by default (the overlay sets no `KAFKA_AUTO_CREATE` override), so that still-running
 * consumer's next poll recreates the very topic this just deleted with nothing but its own metadata
 * request.
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
 */
async function deleteKafkaConsumerGroups(
  run: string,
  timeoutMs = 15_000,
): Promise<void> {
  await withKafkaAdmin(async (admin) => {
    const deadline = Date.now() + timeoutMs;
    let lastFailure = "no attempt";
    while (Date.now() < deadline) {
      const { groups } = await admin.listGroups();
      const ours = groups.map((group) => group.groupId).filter((id) => id.includes(run));
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
  kafkaTopics: string[];
  rabbitmqTopology: RabbitmqTopology;
  pubsubTopology: PubsubTopology;
}

/**
 * Imports the broker fixtures, brings up the overlays they need, creates their topics and queues,
 * raises their logging, deploys them, and waits for a route on whichever chains carry an HTTP
 * trigger.
 *
 * Order matters the way it does for the shared corpus, plus one step ahead of it: the topology has
 * to exist **before** the deploy, because `KafkaTopicAndConnectionCheckAction` and a missing
 * RabbitMQ queue are both discovered at deploy time, not at import time.
 */
export async function seedBrokers(
  catalog: Catalog,
  env: Env,
  run: string,
  fixtures: readonly string[] = corpusFixtureNames([BROKERS_FIXTURE_DIR]),
): Promise<SeededBrokerCorpus> {
  const { archive, documents } = await assembleCorpus(fixtures, run, [BROKERS_FIXTURE_DIR]);
  const chains = documents.map((each, index) => describe(fixtures[index], each.document));
  const rawDocuments = documents.map((each) => each.document);

  for (const overlay of overlaysNeededBy(rawDocuments)) await env.ensureOverlay(overlay);

  const kafkaTopics = kafkaTopicsIn(rawDocuments);
  const rabbitmqTopology = rabbitmqTopologyIn(rawDocuments);
  const pubsubTopology = pubsubTopologyIn(rawDocuments);

  // Written now, before any of the topology below exists, not only once this function returns.
  // `chains`, `kafkaTopics`, `rabbitmqTopology` and `pubsubTopology` are already fixed at this point
  // — pure reads off the fixture documents — so this is the same object the function returns.
  // Without the early write, a failure anywhere from here to the return (creating a topic, the
  // import, the deploy, the consumer wait) leaves the run's topics, queues and subscriptions on the
  // broker with nothing on disk naming them: `brokers-seed-teardown` runs whether or not this test
  // passed, but only `readBrokersCorpusState` tells it what to remove.
  const corpus: SeededBrokerCorpus = { run, chains, kafkaTopics, rabbitmqTopology, pubsubTopology };
  writeBrokersCorpusState(corpus);

  await ensureKafkaTopics(kafkaTopics);
  await ensureRabbitmqTopology(rabbitmqTopology);
  await ensurePubsubTopology(pubsubTopology);

  for (const chain of chains) await catalog.deleteChain(chain.id).catch(() => {});

  const response = await catalog.importChains(archive, `brokers-${run}.zip`);
  expect(response.status(), `the broker corpus import answered ${response.status()}`).toBe(200);
  const imported = (await response.json()) as { chains: Array<{ id: string; status: string }> };
  expect(
    imported.chains.map((each) => each.id).sort(),
    "the import wrote a different set of broker chains than the fixtures declare",
  ).toEqual(chains.map((each) => each.id).sort());

  for (const chain of chains) await catalog.saveLoggingProperties(chain.id, BROKERS_LOGGING);

  await deployCorpus(catalog, chains);
  await waitForDeployed(catalog, chains);

  const routed = chains.filter((each) => each.contextPath !== "");
  if (routed.length) await waitForRoutes(env, routed);

  // A kafka-trigger-2 chain with no HTTP trigger carries no route for the wait above to gate on, and
  // `DEPLOYED` is not consuming: see `waitForKafkaConsumers`.
  await waitForKafkaConsumers(kafkaGroupsIn(rawDocuments));

  return corpus;
}

/** Undeploys and deletes the broker corpus, and drops the topics, queues and files the run created. */
export async function teardownBrokers(catalog: Catalog, corpus: SeededBrokerCorpus): Promise<void> {
  if (keepEntities()) {
    console.log(`[keep] ${corpus.chains.length} broker chains left behind for run ${corpus.run}`);
    for (const chain of corpus.chains) console.log(`[keep]   chain ${chain.name} (${chain.id})`);
    return;
  }

  const failures: string[] = [];
  const kept = new Set<string>();
  for (const chain of corpus.chains) {
    await catalog.deleteLoggingProperties(chain.id).catch((cause: unknown) => {
      failures.push(`${chain.name} (${chain.id}) kept its logging properties, so it was kept: ${String(cause)}`);
      kept.add(chain.id);
    });
    await catalog.undeployAll(chain.id).catch((cause: unknown) => {
      console.error(`[teardown] ${chain.name} (${chain.id}) was not undeployed before its delete: ${String(cause)}`);
    });
  }
  for (const chain of corpus.chains) {
    if (kept.has(chain.id)) continue;
    // `ChainController.deleteById` answers 404 for an unknown chain, which is what every chain named
    // here looks like when the seed failed before the import — the state file is written early (see
    // `seedBrokers`), so it can name chains the corpus never actually created. That is not residue.
    const response = await catalog.raw("delete", `/v1/chains/${chain.id}`);
    if (response.status() === 404) continue;
    if (!response.ok()) {
      failures.push(`${chain.name} (${chain.id}) was not deleted: ${response.status()}`);
    }
  }

  await deleteKafkaTopics(corpus.kafkaTopics).catch((cause: unknown) => {
    failures.push(`kafka topics ${corpus.kafkaTopics.join(", ")} were not deleted: ${String(cause)}`);
  });
  await deleteKafkaConsumerGroups(corpus.run).catch((cause: unknown) => {
    failures.push(`kafka consumer groups for run ${corpus.run} were not deleted: ${String(cause)}`);
  });
  await deleteRabbitmqTopology(corpus.rabbitmqTopology).catch((cause: unknown) => {
    failures.push(`rabbitmq topology was not deleted: ${String(cause)}`);
  });
  await deletePubsubTopology(corpus.pubsubTopology).catch((cause: unknown) => {
    failures.push(`pubsub topology was not deleted: ${String(cause)}`);
  });
  await deleteSftpResidue(corpus.run).catch((cause: unknown) => {
    failures.push(`sftp files under /upload/${corpus.run} were not deleted: ${String(cause)}`);
  });

  if (failures.length === 0) {
    fs.rmSync(BROKERS_CORPUS_STATE_FILE, { force: true });
    return;
  }
  throw new Error(
    `the brokers seed teardown left residue of run ${corpus.run}: ${failures.join("; ")}. ` +
      `${BROKERS_CORPUS_STATE_FILE} is kept, because it is what still names it.`,
  );
}

/** Writes what the brokers seed imported, through a staging file and a rename — the same reason `writeCorpusState` does. */
export function writeBrokersCorpusState(corpus: SeededBrokerCorpus): void {
  writeStateFile(BROKERS_CORPUS_STATE_FILE, corpus);
}

/**
 * One broker fixture chain by the fixture directory it came from — `support/corpus.ts`'s
 * `seedChain`, over the broker corpus instead of the shared one.
 */
export function brokerChain(corpus: SeededBrokerCorpus, fixture: string): BrokerChain {
  const found = corpus.chains.find((each) => each.fixture === fixture);
  if (!found) throw new Error(`the seeded broker corpus holds no chain from fixture ${fixture}`);
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
