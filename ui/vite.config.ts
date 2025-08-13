import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import sassDts from 'vite-plugin-sass-dts'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), sassDts()],
  build: {
    minify: true,
    sourcemap: false,
    rollupOptions: {
      output: {
        inlineDynamicImports: true,
      },
    },
  },
  server: {
    host: true,
    port: 4200,
  },
});
