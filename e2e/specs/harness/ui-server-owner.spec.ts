/**
 * Shown failing: recording the runner PID instead of the owner PID changed the saved record.
 * Shown failing: returning a null PID for a fresh preview broke the reuse assertion.
 * Shown failing: dropping the checkout reservation left the test reservation in place.
 * Shown failing: skipping the owner liveness check let setup resolve after the owner exited.
 * Shown failing: skipping the command-line check let setup accept an unrelated process group.
 * Shown failing: allowing teardown to stop the owner's preview made the HTTP probe fail.
 * Shown failing: skipping the owner comparison let another PID call release without error.
 * Shown failing: skipping ordinary teardown left the preview group alive.
 */
import { spawn, spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test, expect } from "@playwright/test";
import { BuildLock, BUNDLE_HOLDER } from "../../support/build-lock.js";
import {
  groupAlive,
  RecordedServer,
  stopGroup,
} from "../../support/process-group.js";
import { readStateFile } from "../../support/state-file.js";
import {
  ensureUiServer,
  releaseUiServer,
  stopUiServer,
  UI_PREVIEW_OWNER_ENV,
} from "../../support/ui-server.js";

const PATTERN = /e2e-ui-preview-fixture/;

test.describe("campaign UI preview", () => {
  let directory: string;
  let lock: BuildLock;
  let groups: number[];
  let previousOwner: string | undefined;

  test.beforeEach(() => {
    directory = fs.mkdtempSync(path.join(os.tmpdir(), "e2e-ui-owner-"));
    lock = new BuildLock(path.join(directory, "build.lock"));
    groups = [];
    previousOwner = process.env[UI_PREVIEW_OWNER_ENV];
    delete process.env[UI_PREVIEW_OWNER_ENV];
  });

  test.afterEach(async () => {
    if (previousOwner === undefined) delete process.env[UI_PREVIEW_OWNER_ENV];
    else process.env[UI_PREVIEW_OWNER_ENV] = previousOwner;
    for (const pid of groups) if (groupAlive(pid)) await stopGroup(pid);
    fs.rmSync(directory, { recursive: true, force: true });
  });

  function start(source: string, ...args: string[]): number {
    const child = spawn(process.execPath, ["-e", source, ...args], {
      detached: true,
      stdio: "ignore",
    });
    child.unref();
    if (child.pid === undefined)
      throw new Error("preview fixture did not start");
    groups.push(child.pid);
    return child.pid;
  }

  function owner(): number {
    return start("setTimeout(() => {}, 60000)", "e2e-ui-campaign-owner");
  }

  async function preview(marker = "e2e-ui-preview-fixture") {
    const portFile = path.join(directory, "port");
    const pid = start(
      `const fs = require("node:fs");
       require("node:http").createServer((_request, response) => response.end("preview"))
         .listen(0, "127.0.0.1", function () { fs.writeFileSync(process.argv[1], String(this.address().port)); });`,
      portFile,
      marker,
    );
    await expect.poll(() => fs.existsSync(portFile)).toBe(true);
    const url = `http://127.0.0.1:${fs.readFileSync(portFile, "utf8")}`;
    return { pid, answers: async () => (await fetch(url)).ok };
  }

  const recorded = (runner = process.pid) =>
    new RecordedServer(path.join(directory, "server.json"), PATTERN, runner);

  function ensure(
    state: RecordedServer,
    answers: () => Promise<boolean>,
    start?: () => Promise<number>,
  ) {
    return ensureUiServer(lock, state, answers, async () => false, start);
  }

  test("a new preview belongs to the external operator", async () => {
    const operator = owner();
    process.env[UI_PREVIEW_OWNER_ENV] = String(operator);
    let running: Awaited<ReturnType<typeof preview>> | undefined;
    const state = recorded();
    const result = await ensure(
      state,
      async () => (running ? running.answers() : false),
      async () => {
        running = await preview();
        return running.pid;
      },
    );
    expect(result).toEqual({ pid: running!.pid, built: false });
    expect(readStateFile(state.stateFile)).toEqual({
      pid: running!.pid,
      owner: operator,
    });
  });

  test("two suites retain the live campaign preview", async () => {
    const operator = owner();
    const running = await preview();
    const first = recorded();
    new RecordedServer(first.stateFile, PATTERN, operator).record(running.pid);
    process.env[UI_PREVIEW_OWNER_ENV] = String(operator);
    expect(new BuildLock(lock.file, lock.runner).reserve(BUNDLE_HOLDER)).toBe(
      true,
    );

    expect(await ensure(first, running.answers)).toEqual({
      pid: running.pid,
      built: false,
    });
    expect(fs.existsSync(lock.file)).toBe(false);
    await stopUiServer(first);
    expect(await running.answers()).toBe(true);
    const second = recorded(process.ppid);
    expect(await ensure(second, running.answers)).toEqual({
      pid: running.pid,
      built: false,
    });
    await stopUiServer(second);
    expect(readStateFile(first.stateFile)).toEqual({
      pid: running.pid,
      owner: operator,
    });

    await releaseUiServer(operator, second);
    expect(groupAlive(running.pid)).toBe(false);
    expect(groupAlive(operator)).toBe(true);
  });

  test("a dead campaign owner is diagnosed without adoption", async () => {
    const exited = spawnSync(process.execPath, ["-e", ""]).pid;
    const running = await preview();
    const state = recorded();
    new RecordedServer(state.stateFile, PATTERN, exited).record(running.pid);
    process.env[UI_PREVIEW_OWNER_ENV] = String(exited);
    await expect(
      ensure(state, running.answers),
    ).rejects.toThrow("live campaign owner");
    expect(groupAlive(running.pid)).toBe(true);
    expect(readStateFile(state.stateFile)).toEqual({
      pid: running.pid,
      owner: exited,
    });
  });

  test("an unrelated process is not adopted as the preview", async () => {
    const operator = owner();
    const unrelated = await preview("e2e-unrelated-server");
    const state = recorded();
    new RecordedServer(state.stateFile, PATTERN, operator).record(
      unrelated.pid,
    );
    process.env[UI_PREVIEW_OWNER_ENV] = String(operator);
    await expect(
      ensure(state, unrelated.answers),
    ).rejects.toThrow("stale UI preview record");
    expect(groupAlive(unrelated.pid)).toBe(true);
    await releaseUiServer(operator, state);
    expect(groupAlive(unrelated.pid)).toBe(true);
  });

  test("suite teardown leaves external ownership intact", async () => {
    const operator = owner();
    const running = await preview();
    const state = recorded(operator);
    state.record(running.pid);
    process.env[UI_PREVIEW_OWNER_ENV] = String(operator);
    await stopUiServer(state);
    expect(await running.answers()).toBe(true);
    await releaseUiServer(operator, state);
    expect(groupAlive(running.pid)).toBe(false);
  });

  test("another owner cannot release the campaign preview", async () => {
    const operator = owner();
    const running = await preview();
    const state = recorded();
    new RecordedServer(state.stateFile, PATTERN, operator).record(running.pid);
    await expect(releaseUiServer(process.pid, state)).rejects.toThrow(
      "Cannot release UI preview owned by",
    );
    expect(groupAlive(running.pid)).toBe(true);
  });

  test("default teardown still stops its own preview", async () => {
    const running = await preview();
    const state = recorded();
    state.record(running.pid);
    await stopUiServer(state);
    expect(groupAlive(running.pid)).toBe(false);
    expect(fs.existsSync(state.stateFile)).toBe(false);
  });
});
