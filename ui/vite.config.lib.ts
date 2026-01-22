import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import sassDts from "vite-plugin-sass-dts";
import dts from "vite-plugin-dts";
import * as path from "node:path";

// Library build config - bundles all dependencies to avoid bare imports
export default defineConfig({
  plugins: [
    react(),
    sassDts(),
    dts({
      include: ["src"],
      outDir: "dist-lib/types",
      tsconfigPath: "./tsconfig.json",
    }),
  ],
  define: {
    "process.env.NODE_ENV": JSON.stringify("production"),
    "process.env": JSON.stringify({}),
    global: "globalThis",
  },
  build: {
    outDir: "dist-lib",
    emptyOutDir: true,
    minify: true,
    sourcemap: false,
    copyPublicDir: false,
    lib: {
      entry: path.resolve(__dirname, "src/index.ts"),
      formats: ["es"],
      fileName: () => "index.es.js",
    },
    rollupOptions: {
      output: {
        preserveModules: false,
        inlineDynamicImports: true,
        assetFileNames: (assetInfo) => {
          if (assetInfo.name?.endsWith(".css")) {
            return "qip-ui.css";
          }
          return assetInfo.name || "[name].[ext]";
        },
      },
    },
  },
  server: {
    host: true,
    port: 4200,
    hmr: {
      clientPort: 4200
    }
  },
});
