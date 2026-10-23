/**
 * The Kafka, AMQP, Pub/Sub and SFTP clients a broker spec drives itself, host-side.
 *
 * `support/brokers.ts` owns the platform side — the seed's own topics, queues and consumer waits —
 * this is what a spec dials against the same overlay to publish, consume, or bind a queue. Shared
 * once here rather than copy-pasted per spec file, which is how the two `consumeOne` shapes and the
 * AMQP publish's routing key had already started to drift.
 */
import { Kafka, logLevel, type Consumer } from "kafkajs";
import amqp, { type Channel, type ChannelModel } from "amqplib";
import Client from "ssh2-sftp-client";
import { expect } from "@playwright/test";
import {
  ensurePubsubTopology,
  KAFKA_HOST_BROKERS,
  openChannel,
  PUBSUB_HOST_URL,
  RABBITMQ_HOST_URL,
  SFTP_HOST_CONFIG,
} from "./brokers.js";
import { readUntil } from "./poll.js";
import { CORRELATION_HEADER, failedElements, type Sessions } from "./sessions.js";

const kafka = new Kafka({ clientId: "e2e-broker-client", brokers: [KAFKA_HOST_BROKERS], logLevel: logLevel.NOTHING });

/** Publishes one message with the correlation header, for a trigger case to be found by. */
export async function publishKafka(
  topic: string,
  token: string,
  value: string,
  extra: { key?: string; headers?: Record<string, string> } = {},
): Promise<void> {
  const producer = kafka.producer();
  await producer.connect();
  try {
    await producer.send({
      topic,
      messages: [{ key: extra.key, value, headers: { [CORRELATION_HEADER]: token, ...extra.headers } }],
    });
  } finally {
    await producer.disconnect();
  }
}

/** A record a spec consumed, its header values decoded as UTF-8. */
export interface KafkaRecord {
  value: string | null;
  key: string | null;
  headers: Record<string, string>;
}

/**
 * The first message off `topic` whose value `wanted` accepts, or `null` if none arrives within
 * `timeoutMs`, for a sender or delivery case. A message an earlier run left on the topic is skipped.
 */
export async function consumeOneKafka(
  topic: string,
  groupId: string,
  wanted: (value: string | null) => boolean,
  timeoutMs = 20_000,
): Promise<KafkaRecord | null> {
  const consumer: Consumer = kafka.consumer({ groupId });
  await consumer.connect();
  await consumer.subscribe({ topic, fromBeginning: true });
  let resolveFound: (message: KafkaRecord | null) => void;
  const found = new Promise<KafkaRecord | null>((resolve) => {
    resolveFound = resolve;
  });
  const timer = setTimeout(() => resolveFound(null), timeoutMs);
  try {
    await consumer.run({
      eachMessage: async ({ message }) => {
        const value = message.value ? message.value.toString("utf-8") : null;
        if (!wanted(value)) return;
        clearTimeout(timer);
        const headers = Object.fromEntries(Object.entries(message.headers ?? {}).map(([name, each]) => [name, String(each)]));
        resolveFound({ value, key: message.key ? message.key.toString("utf-8") : null, headers });
      },
    });
    return await found;
  } finally {
    clearTimeout(timer);
    await consumer.disconnect();
  }
}

/** Publishes one message on `exchange`/`routingKey` with the correlation header, for a trigger case to be found by. */
export async function publishAmqp(
  exchange: string,
  routingKey: string,
  token: string,
  body: unknown,
  headers: Record<string, string> = {},
): Promise<void> {
  const connection: ChannelModel = await amqp.connect(RABBITMQ_HOST_URL);
  const channel: Channel = await openChannel(connection);
  try {
    channel.publish(exchange, routingKey, Buffer.from(JSON.stringify(body)), {
      headers: { [CORRELATION_HEADER]: token, ...headers },
    });
  } finally {
    await channel.close();
    await connection.close();
  }
}

/**
 * A queue bound to `exchange`/`routingKey`, ready before the message that lands on it is produced.
 *
 * Split from the wait on purpose: an exchange retains nothing, unlike a Kafka topic, so a queue
 * bound after the message was published never sees it. `routingKey` defaults to `""`, which is what
 * every fanout binding in this suite uses — a fanout delivers to every bound queue regardless of one.
 * Closes what it opened if the bind itself throws, the same shape `declareFanout` below uses.
 */
export async function bindConsumerQueue(
  exchange: string,
  routingKey = "",
): Promise<{ connection: ChannelModel; channel: Channel; queue: string }> {
  const connection: ChannelModel = await amqp.connect(RABBITMQ_HOST_URL);
  const channel: Channel = await openChannel(connection);
  try {
    const { queue } = await channel.assertQueue("", { exclusive: true, autoDelete: true });
    await channel.bindQueue(queue, exchange, routingKey);
    return { connection, channel, queue };
  } catch (cause) {
    await channel.close().catch(() => {});
    await connection.close().catch(() => {});
    throw cause;
  }
}

/**
 * A fanout exchange with one durable, exclusive-free queue bound to it — routing key plays no part,
 * since a fanout exchange delivers to every bound queue regardless of one.
 */
export async function declareFanout(
  exchange: string,
  queue: string,
): Promise<{ connection: ChannelModel; channel: Channel }> {
  const connection: ChannelModel = await amqp.connect(RABBITMQ_HOST_URL);
  const channel: Channel = await openChannel(connection);
  try {
    await channel.assertExchange(exchange, "fanout", { durable: true });
    await channel.assertQueue(queue, { durable: true });
    await channel.bindQueue(queue, exchange, "");
    return { connection, channel };
  } catch (cause) {
    await channel.close().catch(() => {});
    await connection.close().catch(() => {});
    throw cause;
  }
}

/** A message a spec consumed off an AMQP queue, its body parsed as JSON. */
export interface AmqpMessage {
  body: unknown;
  headers: Record<string, unknown>;
}

/** One message off an already-bound queue, or `null` if none arrives within `timeoutMs`. */
export function waitForAmqpMessage(channel: Channel, queue: string, timeoutMs = 20_000): Promise<AmqpMessage | null> {
  return new Promise<AmqpMessage | null>((resolve) => {
    const timer = setTimeout(() => resolve(null), timeoutMs);
    channel
      .consume(
        queue,
        (message) => {
          if (!message) return;
          clearTimeout(timer);
          channel.ack(message);
          resolve({ body: JSON.parse(message.content.toString("utf-8")), headers: message.properties.headers ?? {} });
        },
        { noAck: false },
      )
      .catch(() => resolve(null));
  });
}

/** How long a MANUAL or NONE acknowledgement case watches the dead-letter queue stay empty. */
export const DEAD_LETTER_ABSENCE_TIMEOUT = 15_000;

/** Where an acknowledgement case publishes, and where its trigger dead-letters a rejected delivery. */
export interface AckTarget {
  exchange: string;
  routingKey: string;
  /** The trigger's own queue. */
  queue: string;
  deadLetterExchange: string;
  deadLetterRoutingKey: string;
}

/**
 * Publishes `body` to an AMQP trigger whose chain fails in its `HTTP Sender`, and asserts the one
 * failed session the message records, whether it reached the dead-letter queue, and that the
 * trigger's queue is left empty.
 */
export async function expectAckOutcome(
  sessions: Sessions,
  chainId: string,
  target: AckTarget,
  token: string,
  body: unknown,
  deadLettered: boolean,
): Promise<void> {
  const before = await sessions.idsOf(chainId);
  const { connection, channel, queue } = await bindConsumerQueue(target.deadLetterExchange, target.deadLetterRoutingKey);
  try {
    await publishAmqp(target.exchange, target.routingKey, token, body);

    const session = await sessions.byExternalId(token, { elements: 2 });
    expect(session.chainId).toBe(chainId);
    expect(session.executionStatus).toBe("COMPLETED_WITH_ERRORS");
    expect(failedElements(session).map((each) => each.elementName)).toEqual(["HTTP Sender"]);

    // Started after the session lookup, so the absence window opens once the chain has failed. A
    // queue holds an unconsumed message until something reads it, so listening late loses nothing.
    const message = await waitForAmqpMessage(channel, queue, deadLettered ? undefined : DEAD_LETTER_ABSENCE_TIMEOUT);
    const deadLetterQueue = `the dead-letter queue bound to ${target.deadLetterExchange}/${target.deadLetterRoutingKey}`;
    if (deadLettered) expect(message?.body, `nothing arrived on ${deadLetterQueue}`).toEqual(body);
    else expect(message, `the message reached ${deadLetterQueue}`).toBeNull();

    expect((await channel.checkQueue(target.queue)).messageCount, `messages left on ${target.queue}`).toBe(0);
    const fresh = [...(await sessions.idsOf(chainId))].filter((id) => !before.has(id));
    expect(fresh, "sessions the message recorded").toEqual([session.id]);
  } finally {
    await channel.close();
    await connection.close();
  }
}

/** The emulator project every pub/sub fixture names. */
const PUBSUB_PROJECT_ID = "test-project";

/** The emulator's REST URL for `path` under the fixtures' project. */
function pubsubUrl(path: string): string {
  return `${PUBSUB_HOST_URL}/v1/projects/${PUBSUB_PROJECT_ID}/${path}`;
}

/** Publishes one JSON message to `topic`. No correlation header: Pub/Sub attributes never unpack into one. */
export async function publishPubsub(topic: string, body: unknown): Promise<void> {
  const response = await fetch(pubsubUrl(`topics/${topic}:publish`), {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      messages: [{ data: Buffer.from(JSON.stringify(body), "utf-8").toString("base64") }],
    }),
  });
  if (!response.ok) throw new Error(`publishing to topic ${topic} answered ${response.status}`);
}

/**
 * A pull subscription bound to `topic`, created and confirmed before the message that lands on it
 * is produced: Pub/Sub does not replay a topic's backlog to a subscription created later.
 */
export async function createPullSubscription(topic: string, subscription: string): Promise<void> {
  await ensurePubsubTopology({
    senderTopics: [],
    subscriptions: [{ projectId: PUBSUB_PROJECT_ID, topic, subscription }],
  });
}

/** Seeks `subscription` to now, which acknowledges every message published to it before. */
export async function seekPubsubToNow(subscription: string): Promise<void> {
  const response = await fetch(pubsubUrl(`subscriptions/${subscription}:seek`), {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ time: new Date().toISOString() }),
  });
  if (!response.ok) throw new Error(`seeking subscription ${subscription} answered ${response.status}`);
}

export async function deletePullSubscription(subscription: string): Promise<void> {
  await fetch(pubsubUrl(`subscriptions/${subscription}`), { method: "DELETE" }).catch(() => {});
}

/** A message a spec pulled off a pub/sub subscription, its data parsed as JSON. */
export interface PubsubMessage {
  data: unknown;
  orderingKey?: string;
  attributes: Record<string, string>;
}

/** One message off `subscription`, acked, or `null` if none arrives within `timeoutMs`. */
export async function pullOnePubsub(
  subscription: string,
  timeoutMs = 20_000,
): Promise<PubsubMessage | null> {
  const received = await readUntil(
    async () => {
      const response = await fetch(pubsubUrl(`subscriptions/${subscription}:pull`), {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ maxMessages: 1 }),
      });
      if (!response.ok) throw new Error(`pulling ${subscription} answered ${response.status}`);
      const body = (await response.json()) as {
        receivedMessages?: Array<{ ackId: string; message: { data: string; orderingKey?: string; attributes?: Record<string, string> } }>;
      };
      return body.receivedMessages?.[0] ?? null;
    },
    (each) => each !== null,
    timeoutMs,
  );
  if (!received) return null;
  await fetch(pubsubUrl(`subscriptions/${subscription}:acknowledge`), {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ ackIds: [received.ackId] }),
  });
  return {
    data: JSON.parse(Buffer.from(received.message.data, "base64").toString("utf-8")),
    orderingKey: received.message.orderingKey,
    attributes: received.message.attributes ?? {},
  };
}

/** One SFTP connection, host-side, closed whether `fn` throws or not. */
export async function withSftp<T>(fn: (client: Client) => Promise<T>): Promise<T> {
  const client = new Client();
  await client.connect(SFTP_HOST_CONFIG);
  try {
    return await fn(client);
  } finally {
    await client.end();
  }
}
