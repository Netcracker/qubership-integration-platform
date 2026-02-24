/**
 * Public API for configuring @monaco-editor/loader (external build / Vite apps).
 * Use this when consuming the library in a Vite app so Monaco does not load from CDN.
 */
import loader from "@monaco-editor/loader";

export type MonacoLoaderConfig =
  | { monaco: typeof import("monaco-editor") }
  | { paths: { vs: string } };

/**
 * Configure the Monaco loader before any editor is mounted.
 * Call this once at app startup when using the external build (e.g. in new-ui).
 *
 * - Pass { monaco } if you bundled Monaco (e.g. with vite-plugin-monaco-editor-esm).
 * - Pass { paths: { vs: "<same-origin-url>" } } if you serve Monaco assets from your origin.
 *
 * Without this, the loader falls back to CDN (jsDelivr), which can break CSP.
 */
export function configureMonacoLoader(config: MonacoLoaderConfig): void {
  loader.config(config);
}
