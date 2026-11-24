import type { APIRequest, APIRequestContext, Request } from "@playwright/test";
import { externalHeaders } from "./external-auth.ts";
import { externalSettings } from "./external-settings.ts";
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
  const service = /^\/api\/v\d+\/[^/]+\/([^/]+)(?:\/|$)/.exec(path)?.[1];
  if (service === "engine" || service === "sessions-management" || service === "testing-service") return service;
  if (service === "catalog" || service === "variables-management" || service === "systems-catalog"
    || /^\/api\/[^/]+\/v\d+\//.test(path)) return "runtime-catalog";
}

const engineProxyEndpoints = new Map<string, URL>();

export function engineProxyEndpoint(base: string): string {
  if (externalSettings()?.full && !engineProxyEndpoints.has(base)) {
    engineProxyEndpoints.set(base, new URL(base));
  }
  return base;
}

function externalProxyRole(path: string): ServiceRole | undefined {
  const target = /^\/e2e\/svc\/([^/]+)\//.exec(path)?.[1];
  if (target) return ROLES.find((role) => target === `cip-${role}-v1`);
  if (/^\/api\/(?:v\d+\/cip|cip\/v\d+)\//.test(path)) return proxyRole(path);
}

function matchService(url: URL, getBase: (role: ServiceRole) => string | undefined) {
  return ROLES.flatMap((role) => {
    const base = getBase(role);
    if (!base) return [];
    const within = pathWithin(url, new URL(base));
    return within === undefined ? [] : [{ role, base, within }];
  }).sort((a, b) => a.within.length - b.within.length)[0];
}

type HeadersFor = (url: URL) => Record<string, string> | Promise<Record<string, string>>;

const headersFor: HeadersFor = (url) => {
  const settings = externalSettings();
  if (settings) {
    if (!settings.auth) return {};
    const { auth, services } = settings;
    const proxy = settings.proxy ?? settings.ui;
    const proxied = proxy ? pathWithin(url, new URL(proxy)) : undefined;
    const direct = matchService(url, (role) => services[role]);
    if (direct && (proxied === undefined || direct.within.length < proxied.length)) {
      return externalHeaders(auth, direct.role, direct.base);
    }

    const engineProxy = settings.full && proxied !== undefined
      && [...engineProxyEndpoints.values()].some((endpoint) => pathWithin(url, endpoint) !== undefined);
    const role = engineProxy ? "engine" : proxied === undefined ? undefined : externalProxyRole(proxied);
    if (role) return services[role] ? externalHeaders(auth, role, services[role]) : {};
    if (proxied?.startsWith("/api/") || proxied?.startsWith("/e2e/")) return {};
    return direct ? externalHeaders(auth, direct.role, direct.base) : {};
  }
  if (!process.env.QIP_E2E_API_OPTIONS) return {};
  const throughProxy = pathWithin(url, new URL(proxyUrl()));
  const proxiedRole = throughProxy === undefined ? undefined
    : /^\/e2e\/(svc|gateway)\//.test(throughProxy) ? "engine" : proxyRole(throughProxy);
  if (proxiedRole) return apiOptions(proxiedRole);

  const direct = matchService(url, serviceUrl);
  if (direct) return apiOptions(direct.role);
  return throughProxy?.startsWith("/api/") ? apiOptions() : {};
};

export const platformFetch: typeof globalThis.fetch = async (input, init) => {
  const url = URL.parse(typeof input === "string" ? input : input instanceof URL ? input.href : input.url);
  const additional = url ? await headersFor(url) : {};
  if (Object.keys(additional).length === 0) {
    return globalThis.fetch(input, externalSettings() ? { ...init, redirect: "manual" } : init);
  }
  const headers = new Headers(typeof input === "object" && "headers" in input ? input.headers : undefined);
  for (const [name, value] of Object.entries(additional)) headers.set(name, value);
  new Headers(init?.headers).forEach((value, name) => headers.set(name, value));
  return globalThis.fetch(input, { ...init, headers, redirect: "manual" });
};

const methods = new Set(["get", "post", "put", "patch", "delete", "head", "fetch"]);

export async function newPlatformRequest(request: APIRequest, role: ServiceRole, base = serviceUrl(role)): Promise<APIRequestContext> {
  const api = await request.newContext();
  const settings = externalSettings();
  const legacy = settings ? {} : apiOptions(role);
  if (!settings && Object.keys(legacy).length === 0) return api;
  const endpoint = new URL(base);
  const auth = settings?.auth;
  return authenticatedRequest(api, undefined, (url) => {
    if (pathWithin(url, endpoint) === undefined) return {};
    return auth ? externalHeaders(auth, role, base) : legacy;
  });
}

export function authenticatedRequest(
  api: APIRequestContext,
  baseURL?: string,
  credentials: HeadersFor = headersFor,
): APIRequestContext {
  return new Proxy(api, {
    get(target, key) {
      const value: unknown = Reflect.get(target, key);
      if (typeof value !== "function") return value;
      if (typeof key !== "string" || !methods.has(key)) return value.bind(target);
      return async (request: string | Request, options?: Parameters<APIRequestContext["fetch"]>[1]) => {
        const url = URL.parse(typeof request === "string" ? request : request.url(), baseURL);
        const additional = url ? await credentials(url) : {};
        if (Object.keys(additional).length === 0) {
          return value.call(target, request, externalSettings() ? { ...options, maxRedirects: 0 } : options);
        }
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
