import { IconOverrides } from "./icons/IconProvider";
import type { ThemeConfig } from "antd";

export type AppConfig = {
  apiGateway?: string;
  appName?: string;
  icons?: IconOverrides;
  cssVariables?: Record<string, string>;
  additionalCss?: string[];
  themeOverrides?: Partial<ThemeConfig>;
  dev?: boolean;
};

const appConfigValue: AppConfig = {
  appName: import.meta.env.VITE_API_APP,
  apiGateway: import.meta.env.VITE_GATEWAY,
  icons: {},
  dev: import.meta.env.DEV,
};

function setAppName(name: string | undefined | null): void {
  if (typeof name === "string" && name.length > 0) {
    appConfigValue.appName = name;
  }
}

export function setIcons(icons?: IconOverrides) {
  if (icons) {
    appConfigValue.icons = icons;
  }
}

export function getAppName(): string {
  return appConfigValue.appName || import.meta.env.VITE_API_APP || "";
}

export function getIcons(): IconOverrides {
  return appConfigValue.icons || {};
}

export function getConfig(): AppConfig {
  return { ...appConfigValue };
}

export function isDev(): boolean {
  return appConfigValue.dev ?? import.meta.env.DEV;
}

export function configure(config: Partial<AppConfig>): void {
  const overrides: string[] = [];

  if (config.appName !== undefined) {
    const oldValue = appConfigValue.appName;
    setAppName(config.appName);
    overrides.push(`appName: "${oldValue}" -> "${config.appName}"`);
  }
  if (config.apiGateway !== undefined) {
    const oldValue = appConfigValue.apiGateway;
    appConfigValue.apiGateway = config.apiGateway;
    overrides.push(`apiGateway: "${oldValue}" -> "${config.apiGateway}"`);
  }
  if (config.icons !== undefined) {
    const iconKeys = Object.keys(config.icons);
    setIcons(config.icons);
    overrides.push(`icons: ${iconKeys.length} icon(s) overridden`);
  }
  if (config.cssVariables !== undefined) {
    const cssVarKeys = Object.keys(config.cssVariables);
    appConfigValue.cssVariables = config.cssVariables;
    overrides.push(`cssVariables: ${cssVarKeys.length} variable(s) overridden`);
  }
  if (config.additionalCss !== undefined) {
    appConfigValue.additionalCss = config.additionalCss;
    overrides.push(
      `additionalCss: ${config.additionalCss.length} file(s) added`,
    );
  }
  if (config.themeOverrides !== undefined) {
    appConfigValue.themeOverrides = config.themeOverrides;
    overrides.push(`themeOverrides: theme configuration overridden`);
  }
  if (config.dev !== undefined) {
    const oldValue = appConfigValue.dev;
    appConfigValue.dev = config.dev;
    overrides.push(`dev: ${oldValue} -> ${config.dev}`);
  }

  if (overrides.length > 0) {
    console.log("[QIP UI Config] Configuration overrides applied:", overrides);
  }
}

export async function loadConfigFromJson(url: string): Promise<AppConfig> {
  try {
    const response = await fetch(url);
    if (!response.ok) {
      throw new Error(`Failed to load config: ${response.statusText}`);
    }
    const config = (await response.json()) as AppConfig;
    return config;
  } catch (error) {
    console.error("Error loading config from JSON:", error);
    throw error;
  }
}

export function loadConfigFromEnv(): Partial<AppConfig> {
  const config: Partial<AppConfig> = {};

  const apiGateway =
    (import.meta.env.VITE_API_GATEWAY as string | undefined) ||
    (import.meta.env.VITE_GATEWAY as string | undefined);
  if (apiGateway) {
    config.apiGateway = apiGateway;
  }

  const appName =
    (import.meta.env.VITE_APP_NAME as string | undefined) ||
    (import.meta.env.VITE_API_APP as string | undefined);
  if (appName) {
    config.appName = appName;
  }

  const cssVars = import.meta.env.VITE_CSS_VARIABLES as string | undefined;
  if (cssVars) {
    try {
      const parsed = JSON.parse(cssVars) as unknown;
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
        config.cssVariables = parsed as Record<string, string>;
      }
    } catch (error) {
      console.warn(
        "[QIP UI Config] Failed to parse VITE_CSS_VARIABLES:",
        error,
      );
    }
  }

  const additionalCss = import.meta.env.VITE_ADDITIONAL_CSS as
    | string
    | undefined;
  if (additionalCss) {
    try {
      const parsed = JSON.parse(additionalCss) as unknown;
      if (Array.isArray(parsed)) {
        config.additionalCss = parsed as string[];
      }
    } catch (error) {
      console.warn(
        "[QIP UI Config] Failed to parse VITE_ADDITIONAL_CSS:",
        error,
      );
    }
  }

  return config;
}

export type AppExtensionProps = {
  appName?: string;
  icons?: IconOverrides;
  apiGateway?: string;
  cssVariables?: Record<string, string>;
  additionalCss?: string[];
  themeOverrides?: Partial<ThemeConfig>;
  dev?: boolean;
};

export async function configureAppExtension(
  message: AppExtensionProps,
): Promise<void> {
  configure(message);

  if (message.cssVariables) {
    const { injectCssVariables } = await import("./config/cssInjector");
    injectCssVariables(message.cssVariables);
  }

  if (message.additionalCss && message.additionalCss.length > 0) {
    const { loadCssFiles } = await import("./config/cssInjector");
    await loadCssFiles(message.additionalCss).catch((error) => {
      console.warn("Some CSS files failed to load:", error);
    });
  }

  if (message.apiGateway) {
    const { api } = await import("./api/api");
    const { RestApi } = await import("./api/rest/restApi");
    if (api instanceof RestApi) {
      api.reconfigure(message.apiGateway);
    }
  }

  console.info("Initial extension configuration succeeded");
}

export function mergeConfigWithEnv(
  overrideConfig: Partial<AppConfig>,
): Partial<AppConfig> {
  const envConfig = loadConfigFromEnv();

  return {
    ...envConfig,
    ...overrideConfig,
    cssVariables:
      envConfig.cssVariables || overrideConfig.cssVariables
        ? {
            ...(envConfig.cssVariables || {}),
            ...(overrideConfig.cssVariables || {}),
          }
        : undefined,
    additionalCss:
      envConfig.additionalCss || overrideConfig.additionalCss
        ? [
            ...(envConfig.additionalCss || []),
            ...(overrideConfig.additionalCss || []),
          ]
        : undefined,
    themeOverrides:
      envConfig.themeOverrides || overrideConfig.themeOverrides
        ? {
            ...(envConfig.themeOverrides || {}),
            ...(overrideConfig.themeOverrides || {}),
          }
        : undefined,
  };
}
