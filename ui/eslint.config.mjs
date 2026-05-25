import globals from "globals";
import pluginJs from "@eslint/js";
import tseslint from "typescript-eslint";
import pluginReact from "eslint-plugin-react";
import pluginJest from "eslint-plugin-jest";
import * as reactHooks from "eslint-plugin-react-hooks";

/** @type {import("eslint").Linter.Config[]} */
export default [
  {
    ignores: ["dist/*", "dist-lib/*", "coverage/*", "node_modules/*"],
  },
  pluginJs.configs.recommended,
  ...tseslint.configs.recommendedTypeChecked,
  pluginReact.configs.flat.recommended,
  reactHooks.configs["recommended-latest"],
  {
    files: ["**/*.{js,jsx,mjs,cjs,ts,tsx,mts,cts}"],
    languageOptions: {
      globals: {
        ...globals.browser,
      },
      parserOptions: {
        projectService: {
          allowDefaultProject: [
            "eslint.config.js",
            "eslint.config.mjs",
            "scripts/*.mjs",
            "tests/__mocks__/*.cjs",
            "tests/__mocks__/*.js",
          ],
          defaultProject: "./tsconfig.json",
        },
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      "react/react-in-jsx-scope": "off",
      "@typescript-eslint/no-unused-vars": [
        "error",
        {
          argsIgnorePattern: "^_",
          varsIgnorePattern: "^_",
          caughtErrorsIgnorePattern: "^_",
          destructuredArrayIgnorePattern: "^_",
          ignoreRestSiblings: true,
        },
      ],
    },
    settings: {
      react: {
        version: "detect",
      },
    },
  },
  {
    // Plain JS scripts and CommonJS mocks — disable type-checked rules
    // (they require TS type info that's not available for non-TS files).
    files: ["scripts/**/*.mjs", "tests/__mocks__/**/*.{js,cjs}"],
    ...tseslint.configs.disableTypeChecked,
  },
  {
    files: ["scripts/**/*.mjs", "tests/__mocks__/**/*.{js,cjs}"],
    languageOptions: {
      globals: { ...globals.node },
      sourceType: "module",
    },
    rules: {
      "@typescript-eslint/no-require-imports": "off",
      "@typescript-eslint/no-unused-vars": [
        "error",
        {
          argsIgnorePattern: "^_",
          varsIgnorePattern: "^_",
          caughtErrorsIgnorePattern: "^_",
          destructuredArrayIgnorePattern: "^_",
          ignoreRestSiblings: true,
        },
      ],
    },
  },
  {
    // update this to match your test files
    files: ["**/*.spec.js", "**/*.test.js"],
    plugins: { jest: pluginJest },
    languageOptions: {
      globals: pluginJest.environments.globals.globals,
    },
    rules: {
      "jest/valid-expect": [
        "error",
        {
          maxArgs: 2,
        },
      ],
      "jest/no-disabled-tests": "warn",
      "jest/no-focused-tests": "error",
      "jest/no-identical-title": "error",
      "jest/prefer-to-have-length": "warn",
    },
  },
  {
    // Test files: relax type-driven rules that are noisy with jest mocks
    // (jest.fn() returns Mock<UnknownFunction>, .mock.calls items are unknown,
    // jest.requireActual returns unknown, etc.). The actual code under test
    // is still strictly typed via tsc.
    files: [
      "tests/**/*.{ts,tsx}",
      "**/*.{test,spec}.{ts,tsx}",
      "**/__tests__/**/*.{ts,tsx}",
    ],
    rules: {
      "@typescript-eslint/no-unsafe-assignment": "off",
      "@typescript-eslint/no-unsafe-member-access": "off",
      "@typescript-eslint/no-unsafe-call": "off",
      "@typescript-eslint/no-unsafe-return": "off",
      "@typescript-eslint/no-unsafe-argument": "off",
      "@typescript-eslint/no-explicit-any": "off",
      "@typescript-eslint/require-await": "off",
      "@typescript-eslint/unbound-method": "off",
      "@typescript-eslint/no-base-to-string": "off",
      "@typescript-eslint/no-require-imports": "off",
      "@typescript-eslint/no-misused-promises": "off",
      "@typescript-eslint/prefer-promise-reject-errors": "off",
      "react/display-name": "off",
      "react/prop-types": "off",
    },
  },
];
