/**
 * Entry for bundled build (VSCode webview). Runs Monaco loader config first,
 * then re-exports the library so @monaco-editor/react uses the bundled Monaco.
 * The config is its own module: imports run before this body, and ./index
 * sets a worker base when it runs.
 */
import "./monaco-init-bundled";
export * from "./index";
