import { createServer } from "node:http";
import { randomUUID } from "node:crypto";
import assert from "node:assert/strict";

export async function packageServer() {
  const folders = new Map([["foreign", { id: "foreign", name: "foreign" }]]);
  const chains = new Map([["foreign", { id: "foreign", parentId: "foreign" }]]);
  const sessions = new Map([["foreign", { chainId: "foreign" }]]);
  const log = [];
  let corrupt = false;
  let unauthorized = 0;
  let fullSessionDeleted = false;
  const server = createServer(async (request, response) => {
    const url = new URL(request.url, "http://localhost");
    const [, role, ...segments] = url.pathname.split("/");
    const path = `/${segments.join("/")}`;
    const method = request.method;
    const answer = (body, status = 200, headers = {}) => {
      response.writeHead(status, { "Content-Type": "application/json", ...headers });
      response.end(body === undefined ? undefined : JSON.stringify(body));
    };
    if (role === "unrelated") {
      answer({ authorized: Boolean(request.headers.authorization || request.headers["x-service"]) });
      return;
    }
    const fullSessionRequest = role === "sessions" && (
      path === "/fixture" || path === "/v1/sessions" || url.searchParams.get("chainIds")?.startsWith("e2e-")
    );
    const expected = fullSessionRequest ? "Bearer package-sessions" : "Bearer package-test";
    if (request.headers.authorization !== expected || request.headers["x-service"] !== role) {
      unauthorized++;
      answer({ error: "Unauthorized" }, 401);
      return;
    }
    log.push(`${role} ${method} ${path}`);
    let body = "";
    for await (const chunk of request) body += chunk;
    const data = body ? JSON.parse(body) : undefined;

    if (role === "catalog") {
      if (method === "GET") {
        if (path === "/v1/folders") { answer([...folders.values()]); return; }
        if (path === "/v1/chains") { answer([...chains.values()]); return; }
        if (path === "/v1/common-variables") { answer({}); return; }
        const emptyListings = [
          "/v1/catalog/domains", "/v1/systems", "/v1/catalog/context-system",
          "/v1/catalog/mcp-system", "/v1/detailed-design/templates", "/v2/secured-variables",
        ];
        if (emptyListings.includes(path)) { answer([]); return; }
      }
      if (path === "/v1/folders" && method === "POST") {
        const folder = { id: randomUUID(), ...data };
        folders.set(folder.id, folder);
        answer(folder);
        return;
      }
      if (path.startsWith("/v1/folders/") && method === "DELETE") {
        const id = segments.at(-1);
        folders.delete(id);
        for (const [key, chain] of chains) if (chain.parentId === id) chains.delete(key);
        answer(undefined, 204);
        return;
      }
      if (path === "/v1/chains" && method === "POST") {
        const chain = { id: randomUUID(), ...data, elements: [] };
        chains.set(chain.id, chain);
        answer(chain);
        return;
      }
      const match = path.match(/^\/v1\/(?:catalog\/)?chains\/([^/]+)(.*)$/);
      if (match) {
        const [, id, suffix] = match;
        const chain = chains.get(id);
        if (!chain) { answer({}, 404); return; }
        if (suffix === "" && method === "GET") { answer(chain); return; }
        if (suffix === "/elements" && method === "POST") {
          const element = {
            id: randomUUID(), type: data.type, properties: {},
            name: data.type === "http-trigger" ? "HTTP Trigger" : "Header Modification",
          };
          chain.elements.push(element);
          answer({ createdElements: [element] });
          return;
        }
        if (suffix.startsWith("/elements/")) {
          const element = chain.elements.find((item) => item.id === segments.at(-1));
          if (method === "PATCH") Object.assign(element, data);
          answer(method === "GET" ? element : { updatedElements: [element] });
          return;
        }
        if (suffix === "/properties/logging") {
          chain.logging = method === "POST";
          answer(undefined, method === "DELETE" ? 204 : 200);
          return;
        }
        if (suffix === "/snapshots") { answer({ id: randomUUID() }); return; }
        if (suffix === "/deployments") {
          chain.deployed = method === "POST";
          answer(method === "POST" ? { id: randomUUID() } : undefined, method === "POST" ? 200 : 204);
          return;
        }
        if (suffix === "/dependencies") { answer({}); return; }
      }
    }
    if (role === "engine" && path.startsWith("/routes/")) {
      const contextPath = segments.at(-1);
      const chain = [...chains.values()].find((item) => item.deployed && item.elements.some((element) => element.properties.contextPath === contextPath));
      if (!chain) { answer({}, 404); return; }
      const token = request.headers["external-session-cip-id"];
      if (token) {
        const steps = ["HTTP Trigger", "Validate Request", "Header Modification"].map((elementName) => ({
          elementName, executionStatus: "COMPLETED_NORMALLY", children: [],
        }));
        sessions.set(token, {
          id: randomUUID(), chainId: chain.id, externalSessionCipId: token,
          executionStatus: "COMPLETED_NORMALLY", sessionElements: steps,
        });
      }
      const marker = chain.elements.find((item) => item.type === "header-modification").properties.headerModificationToAdd["e2e-marker"];
      answer(corrupt && data?.ping === "runtime" ? { wrong: true } : data, 200, {
        "e2e-marker": marker, "consumer-marker": "consumer",
      });
      return;
    }
    if (role === "sessions") {
      if (path === "/fixture" && method === "POST") {
        sessions.set(data.chainId, data);
        answer(undefined, 204);
        return;
      }
      if (path === "/v1/sessions" && method === "POST") {
        const offset = Number(url.searchParams.get("offset"));
        answer({ sessions: [...sessions.values()].slice(offset) });
        return;
      }
      if (path.startsWith("/v1/sessions/external-id/")) {
        const session = sessions.get(segments.at(-1));
        answer(session ?? {}, session ? 200 : 404);
        return;
      }
      if (path === "/v1/sessions/chains" && method === "DELETE") {
        for (const [token, session] of sessions) {
          if (session.chainId !== url.searchParams.get("chainIds")) continue;
          sessions.delete(token);
          if (session.chainName) fullSessionDeleted = true;
        }
        answer(undefined, 204);
        return;
      }
    }
    if (role === "testing" && path === "/api/v1/endpoint-mocks" && method === "POST") {
      answer([]);
      return;
    }
    answer({ error: `Unhandled ${role} ${method} ${path}` }, 404);
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  return {
    url: `http://127.0.0.1:${server.address().port}`,
    corrupt() { corrupt = true; },
    assertClean() {
      assert.deepEqual([...folders.keys()], ["foreign"]);
      assert.deepEqual([...chains.keys()], ["foreign"]);
      assert.deepEqual([...sessions.keys()], ["foreign"]);
      assert.equal(unauthorized, 0);
      assert(log.some((line) => line.includes("catalog POST /v1/catalog/chains/")));
      assert(log.some((line) => line.startsWith("sessions DELETE /v1/sessions/chains")));
    },
    assertFullClean() {
      this.assertClean();
      assert(fullSessionDeleted, "The full suite did not remove its session during global teardown.");
    },
    close() { return new Promise((resolve) => server.close(resolve)); },
  };
}
