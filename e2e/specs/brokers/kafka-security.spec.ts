/**
 * The security axes of `kafka-trigger-2` and `kafka-sender-2`, against
 * `fixtures/brokers/kafka-{trigger,sender}-security-*`.
 *
 * Each fixture connects to the listener of the Kafka overlay that serves its protocol (the listener
 * table is in `infrastructure/docker/README.md`): `AUTH` (SASL_PLAINTEXT) on 29094, `TLS` (SSL) on 29095,
 * `AUTHTLS` (SASL_SSL) on 29096, and `TLS13` (SSL, TLSv1.3 only) on 29097, as user `e2e`. The server
 * certificate covers the name `kafka`, and both engines trust its CA through their certificate
 * directory, so hostname verification stays on. The cases publish and consume host-side over the
 * PLAINTEXT `EXTERNAL` listener, so only the chain's own connection is under test.
 *
 * `sslProtocol` names the protocol of the client's `SSLContext`, and a `TLSv1.2` context never
 * negotiates TLSv1.3, so a `TLSv1.2` sender or trigger is refused on `TLS13` with `protocol_version`.
 * Those two cases tell `TLSv1.2` apart from the `TLS` default, so they carry its rows, and the
 * positive `TLSv1.2` cases carry none. `TLS` and `TLSv1.3` connect to `TLS13` alike, and no listener
 * accepts TLSv1.2 while it refuses TLSv1.3, so no case tells `TLSv1.3` apart from the default: its
 * positive cases run without a `covers()` call. `TLSv1.1` has no case: the JDK of both engines
 * disables it, so the client fails with `No appropriate protocol` before it reaches the broker
 * (measured on both engines).
 *
 * The three negative cases deploy spec-owned chains through `withOwnFixture` and are `@classic-only`.
 * A wrong password is refused by the classic engine's pre-deploy check, which parks the deployment
 * at `PROCESSING`. Since #1086 the micro engine lists the topics with the chain's credentials when
 * its pod starts, so the wrong password stops the whole micro domain from starting (read from
 * `KafkaTopicCheck`, not measured). The refused sender deploys on both engines and failed the same
 * way on both, but its producer retries the handshake for as long as the chain is deployed, so it
 * cannot sit in the broker corpus, and a deploy into the shared micro domain would roll its pod
 * (skill rule 20). The refused trigger's consumer retries the same way.
 */
import { test, expect } from "../../support/fixtures.js";
import { BROKERS_FIXTURE_DIR, BROKERS_LOGGING, brokerChain, deleteKafkaTopics, ensureKafkaTopics, readBrokersCorpusState } from "../../support/brokers.js";
import { consumeOneKafka, publishKafka } from "../../support/broker-clients.js";
import { DEPLOY_TIMEOUT, ENGINE_CASE_TIMEOUT, waitForRoutes, type DeployedChain } from "../../support/corpus.js";
import type { Catalog } from "../../support/catalog.js";
import { waitForRecording } from "../../support/cleanup.js";
import { logWindowStart } from "../../support/logs.js";
import { deploymentRows, withOwnFixture } from "../../support/spec-owned.js";
import { callToken, tokenized } from "../../support/run.js";
import { readUntil } from "../../support/poll.js";
import { callChain, elementNames, failedElements, HTTP_TRIGGER_STEPS } from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";

type Axis = "securityProtocol" | "saslMechanism" | "sslProtocol";

/** How long the refused trigger is watched for a session after the publish. */
const REFUSED_CONSUMER_WINDOW = 10_000;

interface Variant {
  /** The fixture name's suffix. */
  fixture: string;
  /** The element's name, the axis value the case is about. */
  element: string;
  /** The rows a passing case proves. */
  rows: [Axis, string][];
}

const VARIANTS: Variant[] = [
  { fixture: "sasl-plain", element: "saslMechanism=PLAIN", rows: [["securityProtocol", "SASL_PLAINTEXT"], ["saslMechanism", "PLAIN"]] },
  { fixture: "sasl-scram-sha-256", element: "saslMechanism=SCRAM-SHA-256", rows: [["saslMechanism", "SCRAM-SHA-256"]] },
  { fixture: "sasl-scram-sha-512", element: "saslMechanism=SCRAM-SHA-512", rows: [["saslMechanism", "SCRAM-SHA-512"]] },
  { fixture: "ssl-tls", element: "securityProtocol=SSL", rows: [["securityProtocol", "SSL"], ["sslProtocol", "TLS"]] },
  // The refused cases below carry the TLSv1.2 rows; no case tells TLSv1.3 apart from the default.
  { fixture: "ssl-tlsv1-2", element: "sslProtocol=TLSv1.2", rows: [] },
  { fixture: "ssl-tlsv1-3", element: "sslProtocol=TLSv1.3", rows: [] },
  { fixture: "sasl-ssl", element: "securityProtocol=SASL_SSL", rows: [["securityProtocol", "SASL_SSL"]] },
];

for (const variant of VARIANTS) {
  test(`${variant.element}: kafka-trigger-2 consumes over its listener`, { tag: ["@engine", "@sessions", "@tier2"] }, async ({ sessions }) => {
    for (const [axis, value] of variant.rows) covers("kafka-trigger-2", axis, value);

    const chain = brokerChain(readBrokersCorpusState(), `kafka-trigger-security-${variant.fixture}`);
    const token = callToken("kafka-security");

    await publishKafka(chain.broker.topics as string, token, JSON.stringify({ ping: variant.fixture }));

    const session = await sessions.settled(await sessions.byExternalId(token, { elements: 2 }));
    expect(session.chainId).toBe(chain.id);
    expect(elementNames(session)).toEqual([variant.element, "Header Modification"]);
    expect(failedElements(session)).toEqual([]);
  });

  test(`${variant.element}: kafka-sender-2 sends over its listener`, { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
    for (const [axis, value] of variant.rows) covers("kafka-sender-2", axis, value);

    const chain = brokerChain(readBrokersCorpusState(), `kafka-sender-security-${variant.fixture}`);
    const topic = chain.broker.topics as string;
    const body = { ping: variant.fixture, nonce: callToken("nonce") };

    const { token, response } = await callChain(request, env.chainUrl(chain.contextPath), { data: body });
    expect(response.status()).toBe(200);
    const session = await sessions.settled(await sessions.byExternalId(token, { elements: 3 }));
    expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, variant.element]);
    expect(failedElements(session)).toEqual([]);

    const message = await consumeOneKafka(topic, `${topic}-consumer`, (value) => value?.includes(body.nonce) ?? false);
    expect(message, `no message arrived on ${topic} within the wait`).not.toBeNull();
    expect(JSON.parse(message!.value ?? "null")).toEqual(body);
  });
}

/** The line after the pre-deploy check's failure for `chainId`: the first line of its stack trace, which names the cause. */
function predeployCause(log: string, chainId: string): string | undefined {
  const lines = log.split("\n");
  const at = lines.findIndex((line) => line.includes(`chainId=${chainId}`) && line.includes("Kafka predeploy check is failed"));
  return at < 0 ? undefined : lines[at + 1];
}

test("a wrong password: the classic engine parks kafka-trigger-2 and logs the refused credentials", { tag: ["@engine", "@tier2", "@classic-only"], annotation: { type: "classic-only", description: "the micro engine checks the credentials when its pod starts, so a micro domain holding this chain never starts" } }, async ({ env, catalog, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  const since = logWindowStart();

  await withOwnFixture(catalog, run, "kafka-trigger-wrong-password.yaml", { dir: BROKERS_FIXTURE_DIR }, async (chain) => {
    await expect
      .poll(async () => await deploymentRows(catalog, chain.id), { timeout: DEPLOY_TIMEOUT, message: "the engine never refused the wrong password" })
      .toEqual([expect.stringContaining("PROCESSING: Kafka predeploy check is failed")]);
    await expect
      .poll(async () => predeployCause(await env.engineLogs(since), chain.id), { message: "the engine logged no pre-deploy failure for this chain" })
      .toContain("Authentication failed during authentication due to invalid credentials with SASL mechanism SCRAM-SHA-512");
  });
});

/**
 * Creates `topic`, deploys the spec-owned `file` that names it, and runs `body` once the chain is
 * deployed; the topic is deleted again whatever `body` did.
 */
async function withRefusedFixture(catalog: Catalog, run: string, file: string, topic: string, body: (chain: DeployedChain) => Promise<void>): Promise<void> {
  await ensureKafkaTopics([topic]);
  try {
    await withOwnFixture(catalog, run, file, { dir: BROKERS_FIXTURE_DIR, logging: BROKERS_LOGGING }, async (chain) => {
      await expect.poll(async () => await deploymentRows(catalog, chain.id), { timeout: DEPLOY_TIMEOUT }).toEqual(["DEPLOYED: "]);
      await body(chain);
    });
  } finally {
    await deleteKafkaTopics([topic]);
  }
}

test("sslProtocol=TLSv1.2: a listener that accepts TLSv1.3 only refuses kafka-sender-2", { tag: ["@engine", "@sessions", "@tier2", "@classic-only"], annotation: { type: "classic-only", description: "the case deploys a chain of its own, and a deploy into the shared micro domain rolls its pod" } }, async ({ request, env, catalog, sessions, run }) => {
  covers("kafka-sender-2", "sslProtocol", "TLSv1.2");
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  // The pre-deploy check lists the topic with the default protocol, which TLS13 accepts.
  await withRefusedFixture(catalog, run, "kafka-sender-tlsv1-2-refused.yaml", tokenized(run, "kafka-tlsv1-2-refused"), async (chain) => {
    await waitForRoutes(env, [chain]);
    await waitForRecording(env, sessions, chain);

    const { token, response } = await callChain(request, env.chainUrl(chain.contextPath), { data: { ping: "tlsv1-2-refused" } });
    expect(response.status()).toBe(500);
    const session = await sessions.settled(await sessions.byExternalId(token, { elements: 3 }));
    expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, "sslProtocol=TLSv1.2"]);
    const failed = failedElements(session);
    expect(failed.map((each) => each.elementName)).toEqual(["sslProtocol=TLSv1.2"]);
    expect(failed[0].exceptionInfo?.stackTrace).toContain("SSLHandshakeException: Received fatal alert: protocol_version");
  });
});

/** The engine log lines of consumer group `group` that report a refused TLS handshake. */
function refusedHandshakes(group: string): (line: string) => boolean {
  return (line) => line.includes(group) && line.includes("SSL handshake failed");
}

test("sslProtocol=TLSv1.2: a listener that accepts TLSv1.3 only refuses kafka-trigger-2", { tag: ["@engine", "@sessions", "@tier2", "@classic-only"], annotation: { type: "classic-only", description: "the case deploys a chain of its own, and a deploy into the shared micro domain rolls its pod" } }, async ({ env, catalog, sessions, run }) => {
  covers("kafka-trigger-2", "sslProtocol", "TLSv1.2");
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  const topic = tokenized(run, "kafka-trigger-tlsv1-2-refused");
  const since = logWindowStart();
  await withRefusedFixture(catalog, run, "kafka-trigger-tlsv1-2-refused.yaml", topic, async (chain) => {
    // The fixture names its group after the topic, which carries the run token, so only this chain logs it.
    await expect
      .poll(async () => (await env.engineLogs(since)).split("\n").filter(refusedHandshakes(topic)).length, { timeout: DEPLOY_TIMEOUT, message: `the consumer of ${topic} logged no refused handshake` })
      .toBeGreaterThan(0);

    const before = await sessions.idsOf(chain.id);
    await publishKafka(topic, callToken("kafka-trigger-refused"), JSON.stringify({ ping: "tlsv1-2-refused" }));
    const fresh = await readUntil(
      async () => [...(await sessions.idsOf(chain.id))].filter((id) => !before.has(id)),
      (found) => found.length > 0,
      REFUSED_CONSUMER_WINDOW,
      1_000,
    );
    expect(fresh, "sessions the refused consumer recorded").toEqual([]);
  });
});
