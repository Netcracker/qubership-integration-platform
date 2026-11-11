/** Records client progress and signals a slow request before its timeout expires. */
import fs from "node:fs";
import path from "node:path";
import http from "node:http";
import { test, expect } from "@playwright/test";
import { diagnosticFetch, resetDiagnosticConnections } from "../../support/http-diagnostic.js";

test.describe("HTTP diagnostics", () => {
  for (const slow of [false, true]) {
    test(slow ? "a pending request signals diagnostics before timing out" : "a completed request records its ID, socket, and response", async ({ playwright }, testInfo) => {
      const directory = testInfo.outputPath("http");
      const previous = process.env.E2E_HTTP_DIAGNOSTIC_DIR;
      process.env.E2E_HTTP_DIAGNOSTIC_DIR = directory;
      let receivedId: string | string[] | undefined;
      let calls = 0;
      const server = http.createServer((req, res) => {
        receivedId = req.headers["x-request-id"];
        calls++;
        if (!slow) res.end("ok");
      });
      await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
      const port = (server.address() as { port: number }).port;
      const api = await playwright.request.newContext();
      try {
        const call = diagnosticFetch(api, `http://127.0.0.1:${port}/v1/folders`, { method: "POST", data: { name: "owned-probe" }, timeout: slow ? 5_500 : 30_000 });
        if (slow) await expect(call).rejects.toThrow(/Timeout 5500ms exceeded/);
        else expect((await call).status()).toBe(200);
        expect(calls).toBe(1);
        expect(receivedId).toMatch(/^diag-/);
        const events = fs.readFileSync(path.join(directory, `client-${process.pid}.jsonl`), "utf8").trim().split("\n").map((line) => JSON.parse(line));
        expect(events.every((event) => event.requestId === receivedId)).toBe(true);
        expect(events.some((event) => event.phase === "node-request")).toBe(true);
        expect(events.some((event) => event.phase === "request-flushed" && event.localPort > 0)).toBe(true);
        if (slow) {
          expect(events.some((event) => event.phase === "slow")).toBe(true);
          expect(events.some((event) => event.phase === "error")).toBe(true);
          expect(fs.readdirSync(directory).some((file) => file.endsWith(".trigger"))).toBe(true);
        } else {
          expect(events.some((event) => event.phase === "response-end" && event.complete)).toBe(true);
          expect(events.some((event) => event.phase === "end" && event.status === 200)).toBe(true);
        }
      } finally {
        await api.dispose();
        server.closeAllConnections();
        await new Promise<void>((resolve) => server.close(() => resolve()));
        if (previous === undefined) delete process.env.E2E_HTTP_DIAGNOSTIC_DIR;
        else process.env.E2E_HTTP_DIAGNOSTIC_DIR = previous;
      }
    });
  }
  test("cold probes reach the server on distinct TCP connections", async ({ playwright }, testInfo) => {
    const previousDirectory = process.env.E2E_HTTP_DIAGNOSTIC_DIR;
    process.env.E2E_HTTP_DIAGNOSTIC_DIR = testInfo.outputPath("cold");
    const ports = new Set<number>();
    const connectionHeaders: Array<string | undefined> = [];
    const server = http.createServer((req, res) => {
      ports.add(req.socket.remotePort!);
      connectionHeaders.push(req.headers.connection);
      res.end("ok");
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const api = await playwright.request.newContext();
    try {
      for (let index = 0; index < 10; index++) {
        resetDiagnosticConnections();
        const response = await diagnosticFetch(api, `http://127.0.0.1:${(server.address() as { port: number }).port}/v1/folders`, { method: "POST", data: { index } });
        expect(response.status()).toBe(200);
      }
      expect(ports.size).toBe(10);
      expect(connectionHeaders).not.toContain("close");
    } finally {
      await api.dispose();
      server.closeAllConnections();
      await new Promise<void>((resolve) => server.close(() => resolve()));
      if (previousDirectory === undefined) delete process.env.E2E_HTTP_DIAGNOSTIC_DIR;
      else process.env.E2E_HTTP_DIAGNOSTIC_DIR = previousDirectory;
    }
  });
});
