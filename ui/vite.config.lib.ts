import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import sassDts from 'vite-plugin-sass-dts';
import dts from "vite-plugin-dts";
import peerDepsExternal from "rollup-plugin-peer-deps-external";

// https://vite.dev/config/
export default defineConfig({
    plugins: [
        react(),
        sassDts(),
        dts({
            entryRoot: 'src',
            outDir: 'dist-lib/types',
            insertTypesEntry: true
        }),
        {
            ...peerDepsExternal(),
            enforce: "pre",
        },
    ],
    build: {
        outDir: 'dist-lib',
        emptyOutDir: true,
        lib: {
            entry: 'src/index.ts',
            name: 'QIP-UI',
            formats: ['es', 'cjs'],
            fileName: (format) => `index.${format}.js`,
        },
    },
});
