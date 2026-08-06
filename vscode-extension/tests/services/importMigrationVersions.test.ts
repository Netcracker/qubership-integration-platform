// A missing claim makes runtime-catalog's VersionsGetterService throw outright,
// and the empty array older versions of this extension wrote makes it re-run
// every migration — V101 then wraps `content` a second time and the import
// fails. Both shapes are repaired on read so the next save carries the fix.

import {
  CHAIN_MIGRATIONS,
  MCP_SERVICE_MIGRATIONS,
  repairMigrationsClaim,
  SERVICE_MIGRATIONS,
} from "../../src/web/services/importMigrationVersions";

describe("repairMigrationsClaim", () => {
  it("fills in a missing claim", () => {
    const content: Record<string, unknown> = {};
    repairMigrationsClaim(content, SERVICE_MIGRATIONS);
    expect(content.migrations).toBe(SERVICE_MIGRATIONS);
  });

  // The list mirrors runtime-catalog's registered service migrations, V105 included: a claim
  // holding a version the backend does not know is refused as exported from a newer version.
  it("claims every service migration the backend registers", () => {
    const content: Record<string, unknown> = {};
    repairMigrationsClaim(content, SERVICE_MIGRATIONS);
    expect(content.migrations).toBe("[100, 101, 102, 103, 104, 105]");
  });

  it("replaces the empty array older versions wrote", () => {
    const content: Record<string, unknown> = { migrations: [] };
    repairMigrationsClaim(content, CHAIN_MIGRATIONS);
    expect(content.migrations).toBe(CHAIN_MIGRATIONS);
  });

  // An older claim names a set the backend still has to migrate through.
  it("leaves an existing claim alone", () => {
    const content: Record<string, unknown> = { migrations: "[100, 101]" };
    repairMigrationsClaim(content, SERVICE_MIGRATIONS);
    expect(content.migrations).toBe("[100, 101]");
  });

  it.each([[undefined], [null], ["not an object"], [[]]])(
    "ignores %p instead of throwing",
    (content) => {
      expect(() =>
        repairMigrationsClaim(content, MCP_SERVICE_MIGRATIONS),
      ).not.toThrow();
    },
  );
});
