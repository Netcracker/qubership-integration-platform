/**
 * Jest mock for `@netcracker/qip-schemas`.
 *
 * The real package ships ESM only (`dist/index.mjs`), which Jest's default
 * ts-jest transformer cannot consume without extra ESM plumbing.
 * Most tests either mock `chainElementSchemaModules.ts` directly or never
 * exercise the schemas path, so an empty `schemasByType` is sufficient.
 * Tests that need real schemas should `jest.mock` this stub locally.
 */
module.exports = {
  schemasByType: Object.freeze({}),
};
