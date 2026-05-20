/** @type {import('jest').Config} */
module.exports = {
    testEnvironment: "node",
    clearMocks: true,

    testMatch: [
        "<rootDir>/src/web/api-services/**/*.test.ts",
        "<rootDir>/src/web/api-services/**/*.test.tsx",
        "<rootDir>/tests/**/*.test.ts",
        "<rootDir>/tests/**/*.test.tsx",
    ],

    testPathIgnorePatterns: [
        "<rootDir>/dist/",
        "<rootDir>/node_modules/",
        "<rootDir>/src/web/test/",
    ],

    transform: {
        "^.+\\.(ts|tsx)$": [
            "ts-jest",
            {
                tsconfig: "<rootDir>/tsconfig.json",
                diagnostics: { ignoreCodes: [151002, 1192, 7006] },
            },
        ],
    },

    collectCoverage: true,
    collectCoverageFrom: [
        "<rootDir>/src/web/api-services/**/*.{ts,tsx}",
        "<rootDir>/src/web/response/**/*.{ts,tsx}",
        "<rootDir>/src/web/extension.ts",
        "!<rootDir>/src/web/api-services/**/*.d.ts",
        "!<rootDir>/src/web/api-services/**/*.{test,spec}.{ts,tsx}",
        "!<rootDir>/src/web/response/**/*.d.ts",
        "!<rootDir>/src/web/response/**/*.{test,spec}.{ts,tsx}",
    ],
    coverageDirectory: "coverage",
    coverageReporters: ["text", "lcov", "html"],
};
