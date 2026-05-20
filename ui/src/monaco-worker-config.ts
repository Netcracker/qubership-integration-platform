/**
 * Runtime configuration for Monaco editor workers (bundled build).
 * Call setMonacoWorkerBasePath() with the base URL of the directory containing
 * worker bundles (e.g. dist-lib/assets/monaco-work) so workers load from same-origin.
 */

const WORKER_LABEL_TO_FILENAME: Record<string, string> = {
  editorWorkerService: "editor.worker..bundle.js",
  typescript: "ts.worker..bundle.js",
  javascript: "ts.worker..bundle.js",
  json: "json.worker..bundle.js",
  html: "html.worker..bundle.js",
  handlebars: "html.worker..bundle.js",
  razor: "html.worker..bundle.js",
  css: "css.worker..bundle.js",
  scss: "css.worker..bundle.js",
  less: "css.worker..bundle.js",
};

let workerBaseUrl: string | null = null;

type MonacoEnv = { getWorkerUrl?: (moduleId: string, label: string) => string };
declare global {
  interface Window {
    MonacoEnvironment?: MonacoEnv;
  }
}

function installMonacoEnvironment(): void {
  if (typeof window === "undefined" || workerBaseUrl === null) return;
  if (window.MonacoEnvironment?.getWorkerUrl) return;
  const base = workerBaseUrl;
  window.MonacoEnvironment = {
    getWorkerUrl(_moduleId: string, label: string): string {
      const filename =
        WORKER_LABEL_TO_FILENAME[label] ??
        WORKER_LABEL_TO_FILENAME.editorWorkerService;
      return `${base}/${filename}`;
    },
  };
}

/**
 * Set the base URL for Monaco editor workers (no trailing slash).
 * Must be called before any Monaco editor is created, e.g. from the host (VSCode webview)
 * before loading the main bundle.
 *
 * @param baseUrl - Full URL to the directory containing worker .bundle.js files
 */
export function setMonacoWorkerBasePath(baseUrl: string): void {
  workerBaseUrl = baseUrl.replace(/\/$/, "");
  installMonacoEnvironment();
}

/**
 * Returns the current worker base URL if set.
 */
export function getMonacoWorkerBasePath(): string | null {
  return workerBaseUrl;
}
