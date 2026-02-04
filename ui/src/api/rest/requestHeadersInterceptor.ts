import { AxiosHeaders, type AxiosInstance } from "axios";

export type RequestHeaders = Record<string, string | null | undefined>;

export type RequestHeadersContext = {
  url?: string;
  method?: string;
  baseURL?: string;
};

export type RequestHeadersProvider = (
  context: RequestHeadersContext,
) => RequestHeaders;

export type RequestHeadersEjectHandle = {
  eject: () => void;
};

type ProviderToken = object;

let activeProvider: RequestHeadersProvider | null = null;
let activeProviderToken: ProviderToken | null = null;

let lastRestAxiosInstance: AxiosInstance | null = null;

const knownInstances = new Set<AxiosInstance>();
const attachedRequestInterceptorIds = new WeakMap<AxiosInstance, number>();

function getLocationHrefFallback(): string {
  if (typeof window !== "undefined" && window.location?.href) {
    return window.location.href;
  }
  return "http://localhost/";
}

function shouldAttachHeaders(
  instance: AxiosInstance,
  config: { url?: string; baseURL?: string },
): boolean {
  const urlValue = config.url;
  if (!urlValue) return false;

  const href = getLocationHrefFallback();

  let base: URL;
  try {
    const baseValue =
      config.baseURL ??
      (instance.defaults.baseURL
        ? String(instance.defaults.baseURL)
        : undefined);

    if (baseValue) {
      base = new URL(baseValue, href);
    } else {
      const current = new URL(href);
      base = new URL(current.origin);
    }
  } catch {
    return false;
  }

  let full: URL;
  try {
    full = new URL(urlValue, base);
  } catch {
    return false;
  }

  if (full.origin !== base.origin) return false;

  const basePath = base.pathname;
  const basePrefix = basePath.endsWith("/") ? basePath : `${basePath}/`;

  return full.pathname === basePath || full.pathname.startsWith(basePrefix);
}

function ensureRequestInterceptorAttached(instance: AxiosInstance): void {
  if (attachedRequestInterceptorIds.has(instance)) {
    return;
  }

  const id = instance.interceptors.request.use((config) => {
    const provider = activeProvider;
    if (!provider) {
      return config;
    }

    if (
      !shouldAttachHeaders(instance, {
        url: config.url,
        baseURL: config.baseURL ? String(config.baseURL) : undefined,
      })
    ) {
      return config;
    }

    const context: RequestHeadersContext = {
      url: config.url,
      method: config.method,
      baseURL:
        config.baseURL != null
          ? String(config.baseURL)
          : instance.defaults.baseURL != null
            ? String(instance.defaults.baseURL)
            : undefined,
    };
    const headers = provider(context);
    const merged = AxiosHeaders.from(config.headers);
    for (const [key, value] of Object.entries(headers)) {
      if (value === undefined) continue;
      if (value === null) {
        merged.delete(key);
        continue;
      }
      merged.set(key, value);
    }
    config.headers = merged;

    return config;
  });

  attachedRequestInterceptorIds.set(instance, id);
}

export function registerRestAxiosInstance(instance: AxiosInstance): void {
  lastRestAxiosInstance = instance;
  knownInstances.add(instance);
  ensureRequestInterceptorAttached(instance);
}

export function getRestAxiosInstance(): AxiosInstance | null {
  return lastRestAxiosInstance;
}

export function installRequestHeaders(
  getHeaders: RequestHeadersProvider,
): RequestHeadersEjectHandle {
  const token: ProviderToken = {};
  activeProviderToken = token;
  activeProvider = getHeaders;

  for (const instance of knownInstances) {
    ensureRequestInterceptorAttached(instance);
  }

  return {
    eject: () => {
      if (activeProviderToken === token) {
        activeProviderToken = null;
        activeProvider = null;
      }
    },
  };
}

export function installBearerAuth(
  getToken: () => string | null | undefined,
  tokenType: string = "Bearer",
): RequestHeadersEjectHandle {
  return installRequestHeaders(() => {
    const token = getToken();
    if (!token) return {};
    return { Authorization: `${tokenType} ${token}` };
  });
}
