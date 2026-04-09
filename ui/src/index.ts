import "./lunr-init";
import "./vscode-entry";
import { initExternalMonaco } from "./monaco-init-runtime";

initExternalMonaco();

export type * from "./api/apiTypes";
export type {
  VSCodeMessage,
  VSCodeResponse,
} from "./api/rest/vscodeExtensionApi";
export type { AppExtensionProps, AppConfig } from "./appConfig";
export type {
  IconOverrides,
  IconName,
  IconSource,
} from "./icons/IconProvider.tsx";
export { default as App } from "./App";
export { default as AppExtension } from "./AppExtension";
export type {
  RequestHeaders,
  RequestHeadersContext,
  RequestHeadersProvider,
  RequestHeadersEjectHandle,
} from "./api/rest/requestHeadersInterceptor";
export {
  installRequestHeaders,
  installBearerAuth,
  getHeadersForContext,
  getRestAxiosInstance,
} from "./api/rest/requestHeadersInterceptor";
export {
  configureAppExtension,
  loadConfigFromEnv,
  loadConfigFromJson,
  configure,
  getConfig,
  onConfigChange,
  mergeConfigWithEnv,
  isDev,
} from "./appConfig";
export { isVsCode } from "./api/rest/vscodeExtensionApi";
export {
  DocumentationService,
  documentationService,
} from "./services/documentation/documentationService";
export {
  setMonacoWorkerBasePath,
  getMonacoWorkerBasePath,
} from "./monaco-worker-config";
export { configureMonacoLoader } from "./monaco-loader-config";
export type { MonacoLoaderConfig } from "./monaco-loader-config";
