/** An MCP client for the engine's streamable HTTP endpoint at `/mcp`, spoken over the `request` fixture. */
import { expect, type APIRequestContext } from "@playwright/test";
import { DEPLOY_TIMEOUT } from "./corpus.js";

export interface Tool {
  name: string;
  title?: string;
  description?: string;
  annotations?: Record<string, unknown>;
}

/** A streamable HTTP MCP session: JSON-RPC over POST, answered as JSON or as one SSE event. */
export class McpSession {
  private readonly request: APIRequestContext;
  private readonly url: string;
  private id = "";
  private next = 1;

  constructor(request: APIRequestContext, url: string) {
    this.request = request;
    this.url = url;
  }

  async open(): Promise<void> {
    const response = await this.post({ jsonrpc: "2.0", id: this.next++, method: "initialize", params: { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "qip-e2e", version: "1" } } });
    expect(response.status(), await response.text()).toBe(200);
    this.id = response.headers()["mcp-session-id"];
    expect(this.id, "the server opens a session").toBeTruthy();
    expect((await this.post({ jsonrpc: "2.0", method: "notifications/initialized" })).status()).toBe(202);
  }

  async call<T>(method: string, params: Record<string, unknown> = {}): Promise<T> {
    const id = this.next++;
    const response = await this.post({ jsonrpc: "2.0", id, method, params });
    const text = await response.text();
    expect(response.status(), text).toBe(200);
    const payload = response.headers()["content-type"]?.startsWith("text/event-stream")
      ? text.split("\n").filter((line) => line.startsWith("data:")).map((line) => line.slice("data:".length)).join("")
      : text;
    const message = JSON.parse(payload) as { id: number; result?: T; error?: unknown };
    expect(message.id).toBe(id);
    expect(message.error, `${method} answered an error`).toBeUndefined();
    return message.result as T;
  }

  async tools(): Promise<Tool[]> {
    return (await this.call<{ tools: Tool[] }>("tools/list")).tools;
  }

  private post(data: unknown) {
    return this.request.post(this.url, {
      headers: { "Content-Type": "application/json", Accept: "application/json, text/event-stream", ...(this.id ? { "Mcp-Session-Id": this.id } : {}) },
      data,
    });
  }
}

/** The tool a deployed `mcp-trigger` chain offers, once `tools/list` lists it. */
export async function offeredTool(mcp: McpSession, toolName: string): Promise<Tool> {
  let tool: Tool | undefined;
  await expect.poll(async () => (tool = (await mcp.tools()).find((each) => each.name === toolName)) !== undefined, { timeout: DEPLOY_TIMEOUT, message: `tools/list never offered ${toolName}` }).toBe(true);
  return tool!;
}
