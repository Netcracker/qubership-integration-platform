import { expect, type APIRequestContext } from "@playwright/test";
import { assertExternalAuthorized } from "../env/external-auth.js";
import type { ServiceRole } from "../env/index.js";

export const PROBE_GROUPS = ["liveness", "readiness"];

interface HealthDocument {
  status?: string;
  groups?: string[];
  components?: Record<string, { status?: string }>;
}

export async function health(request: APIRequestContext, url: string, role?: ServiceRole): Promise<HealthDocument> {
  const response = await request.get(url, { failOnStatusCode: false, maxRedirects: 0 });
  if (role) assertExternalAuthorized(response.status(), role);
  expect(response.status(), `GET ${new URL(url).pathname} health status`).toBe(200);
  return (await response.json()) as HealthDocument;
}

export async function expectActuatorUp(request: APIRequestContext, base: string, role?: ServiceRole): Promise<void> {
  const document = await health(request, `${base}/actuator/health`, role);
  expect(document.status).toBe("UP");
  expect([...(document.groups ?? [])].sort()).toEqual(PROBE_GROUPS);
}

export async function expectTestingServiceUp(request: APIRequestContext, base: string): Promise<void> {
  expect(await health(request, `${base}/health`, "testing-service")).toEqual({ status: "UP" });
}
