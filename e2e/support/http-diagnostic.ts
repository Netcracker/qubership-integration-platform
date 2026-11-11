import fs from "node:fs";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { subscribe } from "node:diagnostics_channel";
import type { ClientRequest } from "node:http";
import type { Socket } from "node:net";

const completedSockets = new Set<Socket>();

/** Called only between completed diagnostic requests. */
export function resetDiagnosticConnections(): void {
  for (const socket of completedSockets) socket.destroy();
  completedSockets.clear();
}
import type { APIRequestContext, APIResponse } from "@playwright/test";

function record(event: object): void {
  const directory = process.env.E2E_HTTP_DIAGNOSTIC_DIR;
  if (!directory) return;
  fs.mkdirSync(directory, { recursive: true });
  fs.appendFileSync(path.join(directory, `client-${process.pid}.jsonl`), JSON.stringify({ at: new Date().toISOString(), pid: process.pid, stage: process.env.E2E_HTTP_STAGE ?? "nodeport", ...event }) + "\n");
}

subscribe("http.client.request.start", (message) => {
  if (!process.env.E2E_HTTP_DIAGNOSTIC_DIR) return;
  const request = (message as { request: ClientRequest }).request;
  const requestId = String(request.getHeader("X-Request-ID") ?? "");
  if (!requestId.startsWith("diag-")) return;
  const socket = () => ({ localAddress: request.socket?.localAddress, localPort: request.socket?.localPort, remoteAddress: request.socket?.remoteAddress, remotePort: request.socket?.remotePort, bytesRead: request.socket?.bytesRead, bytesWritten: request.socket?.bytesWritten });
  record({ phase: "node-request", requestId, reusedSocket: request.reusedSocket, ...socket() });
  if (request.socket?.connecting) request.socket.once("connect", () => record({ phase: "connected", requestId, ...socket() }));
  request.once("finish", () => record({ phase: "request-flushed", requestId, ...socket() }));
  request.once("response", (response) => {
    record({ phase: "response-headers", requestId, status: response.statusCode, ...socket() });
    response.once("end", () => {
      if (request.socket) completedSockets.add(request.socket);
      record({ phase: "response-end", requestId, complete: response.complete, ...socket() });
    });
    response.once("aborted", () => record({ phase: "response-aborted", requestId, ...socket() }));
  });
  request.once("error", (error) => record({ phase: "node-error", requestId, error: error.message.split("\n")[0], ...socket() }));
});

export async function diagnosticFetch(api: APIRequestContext, url: string, options: NonNullable<Parameters<APIRequestContext["fetch"]>[1]>): Promise<APIResponse> {
  const directory = process.env.E2E_HTTP_DIAGNOSTIC_DIR;
  if (!directory || options.method !== "POST" || new URL(url).pathname !== "/v1/folders") return api.fetch(url, options);
  const headers = { ...options.headers };
  const existing = Object.keys(headers).find((name) => name.toLowerCase() === "x-request-id");
  const requestId = existing ? headers[existing] : `diag-${randomUUID()}`;
  if (!existing) headers["X-Request-ID"] = requestId;
  const started = performance.now();
  record({ phase: "begin", requestId, method: options.method, url });
  const timer = setTimeout(() => {
    record({ phase: "slow", requestId, elapsedMs: performance.now() - started });
    fs.writeFileSync(path.join(directory, `slow-${process.env.E2E_HTTP_STAGE ?? "nodeport"}-${process.pid}-${randomUUID()}.trigger`), requestId);
  }, 5_000);
  timer.unref();
  try {
    const response = await api.fetch(url, { ...options, headers });
    record({ phase: "end", requestId, status: response.status(), elapsedMs: performance.now() - started });
    return response;
  } catch (error) {
    record({ phase: "error", requestId, elapsedMs: performance.now() - started, error: String(error).split("\n")[0] });
    throw error;
  } finally {
    clearTimeout(timer);
  }
}
