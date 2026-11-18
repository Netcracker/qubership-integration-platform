import type { APIRequestContext, Request } from "@playwright/test";
import { apiOptions } from "./suite-extension.ts";
import { ROLES, serviceUrl, proxyUrl } from "./containers.ts";

function headersFor(url: string): Record<string, string> {
  if (!process.env.QIP_E2E_API_OPTIONS) return {};
  const matches = (base: string) => url === base || url.startsWith(`${base}/`) || url.startsWith(`${base}?`);
  const role = [...ROLES].sort((a, b) => serviceUrl(b).length - serviceUrl(a).length)
    .find((candidate) => matches(serviceUrl(candidate).replace(/\/+$/, "")));
  if (!role && !matches(proxyUrl().replace(/\/+$/, ""))) return {};
  return apiOptions(role).extraHTTPHeaders ?? {};
}

export const platformFetch: typeof globalThis.fetch = (input, init) => {
  const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
  const additional = headersFor(url);
  if (Object.keys(additional).length === 0) return globalThis.fetch(input, init);
  const headers = new Headers(typeof input === "object" && "headers" in input ? input.headers : undefined);
  for (const [name, value] of Object.entries(additional)) headers.set(name, value);
  new Headers(init?.headers).forEach((value, name) => headers.set(name, value));
  return globalThis.fetch(input, { ...init, headers });
};

const methods = new Set(["get", "post", "put", "patch", "delete", "head", "fetch"]);

export function authenticatedRequest(api: APIRequestContext, baseURL: string | undefined): APIRequestContext {
  return new Proxy(api, {
    get(target, key) {
      const value: unknown = Reflect.get(target, key);
      if (typeof value !== "function") return value;
      if (typeof key !== "string" || !methods.has(key)) return value.bind(target);
      return (request: string | Request, options?: { headers?: Record<string, string> }) => {
        const url = typeof request === "string" ? URL.parse(request, baseURL)?.href : request.url();
        const additional = url ? headersFor(url) : {};
        if (Object.keys(additional).length === 0) return value.call(target, request, options);
        const headers = new Headers(additional);
        if (typeof request !== "string") {
          new Headers(request.headers()).forEach((value, name) => headers.set(name, value));
        }
        new Headers(options?.headers).forEach((value, name) => headers.set(name, value));
        return value.call(target, request, { ...options, headers: Object.fromEntries(headers) });
      };
    },
  });
}
