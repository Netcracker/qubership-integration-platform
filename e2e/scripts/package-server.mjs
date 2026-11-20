import { createServer } from "node:http";
import { randomUUID } from "node:crypto";
import assert from "node:assert/strict";

export async function packageServer() {
  const foreignChainId = "e2e0f001-0000-4000-8000-000000000001";
  const nilChainId = "00000000-0000-0000-0000-000000000000";
  const folders = new Map([["foreign", { id: "foreign", name: "foreign" }]]);
  const chains = new Map([foreignChainId, nilChainId].map((id) => [id, { id, parentId: "foreign", elements: [] }]));
  const sessions = new Map([["foreign", { chainId: foreignChainId }]]);
  const log = [];
  const healthLog = [];
  let healthStatus = "UP";
  let browserLogins = 0;
  let corrupt = false;
  let dropWarmup = false;
  let denyEcho = false;
  let denyUndeploy = false;
  let restartLog = "info";
  let unauthorized = 0;
  let fullSessionDeleted = false;
  const server = createServer(async (request, response) => {
    const url = new URL(request.url, "http://localhost");
    const underApi = url.pathname.startsWith("/api/engine/");
    const [, role, ...segments] = (underApi ? url.pathname.slice(4) : url.pathname).split("/");
    const path = `/${segments.join("/")}`;
    const method = request.method;
    const answer = (body, status = 200, headers = {}) => {
      response.writeHead(status, { "Content-Type": "application/json", ...headers });
      response.end(body === undefined ? undefined : JSON.stringify(body));
    };
    if (url.pathname === "/infrastructure/log") {
      const deployment = [...chains.values()].find((chain) => chain.deployed)?.deployment;
      const level = restartLog === "error" ? "ERROR" : "INFO";
      answer(restartLog === "empty" ? "" : `[${level}] Deployment ${deployment.id} tag(exception=none)`);
      return;
    }
    if (url.pathname === "/login") {
      if (request.headers["x-qip-ui"] !== "configured") {
        answer(undefined, 403);
        return;
      }
      response.writeHead(200, {
        "Content-Type": "text/html",
        "Set-Cookie": "qip-login=active; Path=/; HttpOnly",
      });
      response.end('<html><body><main>Signed in</main><script src="/api/login-probe"></script></body></html>');
      browserLogins++;
      return;
    }
    if (url.pathname === "/api/login-probe") {
      answer(undefined, 401);
      return;
    }
    if (url.pathname === "/api/browser-denied") {
      answer({ error: "secret-ui-response-sentinel" }, 401);
      return;
    }
    if (url.pathname === "/browser-application") {
      response.writeHead(200, { "Content-Type": "text/html" });
      const contents = url.searchParams.has("failed") ? "secret-ui-page-sentinel"
        : request.headers.cookie?.includes("qip-login=active") ? "Authorized" : "Sign in";
      response.end(`<html><body><main>${contents}</main></body></html>`);
      return;
    }
    if (path === "/actuator/health" && ["catalog", "engine", "sessions"].includes(role)) {
      healthLog.push(`${method} ${url.pathname}`);
      const auth = request.headers.authorization;
      if ((underApi || auth) && auth !== "Bearer package-test") { answer({ error: "Unauthorized" }, 401); return; }
      answer({ status: healthStatus, groups: ["liveness", "readiness"] });
      return;
    }
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
        const drop = (id) => {
          folders.delete(id);
          for (const [key, folder] of folders) if (folder.parentId === id) drop(key);
          for (const [key, chain] of chains) if (chain.parentId === id) chains.delete(key);
        };
        drop(segments.at(-1));
        answer(undefined, 204);
        return;
      }
      if (method === "GET" && path.startsWith("/v1/folders/") && path.endsWith("/chains")) {
        answer([...chains.values()].filter(({ parentId }) => parentId === segments[2]));
        return;
      }
      if (path === "/v1/chains/bulk-delete" && method === "POST") {
        for (const id of data) chains.delete(id);
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
        if (!chain) {
          const message = suffix === "/migrate" ? `Can't find chain with id: ${id}` : undefined;
          answer({ errorMessage: message }, 404);
          return;
        }
        if (suffix === "" && method === "GET") { answer(chain); return; }
        if (suffix === "" && method === "DELETE") {
          chains.delete(id);
          answer(undefined);
          return;
        }
        if (suffix === "/elements" && method === "POST") {
          const element = {
            id: randomUUID(), type: data.type, properties: {},
            name: data.type === "http-trigger" ? "HTTP Trigger" : "Header Modification",
          };
          if (data.type === "choice") {
            element.children = ["when", "otherwise"].map((type) => ({ id: randomUUID(), type }));
            chain.containsDeprecatedContainers = true;
          }
          chain.elements.push(element);
          answer({ createdElements: [element] });
          return;
        }
        if (suffix === "/elements" && method === "GET") {
          answer(chain.elements.flatMap((element) => [element, ...(element.children ?? [])]));
          return;
        }
        if (suffix === "/migrate" && method === "PATCH") {
          for (const element of chain.elements) {
            element.type = "condition";
            element.children = [{ id: randomUUID(), type: "if", properties: { priority: 0 } }, { id: randomUUID(), type: "else" }];
          }
          chain.containsDeprecatedContainers = false;
          answer({ chain, groupsRemoved: false });
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
          if (method === "GET") { answer(chain.deployed ? [chain.deployment] : []); return; }
          if (method === "DELETE" && denyUndeploy) { answer({ error: "secret-cleanup-response" }, 403); return; }
          chain.deployed = method === "POST";
          if (chain.deployed) chain.deployment = { id: randomUUID(), snapshotId: data.snapshotId };
          answer(chain.deployed ? chain.deployment : undefined, chain.deployed ? 200 : 204);
          return;
        }
        if (suffix === "/dependencies") { answer({}); return; }
      }
    }
    if (role === "engine" && (path.startsWith("/routes/") || path.startsWith("/e2e-"))) {
      const contextPath = segments.at(-1);
      const chain = [...chains.values()].find((item) => item.deployed && item.elements.some((element) => element.properties.contextPath === contextPath));
      if (!chain) { answer({}, 404); return; }
      if (method === "GET") { answer(undefined, 405); return; }
      if (denyEcho && data?.ping === "runtime") { answer({ error: "secret-engine-response" }, 401); return; }
      const token = request.headers["external-session-cip-id"];
      if (dropWarmup && data?.ping === "recording") {
        dropWarmup = false;
      } else if (token) {
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
    restore() { corrupt = false; },
    setHealth(status) { healthStatus = status; },
    dropFirstWarmup() { dropWarmup = true; },
    denyRuntime(value) { denyEcho = value; denyUndeploy = value; },
    setRestartLog(value) { restartLog = value; },
    browserLogins() { return browserLogins; },
    healthRequests() { return [...healthLog].sort(); },
    platformRequests() { return log.length + unauthorized; },
    assertClean() {
      assert.deepEqual([...folders.keys()], ["foreign"]);
      assert.deepEqual([...chains.keys()], [foreignChainId, nilChainId]);
      assert.equal(chains.get(nilChainId).containsDeprecatedContainers, undefined);
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
