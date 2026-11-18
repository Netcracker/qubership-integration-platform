import type { TestType, APIRequest } from "@playwright/test";
import type { TargetSetup } from "./target-setup.js";
import type { ServiceRole } from "./index.js";

export type RequestOptions = NonNullable<Parameters<APIRequest["newContext"]>[0]>;
export type PlatformTest = typeof import("../support/fixtures.js").test;
export type VsCodeTest = typeof import("../support/vscode-guard.js").test;

export interface SuiteExtension {
  target?: (base: TargetSetup) => TargetSetup;
  extendTest?: (base: PlatformTest) => PlatformTest;
  extendVsCodeTest?: (base: VsCodeTest) => VsCodeTest;
  kubernetes?: {
    kubectl?: (args: string[]) => Promise<string>;
    helm?: (args: string[]) => Promise<string>;
    deployment?: (role: ServiceRole) => string;
    container?: (role: ServiceRole) => string;
    microService?: (domain: string) => string;
  };
  lifecycle?: {
    schemas?: () => Promise<boolean>;
    startUi?: () => Promise<{ pid: number | null; built: boolean }>;
    stopUi?: () => Promise<void>;
    startVsCode?: () => Promise<{ pid: number; built: string[] }>;
    stopVsCode?: () => Promise<void>;
  };
}

let extension: SuiteExtension = {};

export function configureSuite(value: SuiteExtension): void {
  extension = value;
}

export function suiteExtension(): SuiteExtension {
  return extension;
}

export function extendSuiteTest<T extends TestType<any, any>>(base: T, kind: "platform" | "vscode" = "platform"): T {
  const extend = kind === "vscode" ? extension.extendVsCodeTest : extension.extendTest;
  return ((extend as ((value: T) => T) | undefined)?.(base) ?? base);
}

export function apiOptions(role?: ServiceRole): RequestOptions {
  const options = JSON.parse(process.env.QIP_E2E_API_OPTIONS ?? "{}") as {
    common?: RequestOptions;
    services?: Partial<Record<ServiceRole, RequestOptions>>;
  };
  const common = options.common ?? {};
  const service = role ? options.services?.[role] ?? {} : {};
  return { ...common, ...service, extraHTTPHeaders: { ...common.extraHTTPHeaders, ...service.extraHTTPHeaders } };
}
