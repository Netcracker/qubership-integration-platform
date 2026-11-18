import type { APIRequest, APIRequestContext, Request } from "@playwright/test";
import type { ServiceRole } from "./index.ts";
import { apiOptions } from "./suite-extension.ts";
import { ROLES, serviceUrl, proxyUrl } from "./containers.ts";

export function pathWithin(url: URL, endpoint: URL): string | undefined {
  const base = endpoint.pathname.replace(/\/+$/, "");
  if (url.origin !== endpoint.origin) return;
  if (url.pathname === base) return "";
  if (url.pathname.startsWith(`${base}/`)) return url.pathname.slice(base.length);
}

function proxyRole(path: string): ServiceRole | undefined {
  if (/^\/e2e\/(svc|gateway)\//.test(path)) return "engine";
  const service = /^\/api\/v\d+\/[^/]+\/([^/]+)(?:\/|$)/.exec(path)?.[1];
  if (service === "engine" || service === "sessions-management" || service === "testing-service") return service;
  if (service === "catalog" || service === "variables-management" || service === "systems-catalog"
    || /^\/api\/[^/]+\/v\d+\//.test(path)) return "runtime-catalog";
}

function headersFor(url: URL): Record<string, string> {
  if (!process.env.QIP_E2E_API_OPTIONS) return {};
  const throughProxy = pathWithin(url, new URL(proxyUrl()));
  const proxiedRole = throughProxy === undefined ? undefined : proxyRole(throughProxy);
  if (proxiedRole) return apiOptions(proxiedRole);

  const direct = ROLES.flatMap((role) => {
    const path = pathWithin(url, new URL(serviceUrl(role)));
    return path === undefined ? [] : [{ role, path }];
  }).sort((a, b) => a.path.length - b.path.length)[0];
  if (direct) return apiOptions(direct.role);
  return throughProxy?.startsWith("/api/") ? apiOptions() : {};
}

export const platformFetch: typeof globalThis.fetch = (input, init) => {
  const url = URL.parse(typeof input === "string" ? input : input instanceof URL ? input.href : input.url);
  const additional = url ? headersFor(url) : {};
  if (Object.keys(additional).length === 0) return globalThis.fetch(input, init);
  const headers = new Headers(typeof input === "object" && "headers" in input ? input.headers : undefined);
  for (const [name, value] of Object.entries(additional)) headers.set(name, value);
  new Headers(init?.headers).forEach((value, name) => headers.set(name, value));
  return globalThis.fetch(input, { ...init, headers, redirect: "manual" });
};

const methods = new Set(["get", "post", "put", "patch", "delete", "head", "fetch"]);

export async function newPlatformRequest(request: APIRequest, role: ServiceRole, base = serviceUrl(role)): Promise<APIRequestContext> {
  const api = await request.newContext();
  const headers = apiOptions(role);
  if (Object.keys(headers).length === 0) return api;
  const endpoint = new URL(base);
  return authenticatedRequest(api, undefined, (url) => pathWithin(url, endpoint) === undefined ? {} : headers);
}

export function authenticatedRequest(
  api: APIRequestContext,
  baseURL?: string,
  credentials: (url: URL) => Record<string, string> = headersFor,
): APIRequestContext {
  return new Proxy(api, {
    get(target, key) {
      const value: unknown = Reflect.get(target, key);
      if (typeof value !== "function") return value;
      if (typeof key !== "string" || !methods.has(key)) return value.bind(target);
      return (request: string | Request, options?: Parameters<APIRequestContext["fetch"]>[1]) => {
        const url = URL.parse(typeof request === "string" ? request : request.url(), baseURL);
        const additional = url ? credentials(url) : {};
        if (Object.keys(additional).length === 0) return value.call(target, request, options);
        const headers = new Headers(additional);
        if (typeof request !== "string") {
          new Headers(request.headers()).forEach((value, name) => headers.set(name, value));
        }
        new Headers(options?.headers).forEach((value, name) => headers.set(name, value));
        return value.call(target, request, { ...options, headers: Object.fromEntries(headers), maxRedirects: 0 });
      };
    },
  });
}
