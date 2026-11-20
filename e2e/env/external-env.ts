import { pathToFileURL } from "node:url";
import type { TestType } from "@playwright/test";
import { apiPath } from "./api-routes.js";
import type { Env } from "./index.js";
import { externalService, type ExternalSettings } from "./external-settings.js";

export type ExternalInfrastructure = Pick<Env, "restart" | "processEnvelope">
  & Partial<Pick<Env, "logs" | "engineLogs" | "engineEvidence" | "resourcePeaks">>;

export type ExternalEnv = Pick<Env, "name" | "generatedRequestId" | "url" | "apiUrl"
  | "chainUrl" | "engineUrl" | "engineMetricsUrl" | "uiUrl"> & {
  infrastructure?: ExternalInfrastructure;
};

export type ExternalTest<T> = T extends TestType<infer Fixtures, infer Workers>
  ? TestType<Fixtures, Omit<Workers, "env"> & { env: ExternalEnv }>
  : never;

export async function createExternalEnv(settings: ExternalSettings): Promise<ExternalEnv> {
  const proxy = settings.proxy ?? settings.ui;
  const url: Env["url"] = (role) => externalService(settings, role);
  const adapter: ExternalInfrastructure | undefined = settings.infrastructure
    ? (await import(pathToFileURL(settings.infrastructure).href)).default
    : undefined;

  if (settings.infrastructure && (!adapter || typeof adapter.restart !== "function" || typeof adapter.processEnvelope !== "function")) {
    throw new Error("Export restart and processEnvelope from the external infrastructure adapter.");
  }
  return {
    name: "external",
    generatedRequestId: null,
    url,
    apiUrl: (role, servicePath) => proxy
      ? `${proxy}${apiPath(role, servicePath)}`
      : `${url(role)}${servicePath}`,
    chainUrl: (contextPath) => [url("engine"), settings.routePrefix, contextPath.replace(/^\/+/, "")].filter(Boolean).join("/"),
    engineUrl: () => url("engine"),
    engineMetricsUrl: () => `${url("engine")}/actuator/prometheus`,
    uiUrl: (page) => {
      if (!settings.ui) throw new Error("Set ui in the external suite configuration.");
      return `${settings.ui}${page}`;
    },
    infrastructure: adapter,
  };
}
