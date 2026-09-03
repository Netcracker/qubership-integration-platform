import typescriptEslint from "@typescript-eslint/eslint-plugin";
import tsParser from "@typescript-eslint/parser";

export default [{
    files: ["**/*.ts"],
}, {
    plugins: {
        "@typescript-eslint": typescriptEslint,
    },

    languageOptions: {
        parser: tsParser, ecmaVersion: 2022, sourceType: "module",
    },

    rules: {
        "@typescript-eslint/naming-convention": ["warn", {
            selector: "import", format: ["camelCase", "PascalCase"],
        }],

        curly: "warn", eqeqeq: "warn", "no-throw-literal": "warn", semi: "warn",
        "no-restricted-syntax": [
          "error",
          {
            selector: "MemberExpression[property.name='innerHTML']",
            message: "Using innerHTML is forbidden.",
          }
        ],
    },
}];
