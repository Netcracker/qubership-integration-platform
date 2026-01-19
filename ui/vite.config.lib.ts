import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import sassDts from 'vite-plugin-sass-dts';
import dts from "vite-plugin-dts";
import * as path from "node:path";

// https://vite.dev/config/
export default defineConfig({
    plugins: [
        react(),
        sassDts(),
        dts({
            entryRoot: 'src',
            outDir: 'dist-lib/types',
            insertTypesEntry: true,
            rollupTypes: true,
        })
    ],
    build: {
        outDir: "dist-lib",
        emptyOutDir: true,
        minify: true,
        sourcemap: false,
        copyPublicDir: false,
      rollupOptions: {
            input: path.resolve(__dirname, "src/index.ts"),
            external: [
                "react",
                "react-dom",
                "react/jsx-runtime"
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
            }
        },
    },
});
