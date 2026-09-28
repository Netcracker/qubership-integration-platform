/**
 * What a checkpoint is for: a failed session resumed from it, through the engine's own API.
 *
 * The fixture holds two checkpoints and a step after them that throws while `e2e-fail` is true. A
 * retry restores the headers, properties and body the checkpoint saved, and its body replaces what
 * it names, so each retry sends `e2e-fail: false` and completes.
 *
 * Measured, and not what the operation's name suggests: `POST .../sessions/{id}/retry` resumes from
 * the **latest** checkpoint (`CheckpointSessionService.retryFromLastCheckpoint`), not from the
 * trigger. Only a retry from a named checkpoint can start earlier. A retried session is a new
 * session whose `parentSessionId` is the one it resumed, and whose trace starts at the checkpoint.
 *
 * Each case fails its own session: a completed retry deletes the checkpoints of the whole session
 * tree, so one failed session can be retried once. The retry routes' `httpMethodRestrict` is read on
 * the compile, in `specs/api/checkpoint-snapshot.spec.ts`.
 */
import { test, expect } from "../../support/fixtures.js";
import { readCorpusState, seedChain, type SeedChain } from "../../support/corpus.js";
import { callChain, element, elementNames, HTTP_TRIGGER_STEPS, SESSION_TIMEOUT, type RecordedSession, type Sessions } from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";
import type { APIRequestContext, APIResponse } from "@playwright/test";
import type { Env } from "../../env/index.js";
import type { Engine } from "../../support/engine.js";

const FAILED_TRACE = [...HTTP_TRIGGER_STEPS, "First Checkpoint", "Between Checkpoints", "Second Checkpoint", "Fail On Flag"];
const REPLACE = { headers: { "e2e-fail": "false" } };

/** A call that fails after both checkpoints, and the session it left. */
async function failedSession(request: APIRequestContext, env: Env, sessions: Sessions, chain: SeedChain, ping: string): Promise<RecordedSession> {
  const call = await callChain(request, env.chainUrl(chain.contextPath), { headers: { "e2e-fail": "true" }, data: { ping } });
  expect(call.response.status()).toBe(500);
  const session = await sessions.byExternalId(call.token, { elements: FAILED_TRACE.length });
  expect(session.executionStatus).toBe("COMPLETED_WITH_ERRORS");
  expect(elementNames(session)).toEqual(FAILED_TRACE);
  // A step's status settles after the session's: measured IN_PROGRESS on a session already failed.
  await expect
    .poll(async () => element(await sessions.session(session.id), "Fail On Flag")?.executionStatus, { timeout: SESSION_TIMEOUT })
    .toBe("COMPLETED_WITH_ERRORS");
  return session;
}

/** A retry is accepted before it runs; any other answer names the session, so read it rather than wait. */
async function accepted(retry: Promise<APIResponse>): Promise<void> {
  const response = await retry;
  expect(response.status(), await response.text()).toBe(202);
}

/** The settled session a retry of `parentId` produced. */
function retriedSession(sessions: Sessions, chainId: string, parentId: string, elements: number): Promise<RecordedSession> {
  return sessions.onlyOf(chainId, elements, (each) => each.parentSessionId === parentId);
}

async function failedIds(engine: Engine, chainId: string): Promise<string[]> {
  return (await engine.failedSessions(chainId)).map((each) => each.id);
}

test("a failed session is listed for retry, and a retry from its first checkpoint resumes there and completes", { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions, engine }) => {
  covers("checkpoint");

  const chain = seedChain(readCorpusState(), "checkpoint-retry");
  const failed = await failedSession(request, env, sessions, chain, "from-first");

  const listed = (await engine.failedSessions(chain.id)).find((each) => each.id === failed.id);
  expect(listed, `${failed.id} under sessions/failed`).toBeDefined();
  expect(listed?.executionStatus).toBe("COMPLETED_WITH_ERRORS");
  expect(listed?.checkpoints.map((each) => each.checkpointElementId).sort()).toEqual(
    [chain.elements["First Checkpoint"], chain.elements["Second Checkpoint"]].sort(),
  );

  await accepted(engine.retryFromCheckpoint(chain.id, failed.id, chain.elements["First Checkpoint"], REPLACE));
  const retried = await retriedSession(sessions, chain.id, failed.id, 4);
  expect(retried.executionStatus).toBe("COMPLETED_NORMALLY");
  expect(elementNames(retried)).toEqual(["First Checkpoint", "Between Checkpoints", "Second Checkpoint", "Fail On Flag"]);
  // The checkpoint step takes the retry's request and hands on the body it saved.
  expect(JSON.parse(element(retried, "First Checkpoint")?.bodyAfter ?? "null")).toEqual({ ping: "from-first" });
  expect(JSON.parse(element(retried, "Fail On Flag")?.bodyAfter ?? "null")).toEqual({ checkpoint: "completed" });

  await expect.poll(() => failedIds(engine, chain.id), { message: "a completed retry takes the session off the list" }).not.toContain(failed.id);
});

test("a session retry resumes from the latest checkpoint, not from the trigger", { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions, engine }) => {
  const chain = seedChain(readCorpusState(), "checkpoint-retry");
  const failed = await failedSession(request, env, sessions, chain, "from-latest");

  await accepted(engine.retrySession(chain.id, failed.id, REPLACE));
  const retried = await retriedSession(sessions, chain.id, failed.id, 2);
  expect(retried.executionStatus).toBe("COMPLETED_NORMALLY");
  expect(elementNames(retried)).toEqual(["Second Checkpoint", "Fail On Flag"]);
  expect(JSON.parse(element(retried, "Second Checkpoint")?.bodyAfter ?? "null")).toEqual({ ping: "from-latest" });
  expect(JSON.parse(element(retried, "Fail On Flag")?.bodyAfter ?? "null")).toEqual({ checkpoint: "completed" });
});

test("a retry of a session with no checkpoint answers 404 naming the session", { tag: ["@engine", "@tier2"] }, async ({ engine }) => {
  const chain = seedChain(readCorpusState(), "checkpoint-retry");
  const absent = `never-existed-${chain.id}`;

  const whole = await engine.retrySession(chain.id, absent);
  expect(whole.status()).toBe(404);
  expect((await whole.json()).errorMessage).toBe(`Can't find checkpoint for session with id: ${absent}`);

  const checkpoint = chain.elements["First Checkpoint"];
  const fromCheckpoint = await engine.retryFromCheckpoint(chain.id, absent, checkpoint);
  expect(fromCheckpoint.status()).toBe(404);
  expect((await fromCheckpoint.json()).errorMessage).toBe(`Can't find checkpoint ${checkpoint} for session with id: ${absent}`);
});
