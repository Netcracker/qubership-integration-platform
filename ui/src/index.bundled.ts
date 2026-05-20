/**
 * Entry for bundled build (VSCode webview). Runs Monaco loader config first,
 * then re-exports the library so @monaco-editor/react uses the bundled Monaco.
 */
import { initBundledMonaco } from "./monaco-init-runtime";

initBundledMonaco();
export * from "./index";
