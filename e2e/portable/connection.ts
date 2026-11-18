import type { APIRequest } from "@playwright/test";
import type { DomainType } from "../support/catalog.js";
import { authenticatedRequest, pathWithin } from "../env/platform-http.js";

export type Endpoint = Omit<NonNullable<Parameters<APIRequest["newContext"]>[0]>, "baseURL"> & {
  url: string;
};

export interface QipConnection {
  catalog: Endpoint;
  engine: Endpoint;
  sessions: Endpoint;
  deployment: { domain: string; domainType?: DomainType };
  routePrefix?: string;
  deploymentTimeout?: number;
}

export interface QipOptions {
  qip: QipConnection | undefined;
}

export async function endpointRequest(request: APIRequest, endpoint: Endpoint) {
  const { url, extraHTTPHeaders, ...options } = endpoint;
  const api = await request.newContext({ ...options, baseURL: url });
  if (!extraHTTPHeaders) return api;
  const base = new URL(url);
  return authenticatedRequest(api, url, (target) => pathWithin(target, base) === undefined ? {} : extraHTTPHeaders);
}

export function connection(value: QipConnection | undefined): QipConnection {
  if (!value) throw new Error("Set use.qip to the catalog, engine, sessions, and deployment configuration.");
  for (const role of ["catalog", "engine", "sessions"] as const) {
    const url = new URL(value[role].url);
    if (!["http:", "https:"].includes(url.protocol) || url.search || url.hash || url.username || url.password) {
      throw new Error(`qip.${role}.url must be an HTTP URL without credentials, a query, or a fragment.`);
    }
  }
  if (!value.deployment.domain) throw new Error("Set qip.deployment.domain to the target engine domain.");
  return value;
}

export function baseUrl(endpoint: Endpoint): string {
  return endpoint.url.replace(/\/+$/, "");
}
