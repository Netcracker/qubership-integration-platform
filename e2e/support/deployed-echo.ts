import { expect, type APIRequestContext } from "@playwright/test";
import { fail, type Catalog, type DomainType } from "./catalog.js";
import { createDeployableChain, MARKER_HEADER } from "./chain-builder.js";
import { callChain, hasTrace, isSettled, SESSION_TIMEOUT, Sessions, trace } from "./sessions.js";
import { waitForFirstRecording } from "./recording.js";
import { assertExternalAuthorized, inAuthPhase } from "../env/external-auth.js";
import { ownResource } from "./owned-resources.js";
import type { EchoChain } from "../suites/types.js";

interface EchoOptions {
  name: string;
  parentId: string;
  marker: string;
  url: string;
  deployment: { domain: string; domainType?: DomainType };
  timeout: number;
  sessionsBase: string;
  legacyRouteProbe?: boolean;
}

export async function waitForEchoRoute(
  engine: APIRequestContext,
  url: string,
  status: 404 | 405,
  timeout: number,
): Promise<void> {
  await expect.poll(async () => {
    const response = await engine.get(url);
    try {
      assertExternalAuthorized(response.status(), "engine");
      return response.status();
    } finally {
      await response.dispose();
    }
  }, { timeout, message: status === 405 ? "Wait for the echo route to accept POST." : "Wait for the echo route to be removed." }).toBe(status);
}

export async function withDeployedEcho(
  catalog: Catalog,
  engine: APIRequestContext,
  sessions: APIRequestContext,
  options: EchoOptions,
  use: (echo: EchoChain) => Promise<void>,
): Promise<void> {
  const { name, parentId, marker, url, deployment, timeout, sessionsBase } = options;
  const chain = await inAuthPhase("setup", () =>
    createDeployableChain(catalog, { name, parentId, contextPath: name, marker }));
  let deploymentRequested = false;
  await ownResource({ kind: "chain", id: chain.id }, async () => {
    await inAuthPhase("setup", async () => {
      await catalog.saveLoggingProperties(chain.id, { sessionsLoggingLevel: "DEBUG" });
      const snapshot = await catalog.createSnapshot(chain.id);
      deploymentRequested = true;
      await catalog.deploy(chain.id, snapshot.id, deployment.domain, deployment.domainType);
      if (options.legacyRouteProbe) {
        await expect(async () => {
          const response = await engine.post(url, { data: { ping: "ready" }, headers: { "Content-Type": "application/json" } });
          try {
            expect(response.status()).toBe(200);
            expect(response.headers()[MARKER_HEADER]).toBe(marker);
          } finally {
            await response.dispose();
          }
        }).toPass({ timeout });
      } else {
        await waitForEchoRoute(engine, url, 405, timeout);
      }
      const recorded = new Sessions(sessions, { base: sessionsBase });
      await waitForFirstRecording(name, {
        send: async (token) => {
          const { response } = await callChain(engine, url, { token, data: { ping: "recording" } });
          try {
            assertExternalAuthorized(response.status(), "engine");
            expect(response.status()).toBe(200);
            expect(response.headers()[MARKER_HEADER]).toBe(marker);
          } finally {
            await response.dispose();
          }
        },
        recorded: async (token) => {
          const { session, status } = await recorded.attemptByExternalId(token);
          assertExternalAuthorized(status, "sessions-management");
          return session !== null && hasTrace(session) && trace(session).length >= 3 && isSettled(session);
        },
      }, SESSION_TIMEOUT);
    });
    await use({ id: chain.id, url, marker, markerHeader: MARKER_HEADER });
  }, async () => {
    try {
      if (deploymentRequested) {
        await catalog.undeployAll(chain.id);
        if (options.legacyRouteProbe) {
          await expect(async () => {
            const response = await engine.post(url, { data: {} });
            try { expect(response.status()).toBe(404); } finally { await response.dispose(); }
          }).toPass({ timeout });
        } else {
          await waitForEchoRoute(engine, url, 404, timeout);
        }
      }
    } finally {
      try { await catalog.deleteLoggingProperties(chain.id); } finally {
        const url = `${sessionsBase}/v1/sessions/chains`;
        const response = await sessions.delete(url, {
          params: { chainIds: chain.id },
        });
        try {
          if (!response.ok()) await fail(response, "DELETE", url, "sessions-management");
        } finally {
          await response.dispose();
        }
      }
    }
  });
}
