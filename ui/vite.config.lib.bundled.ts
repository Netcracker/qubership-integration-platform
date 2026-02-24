import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import sassDts from "vite-plugin-sass-dts";
import dts from "vite-plugin-dts";
import monacoEditorEsmPlugin from "vite-plugin-monaco-editor-esm";
import * as path from "node:path";

// Library build config for VS Code extensions - React and Monaco bundled (self-contained, no CDN)
export default defineConfig({
  plugins: [
    react(),
    sassDts(),
    monacoEditorEsmPlugin({
      languageWorkers: [],
      customWorkers: [
        {
          label: "editorWorkerService",
          entry: "monaco-editor/esm/vs/editor/editor.worker.js",
        },
        {
          label: "typescript",
          entry: "monaco-editor/esm/vs/language/typescript/ts.worker.js",
        },
        {
          label: "json",
          entry: "monaco-editor/esm/vs/language/json/json.worker.js",
        },
        {
          label: "html",
          entry: "monaco-editor/esm/vs/language/html/html.worker.js",
        },
        {
          label: "css",
          entry: "monaco-editor/esm/vs/language/css/css.worker.js",
        },
      ],
      customDistPath: (root, buildOutDir) =>
        path.join(root, buildOutDir, "assets", "monaco-work"),
    }),
    // Types are produced by build:lib:external (runs first in build:lib:all)
    dts({
      entryRoot: "src",
      outDir: "dist-lib/types",
      insertTypesEntry: true,
      rollupTypes: true,
      skipDiagnostics: true,
    }),
  ],
  build: {
    outDir: "dist-lib",
    emptyOutDir: false, // Don't empty, we need to keep index.es.js from external build
    minify: true,
    sourcemap: false,
    rollupOptions: {
      input: path.resolve(__dirname, "src/index.bundled.ts"),
      external: [], // React and Monaco are bundled
      preserveEntrySignatures: "exports-only",
      output: {
        format: "es",
        entryFileNames: "index.bundled.es.js",
        inlineDynamicImports: true,
        assetFileNames: (assetInfo) => {
          if (assetInfo.name?.endsWith(".css")) {
            return "qip-ui.css";
          }
          return "assets/[name]-[hash][extname]";
        },
      },
    },
  },
});
