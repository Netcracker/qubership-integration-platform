import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import sassDts from "vite-plugin-sass-dts";
import dts from "vite-plugin-dts";
import * as path from "node:path";

// Library build config for VS Code extensions - React is bundled (self-contained)
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
    emptyOutDir: false, // Don't empty, we need to keep index.es.js from external build
    minify: true,
    sourcemap: false,
    rollupOptions: {
      input: path.resolve(__dirname, "src/index.ts"),
      external: [], // React is bundled
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
