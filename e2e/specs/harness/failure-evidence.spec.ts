/**
 * What a failed case carries beyond its assertion, with no stack: the route history `waitForRoutes`
 * adds to its message, the engine evidence `attachDiagnostics` attaches, and the record that keeps
 * the micro domains for the cluster dump.
 */
import fs from "node:fs";
import http from "node:http";
import type { AddressInfo } from "node:net";
import os from "node:os";
import path from "node:path";
import { test, expect, type TestInfo } from "@playwright/test";
import type { Env } from "../../env/index.js";
import { routeHistory, waitForRoutes } from "../../support/corpus.js";
import { attachDiagnostics, beginDiagnostics, microCaseFailed, noteMicroFailure } from "../../support/diagnostics.js";

test.describe("failure evidence", () => {
  test("the route history counts each status in first-seen order", () => {
    const history = new Map([
      ["e2e-run1-chain-call", [503, 500, 405, 500, 405]],
      ["e2e-run1-axis", [0, 0, 500]],
      ["e2e-run1-served", [405, 405]],
    ]);
    expect(routeHistory(history, ["e2e-run1-chain-call", "e2e-run1-axis"])).toBe(
      [
        "what each of those routes answered over the wait, by count:",
        "e2e-run1-axis: no response x2, 500 x1",
        "e2e-run1-chain-call: 503 x1, 500 x2, 405 x2",
      ].join("\n"),
    );
  });

  test("flipping routes fail waitForRoutes with both statuses", async () => {
    // The flip of CI run 38707743367. The two routes flip out of phase, so no poll finds both serving,
    // and the last poll alone shows one status per route.
    const calls = new Map<string, number>();
    const server = http.createServer((request, response) => {
      const call = calls.get(request.url ?? "") ?? 0;
      calls.set(request.url ?? "", call + 1);
      response.statusCode = (call % 2 === 0) === (request.url === "/a") ? 500 : 405;
      response.end();
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const { port } = server.address() as AddressInfo;
    const env = { chainUrl: (contextPath: string) => `http://127.0.0.1:${port}${contextPath}` } as unknown as Env;
    const chains = [
      { id: "c-a", name: "e2e-run1-a", contextPath: "/a" },
      { id: "c-b", name: "e2e-run1-b", contextPath: "/b" },
    ];
    try {
      const failure = await waitForRoutes(env, chains, 2_000).then(
        () => null,
        (cause: Error) => cause.message,
      );
      expect(failure).toMatch(/e2e-run1-a: 500 x\d+, 405 x\d+|e2e-run1-b: 405 x\d+, 500 x\d+/);
    } finally {
      server.close();
    }
  });

  test("a failed case attaches all engine evidence its Env returns", async () => {
    const attached: string[] = [];
    const testInfo = {
      status: "failed",
      expectedStatus: "passed",
      attach: async (name: string, options: { body: string }) => {
        attached.push(`${name}: ${options.body}`);
      },
    } as unknown as TestInfo;
    const env = {
      engineLogs: async () => "",
      engineEvidence: async () => [
        { name: "micro-pod.log", body: "the startup" },
        { name: "micro-integration.yaml", body: "phase: Running" },
      ],
    } as unknown as Env;

    beginDiagnostics();
    await attachDiagnostics(testInfo, env, "classic", "run1");
    expect(attached).toEqual(
      expect.arrayContaining(["micro-pod.log: the startup", "micro-integration.yaml: phase: Running"]),
    );
  });

  test("a failed micro case keeps the domains of its own run only", () => {
    const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "e2e-micro-failure-")), "marker");
    expect(microCaseFailed("run1", file)).toBe(false);
    noteMicroFailure("run1", file);
    expect(microCaseFailed("run1", file)).toBe(true);
    // A marker an earlier run left names that run, so this run's teardown still deletes its domains.
    expect(microCaseFailed("run2", file)).toBe(false);
    fs.rmSync(path.dirname(file), { recursive: true, force: true });
  });
});
