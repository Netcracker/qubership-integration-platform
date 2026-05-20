import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import sassDts from "vite-plugin-sass-dts";
import dts from "vite-plugin-dts";
import * as path from "node:path";

// Library build config for web applications - React is external (smaller bundle)
// This is the default version published to npm for use in Vite/Webpack projects
export default defineConfig({
  plugins: [
    react(),
    sassDts(),
    dts({
      entryRoot: "src",
      outDir: "dist-lib/types",
      insertTypesEntry: true,
      rollupTypes: true,
    }),
  ],
  build: {
    outDir: "dist-lib",
    emptyOutDir: true,
    minify: true,
    sourcemap: false,
    rollupOptions: {
      input: path.resolve(__dirname, "src/index.ts"),
      external: [
        "react",
        "react-dom",
        "react/jsx-runtime",
        "monaco-editor",
        "@monaco-editor/loader",
      ],
      preserveEntrySignatures: "exports-only",
      output: {
        format: "es",
        entryFileNames: "index.es.js",
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
