import { getConfig } from "../appConfig";
import { injectCssVariables, loadCssFiles } from "./cssInjector";
import { api } from "../api/api";
import { RestApi } from "../api/rest/restApi";

export async function initializeConfiguration(): Promise<void> {
  const config = getConfig();
  console.log("[QIP UI Config] initializeConfiguration called, config:", {
    apiGateway: config.apiGateway,
    appName: config.appName,
    isRestApi: api instanceof RestApi,
  });

  if (api instanceof RestApi) {
    const oldBaseURL = api.instance.defaults.baseURL;
    console.log(
      `[QIP UI Config] Current baseURL: "${oldBaseURL}", config.apiGateway: "${config.apiGateway}"`,
    );

    if (config.apiGateway) {
      api.reconfigure(config.apiGateway);
      console.log(
        `[QIP UI Config] API gateway reconfigured from "${oldBaseURL}" to "${config.apiGateway}"`,
      );
      console.log(
        `[QIP UI Config] Verifying new baseURL: ${api.instance.defaults.baseURL}`,
      );
    } else {
      console.warn(
        `[QIP UI Config] config.apiGateway is not set, keeping old baseURL: "${oldBaseURL}"`,
      );
    }
  } else {
    console.log(
      "[QIP UI Config] Not RestApi instance, skipping gateway reconfiguration",
    );
  }

  if (config.cssVariables) {
    const varCount = Object.keys(config.cssVariables).length;
    injectCssVariables(config.cssVariables);
    console.log(`[QIP UI Config] Injected ${varCount} CSS variable(s)`);
  }

  if (config.additionalCss && config.additionalCss.length > 0) {
    await loadCssFiles(config.additionalCss)
      .then(() => {
        console.log(
          `[QIP UI Config] Loaded ${config.additionalCss!.length} additional CSS file(s)`,
        );
      })
      .catch((error) => {
        console.warn("[QIP UI Config] Some CSS files failed to load:", error);
      });
  }
}

export function reapplyCssVariables(): void {
  const config = getConfig();
  if (config.cssVariables) {
    const varCount = Object.keys(config.cssVariables).length;
    injectCssVariables(config.cssVariables);
    console.log(`[QIP UI Config] Reapplied ${varCount} CSS variable(s)`);
  }
}
