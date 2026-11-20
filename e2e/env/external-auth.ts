import { AsyncLocalStorage } from "node:async_hooks";
import { pathToFileURL } from "node:url";
import path from "node:path";
import type { Browser, BrowserContextOptions, Page } from "@playwright/test";
import { externalSettings } from "./external-settings.ts";
import type { ServiceRole } from "./index.ts";

export interface ApiAuthContext {
  service: ServiceRole;
  run: string;
  baseURL: string;
  parallelIndex: number | undefined;
  phase: "test" | "setup" | "cleanup";
}

export interface ApiCredentials {
  headers: Record<string, string>;
  expiresAt?: number;
}

type BrowserState = Exclude<BrowserContextOptions["storageState"], string | undefined>;
type LoginResult = { expiresAt?: number } | void;

export interface UiAuthContext {
  run: string;
  baseURL: string;
  parallelIndex: number;
}

export interface ExternalAuth {
  api?: {
    headers?: Partial<Record<ServiceRole, Record<string, string>>>;
    getHeaders?(context: ApiAuthContext): ApiCredentials | Promise<ApiCredentials>;
  };
  ui?: { storageState: BrowserState | string } | {
    login(context: UiAuthContext & { page: Page }): LoginResult | Promise<LoginResult>;
  };
}

const phase = new AsyncLocalStorage<ApiAuthContext["phase"]>();
const providers = new Map<string, Promise<ExternalAuth>>();

export function inAuthPhase<T>(value: ApiAuthContext["phase"], action: () => Promise<T>): Promise<T> {
  return phase.run(value, action);
}

function provider(file: string): Promise<ExternalAuth> {
  let pending = providers.get(file);
  if (!pending) {
    pending = import(pathToFileURL(file).href).then((mod) => {
      if (!mod.default || typeof mod.default !== "object") throw new Error("Missing default export.");
      return mod.default as ExternalAuth;
    });
    providers.set(file, pending);
  }
  return pending;
}

const API_EXPIRY_MARGIN_MS = 5000;

export function createHeaderCache(now: () => number = Date.now) {
  const cached = new Map<string, ApiCredentials>();
  const refreshing = new Map<string, Promise<ApiCredentials>>();

  return async (key: string, refresh: () => Promise<ApiCredentials>): Promise<Record<string, string>> => {
    const previous = cached.get(key);
    if (previous?.expiresAt !== undefined && previous.expiresAt - now() > API_EXPIRY_MARGIN_MS) {
      return previous.headers;
    }

    let pending = refreshing.get(key);
    if (!pending) {
      pending = refresh().then((value) => {
        if (value.expiresAt !== undefined && (!Number.isFinite(value.expiresAt) || value.expiresAt <= now())) {
          throw new Error("The provider returned expired credentials.");
        }
        const credentials = { ...value, headers: Object.fromEntries(new Headers(value.headers)) };
        cached.set(key, credentials);
        return credentials;
      }).finally(() => refreshing.delete(key));
      refreshing.set(key, pending);
    }
    return (await pending).headers;
  };
}

const headersFromCache = createHeaderCache();

export async function externalHeaders(file: string, service: ServiceRole, baseURL: string): Promise<Record<string, string>> {
  const stage = phase.getStore() ?? "test";
  const run = externalSettings()!.run;
  const index = process.env.TEST_PARALLEL_INDEX;
  const parallelIndex = index === undefined ? undefined : Number(index);
  try {
    const { api } = await provider(file);
    const headers = new Headers(api?.headers?.[service]);
    if (api?.getHeaders) {
      const key = JSON.stringify([file, run, service, baseURL, parallelIndex, stage]);
      const dynamic = await headersFromCache(key, async () => api.getHeaders!({
        service, baseURL, run, parallelIndex, phase: stage,
      }));
      new Headers(dynamic).forEach((value, name) => headers.set(name, value));
    }
    return Object.fromEntries(headers);
  } catch {
    throw new Error(`API authorization failed for ${service} during ${stage}. Check the auth module and credentials.`);
  }
}

interface BrowserSession {
  state: BrowserState | string;
  expiresAt?: number;
}

export function createUiSession(now: () => number = Date.now) {
  let session: BrowserSession | undefined;
  return async (login: () => Promise<BrowserSession | undefined>) => {
    if (session && (session.expiresAt === undefined || session.expiresAt - now() > API_EXPIRY_MARGIN_MS)) {
      return session.state;
    }
    session = await login();
    return session?.state;
  };
}

export async function uiLogin(
  file: string,
  browser: Browser,
  context: UiAuthContext,
): Promise<BrowserSession | undefined> {
  try {
    const { ui } = await provider(file);
    if (!ui) return;
    if ("storageState" in ui) {
      if (typeof ui.storageState === "string") {
        return { state: path.resolve(path.dirname(file), ui.storageState) };
      }
      return { state: ui.storageState };
    }
    const isolated = await browser.newContext({ baseURL: context.baseURL });
    try {
      const result = await ui.login({ ...context, page: await isolated.newPage() });
      const expiresAt = result?.expiresAt;
      if (expiresAt !== undefined && (!Number.isFinite(expiresAt) || expiresAt <= Date.now())) {
        throw new Error("The login returned an expired browser session.");
      }
      return { state: await isolated.storageState({ indexedDB: true }), expiresAt };
    } finally {
      await isolated.close();
    }
  } catch {
    throw new Error("UI authorization failed during login. Check the auth module and credentials.");
  }
}

export function assertExternalAuthorized(status: number, service: ServiceRole): void {
  if (externalSettings() && (status === 401 || status === 403)) {
    throw new Error(`API authorization failed for ${service} during ${phase.getStore() ?? "test"} (HTTP ${status}).`);
  }
}
