/**
 * What a failed spec attaches to its report, so the failure can be read rather than guessed at.
 *
 * A red spec on its own yields the assertion diff and the source line, and nothing else — measured
 * by breaking one runtime spec and collecting everything the run produced: `Expected: 200 /
 * Received: 404`, the snippet, and an `error-context.md` repeating both. No request, no response
 * body, no engine log. `retries: 0` is right for a shared stack, but it also removes
 * `on-first-retry`, the trigger trace capture is conventionally hung on. The trace is now retained
 * on failure instead, and this module adds the two things the trace cannot know:
 *
 * - **The engine's own account of the chain**, filtered to it. Without that line, "the platform
 *   regressed" and "the route never started" look identical in the report, and three of the
 *   investigations behind this suite turned on exactly that distinction.
 * - **The session**, with a link into the UI. A person reads a trace in the UI, not as JSON — and
 *   when the spec found no session at all, saying so is itself the finding.
 *
 * Recording is automatic rather than declared per spec: `seedChain()` notes the chain a spec asked
 * for and the session lookups note what they found, so a spec carries no diagnostic bookkeeping.
 * The record is module-level state, which is safe because Playwright runs one test at a time per
 * worker and each worker is its own process.
 */
import type { TestInfo } from "@playwright/test";
import type { EngineKind, Env } from "../env/index.js";

/** The chain a spec is exercising, as much of it as the spec knows. */
export interface ChainUnderTest {
  id: string;
  name: string;
  contextPath?: string;
}

/** A session a lookup found, and the token it was found by. */
export interface FoundSession {
  id: string;
  chainId?: string;
  token?: string;
}

interface Diagnostic {
  startedAt: Date;
  chains: ChainUnderTest[];
  sessions: FoundSession[];
  /** Tokens a lookup asked for. A token here and no session is the interesting case. */
  tokens: string[];
}

function fresh(): Diagnostic {
  return { startedAt: new Date(), chains: [], sessions: [], tokens: [] };
}

let current: Diagnostic = fresh();

/** Starts a fresh record. Called by the `diagnostics` fixture before each test. */
export function beginDiagnostics(): void {
  current = fresh();
}

/** Notes the chain a spec addressed. Called from `seedChain()`, and by hand for a built chain. */
export function noteChain(chain: ChainUnderTest): void {
  if (!current.chains.some((each) => each.id === chain.id)) current.chains.push(chain);
}

/** Notes a token a lookup asked for, whether or not a session ever answered it. */
export function noteSessionLookup(token: string): void {
  if (!current.tokens.includes(token)) current.tokens.push(token);
}

/** Notes a session a lookup found. */
export function noteSession(session: FoundSession): void {
  if (!current.sessions.some((each) => each.id === session.id)) current.sessions.push(session);
}

/**
 * The engine lines that belong to the chains under test, plus the tail for context.
 *
 * The engine logs its MDC on every line — `[chain_id=…] [chain=…]` — so a chain id is an exact
 * filter, and at DEBUG the unfiltered log is mostly Kafka polling and Consul renewals. Both halves
 * are kept: a chain that never started logs nothing under its own id, and the tail is where the
 * reason for that sits.
 */
export function filterChainLog(
  log: string,
  chains: readonly ChainUnderTest[],
  limits: { matched?: number; tail?: number } = {},
): string {
  const matched = limits.matched ?? 120;
  const tail = limits.tail ?? 40;
  const lines = log.split("\n").filter((line) => line.trim() !== "");
  const sections: string[] = [];

  for (const chain of chains) {
    const mine = lines.filter(
      (line) => line.includes(`chain_id=${chain.id}`) || line.includes(chain.name),
    );
    sections.push(
      `--- engine lines for ${chain.name} (${chain.id}): ${mine.length} ---`,
      ...(mine.length ? mine.slice(-matched) : ["(none — the chain logged nothing in this window)"]),
      "",
    );
  }

  sections.push(
    `--- last ${Math.min(tail, lines.length)} engine lines, unfiltered ---`,
    ...lines.slice(-tail),
  );
  return sections.join("\n");
}

/**
 * Where a person opens the session's trace.
 *
 * The UI renders a session under its chain — `ui/src/App.tsx:244` is
 * `<Route path="sessions/:sessionId">` nested inside `/chains/:chainId` — so the chain id is part
 * of the link and not decoration. A session whose chain the record never saw still gets a link, to
 * the session list, which is one click from the trace rather than zero.
 */
export function sessionLink(env: Env, session: FoundSession): string {
  return session.chainId
    ? env.uiUrl(`/chains/${session.chainId}/sessions/${session.id}`)
    : env.uiUrl("/sessions");
}

/** Whether the test failed, in the one form Playwright makes available inside a fixture. */
function failed(testInfo: TestInfo): boolean {
  return testInfo.status !== testInfo.expectedStatus;
}

/**
 * Attaches the engine log and the session to a failed test, and nothing at all to a passing one.
 *
 * Nothing here may throw: an attachment that fails would replace the real failure with its own,
 * which is the opposite of the point. Every hop reports its own error into the attachment instead.
 */
export async function attachDiagnostics(
  testInfo: TestInfo,
  env: Env,
  engineKind: EngineKind,
): Promise<void> {
  if (!failed(testInfo)) return;
  const record = current;

  const since = record.startedAt.toISOString();
  // A `runtime-micro` case ran its chain in the micro domain's pod, so that pod's log is the one read.
  const micro = engineKind === "micro";
  const log = await env
    .engineLogs(since)
    .then((text) => filterChainLog(text, record.chains))
    .catch((cause: unknown) => `the engine log could not be read: ${String(cause)}`);
  await testInfo
    .attach(micro ? "micro-engine.log" : "engine.log", {
      body: `since ${since}\n\n${log}`,
      contentType: "text/plain",
    })
    .catch(() => {});

  await attachSessions(testInfo, env, record);
}

async function attachSessions(testInfo: TestInfo, env: Env, record: Diagnostic): Promise<void> {
  if (record.sessions.length === 0) {
    // The absence is the finding: a spec that looked and found nothing failed for a different
    // reason than one that never looked, and the report has no other way to tell them apart.
    const looked = record.tokens.length
      ? `it looked for ${record.tokens.map((token) => JSON.stringify(token)).join(", ")} and ` +
        `none arrived`
      : "it never looked for one";
    await testInfo
      .attach("session.txt", {
        body: `No session was recorded for this test: ${looked}.\n`,
        contentType: "text/plain",
      })
      .catch(() => {});
    return;
  }

  const base = env.url("sessions-management");
  for (const session of record.sessions) {
    const body = await fetch(`${base}/v1/sessions/${session.id}`, {
      signal: AbortSignal.timeout(10_000),
    })
      .then((response) => response.text())
      .catch((cause: unknown) => `the session could not be read: ${String(cause)}`);
    await testInfo
      .attach(`session-${session.id}.json`, { body, contentType: "application/json" })
      .catch(() => {});
    await testInfo
      .attach(`session-${session.id}.txt`, {
        // The link, not the JSON, is what a person opens: the UI renders the trace as the chain.
        body: `${sessionLink(env, session)}\n`,
        contentType: "text/plain",
      })
      .catch(() => {});
  }
}
