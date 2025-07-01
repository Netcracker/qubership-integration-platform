import globals from "globals";
import pluginJs from "@eslint/js";
import tseslint from "typescript-eslint";
import pluginReact from "eslint-plugin-react";
import * as reactHooks from "eslint-plugin-react-hooks";

/** @type {import("eslint").Linter.Config[]} */
export default [
  { ignores: ["dist/*", "node_modules/*"] },
  pluginJs.configs.recommended,
  ...tseslint.configs.recommendedTypeChecked,
  pluginReact.configs.flat.recommended,
  reactHooks.configs["recommended-latest"],
  {
    files: ["**/*.{js,jsx,mjs,cjs,ts,tsx}"],
    languageOptions: {
      globals: {
        ...globals.browser,
      },
      parserOptions: {
        projectService: {
          allowDefaultProject: ["eslint.config.js"],
          defaultProject: "./tsconfig.json",
        },
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      "react/react-in-jsx-scope": "off",
    },
    settings: {
      react: {
        version: "detect",
      },
    },
  },
];
