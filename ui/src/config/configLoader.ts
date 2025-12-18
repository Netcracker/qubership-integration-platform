import { AppConfig, loadConfigFromJson, configure, loadConfigFromEnv } from "../appConfig";

declare global {
  interface Window {
    QIP_UI_CONFIG?: Partial<AppConfig>;
  }
}

const CONFIG_FILE_PATHS = ["./config.json", "./qip-ui-config.json"];

export async function loadConfig(): Promise<AppConfig> {
  const envConfig = loadConfigFromEnv();
  let config: Partial<AppConfig> = { ...envConfig };
  let loadedFromFile = false;
  const hasEnvConfig = Object.keys(envConfig).length > 0;

  for (const path of CONFIG_FILE_PATHS) {
    try {
      const loadedConfig = await loadConfigFromJson(path);
      config = { ...config, ...loadedConfig };
      loadedFromFile = true;
      console.log(`[QIP UI Config] Configuration loaded from: ${path}`);
      break;
    } catch {
      continue;
    }
  }

  if (window.QIP_UI_CONFIG) {
    config = { ...config, ...window.QIP_UI_CONFIG };
    console.log("[QIP UI Config] Configuration merged from window.QIP_UI_CONFIG");
  }

  if (hasEnvConfig) {
    console.log("[QIP UI Config] Configuration loaded from environment variables");
  }

  if (!loadedFromFile && !window.QIP_UI_CONFIG && !hasEnvConfig) {
    console.log("[QIP UI Config] No external configuration found, using defaults");
  }

  return config as AppConfig;
}

export async function initializeConfig(): Promise<void> {
  try {
    const config = await loadConfig();
    console.log("[QIP UI Config] loadConfig returned:", config);
    configure(config);
    console.log("[QIP UI Config] configure() called with config:", config);
  } catch (error) {
    console.warn("Failed to load external config, using defaults:", error);
  }
}

