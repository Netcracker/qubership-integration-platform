/**
 * `sftp-trigger-2`, `sftp-download` and `sftp-upload`, against `fixtures/brokers/sftp-*`.
 *
 * `sftp-trigger-2` fires on a dropped file and carries no correlation header, so a case correlates
 * in two hops: `sessions.idsOf` before the drop, `sessions.onlyOf` for the one session `idsOf` did
 * not carry. `sftp-download` and
 * `sftp-upload` sit mid-chain behind an `http-trigger`, so they correlate the ordinary way, through
 * `sessions.byExternalId`.
 *
 * The overlay is a single `atmoz/sftp` server (`infrastructure/docker-compose.sftp.yml`), chrooted
 * so the one writable subtree is `/upload`. Every fixture's `connectUrl` addresses the
 * container-internal listener (`sftp-server:22`); this spec's own `ssh2-sftp-client` calls address
 * the host-mapped one (`SFTP_HOST_CONFIG`, `localhost:2222`) -- the same "two addresses for one
 * broker" split Kafka and RabbitMQ carry.
 *
 * `idempotent`/`idempotentKey` are plain properties, not `if`-branched ones, so
 * `registry/discriminators.ts` extracts no axis for them and neither carries a registry row -- a
 * manually added one would fail `specs/schema/coverage.spec.ts`'s "no registry row names a value
 * the schemas no longer declare" check. They are still asserted here, behaviorally: pinning
 * `idempotentKey` to a literal (not file-derived) string on `sftp-trigger-idempotent` makes every
 * matched file resolve to the same key, so a second, distinct file dropped after the first is
 * rejected by the in-memory idempotent repository before the route ever runs -- provable without
 * controlling the SFTP server's file-modified timestamp, which the default, file-derived
 * `idempotentKey` would require.
 */
import { test, expect } from "../../support/fixtures.js";
import { brokerChain, readBrokersCorpusState, type BrokerChain } from "../../support/brokers.js";
import { withSftp } from "../../support/broker-clients.js";
import { callToken } from "../../support/run.js";
import { callChain, element, elementNames, failedElements } from "../../support/sessions.js";
import { readUntil } from "../../support/poll.js";
import { covers } from "../../registry/covers.js";

/** What `readUntil` gets for the idempotent case's settle window, instead of a bare literal. */
const IDEMPOTENT_SETTLE_TIMEOUT = 12_000;

/** The remote directory a chain's `connectUrl` polls — the host:port prefix stripped, path kept. */
function sftpDir(chain: BrokerChain): string {
  const connectUrl = chain.broker.connectUrl as string;
  return connectUrl.slice(connectUrl.indexOf("/"));
}

/** Writes `content` to `remotePath`, creating its directory first. */
async function dropFile(remotePath: string, content: string): Promise<void> {
  await withSftp(async (client) => {
    await client.mkdir(remotePath.slice(0, remotePath.lastIndexOf("/")), true);
    await client.put(Buffer.from(content, "utf-8"), remotePath);
  });
}

/** Reads `remotePath` back as text, for the upload case's own assertion. */
async function readFile(remotePath: string): Promise<string> {
  return withSftp(async (client) => {
    const content = await client.get(remotePath);
    if (!Buffer.isBuffer(content)) {
      throw new Error(`get(${remotePath}) answered a ${typeof content}, not a Buffer`);
    }
    return content.toString("utf-8");
  });
}

// ---------------------------------------------------------------------------
// sftp-trigger-2
// ---------------------------------------------------------------------------

test(
  "a file dropped onto the polled directory fires sftp-trigger-2, found by chain id",
  { tag: ["@engine", "@sessions", "@tier1"] },
  async ({ sessions }) => {
    covers("sftp-trigger-2");

    const corpus = readBrokersCorpusState();
    const chain = brokerChain(corpus, "sftp-trigger-basic");
    const before = await sessions.idsOf(chain.id);

    await dropFile(`${sftpDir(chain)}/e2e-${corpus.run}-basic.txt`, "sftp trigger basic");

    const session = await sessions.onlyOf(chain.id, 2, (each) => !before.has(each.id));
    expect(elementNames(session)).toEqual(["SFTP Trigger", "Header Modification"]);
    expect(failedElements(session)).toEqual([]);
  },
);

test(
  "idempotentKey pinned to one literal rejects a second file under the shared key",
  { tag: ["@engine", "@sessions", "@tier2"] },
  async ({ sessions }) => {
    const corpus = readBrokersCorpusState();
    const chain = brokerChain(corpus, "sftp-trigger-idempotent");
    const dir = sftpDir(chain);
    const before = await sessions.idsOf(chain.id);

    await dropFile(`${dir}/e2e-${corpus.run}-first.txt`, "first");
    const first = await sessions.onlyOf(chain.id, 2, (each) => !before.has(each.id));
    expect(failedElements(first)).toEqual([]);

    // The contract this case pins: every file matched by antInclude resolves to the same
    // idempotentKey, so a second, distinct file is rejected by the in-memory idempotent repository
    // before the route ever runs -- no new session for this chain, ever. `readUntil` polls the full
    // 12s budget (the poll cron is every 3s, so several cycles) and resolves with whatever the last
    // reading was, so this is a real bounded wait for absence rather than `toPass`, which would pass
    // on the first honest poll at t≈0, before the cron has scanned even once.
    const afterFirst = await sessions.idsOf(chain.id);
    await dropFile(`${dir}/e2e-${corpus.run}-second.txt`, "second");
    const settled = await readUntil(
      () => sessions.idsOf(chain.id),
      (ids) => [...ids].some((id) => !afterFirst.has(id)),
      IDEMPOTENT_SETTLE_TIMEOUT,
      1_000,
    );
    expect([...settled].filter((id) => !afterFirst.has(id))).toEqual([]);
  },
);

// ---------------------------------------------------------------------------
// sftp-download
// ---------------------------------------------------------------------------

test(
  "an HTTP call drives sftp-download, after a spec drops the file it polls",
  { tag: ["@engine", "@sessions", "@tier1"] },
  async ({ request, env, sessions }) => {
    covers("sftp-download");

    const corpus = readBrokersCorpusState();
    const chain = brokerChain(corpus, "sftp-download-basic");
    const content = `sftp-download-${callToken("payload")}`;
    await dropFile(`${sftpDir(chain)}/e2e-${corpus.run}-download.txt`, content);

    const { token, response } = await callChain(request, env.chainUrl(chain.contextPath), { data: {} });
    expect(response.status()).toBe(200);

    const session = await sessions.byExternalId(token, { elements: 4 });
    expect(session.chainId).toBe(chain.id);
    expect(elementNames(session)).toEqual([
      "HTTP Trigger",
      "Validate Request",
      "SFTP Download",
      "Header Modification",
    ]);
    expect(failedElements(session)).toEqual([]);

    const downstream = element(session, "Header Modification");
    expect(downstream?.bodyBefore, "the body the download step enriched the exchange with").toContain(content);
  },
);

// ---------------------------------------------------------------------------
// sftp-upload
// ---------------------------------------------------------------------------

test(
  "an HTTP call drives sftp-upload, and the payload lands on the server",
  { tag: ["@engine", "@sessions", "@tier1"] },
  async ({ request, env, sessions }) => {
    covers("sftp-upload");

    const corpus = readBrokersCorpusState();
    const chain = brokerChain(corpus, "sftp-upload-basic");
    const body = { ping: "sftp-upload", nonce: callToken("nonce") };

    const { token, response } = await callChain(request, env.chainUrl(chain.contextPath), { data: body });
    expect(response.status()).toBe(200);

    const session = await sessions.byExternalId(token, { elements: 3 });
    expect(session.chainId).toBe(chain.id);
    expect(elementNames(session)).toEqual(["HTTP Trigger", "Validate Request", "SFTP Upload"]);
    expect(failedElements(session)).toEqual([]);

    await expect(async () => {
      const uploaded = await readFile(`${sftpDir(chain)}/${chain.broker.fileName as string}`);
      expect(JSON.parse(uploaded)).toEqual(body);
    }).toPass({ timeout: 15_000 });
  },
);
