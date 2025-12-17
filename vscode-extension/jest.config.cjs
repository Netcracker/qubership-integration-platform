/** @type {import('jest').Config} */
module.exports = {
  testEnvironment: "node",
  clearMocks: true,

  testMatch: ["<rootDir>/src/**/*.test.ts"],

  transform: {
    "^.+\\.ts$": ["ts-jest", { tsconfig: "tsconfig.json" }],
  },

  collectCoverage: true,
  collectCoverageFrom: [
    "src/**/*.ts",
    "!src/**/*.d.ts",
    "!src/**/*.test.ts",
  ],
  coverageDirectory: "coverage",
  coverageReporters: ["text", "lcov", "html"],
};