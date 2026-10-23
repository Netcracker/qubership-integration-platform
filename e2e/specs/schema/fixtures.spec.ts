/**
 * The tracked fixtures against the schema sources they are written for.
 *
 * No stack. A fixture that does not validate breaks the seed project minutes into a run, and this
 * spec catches it in under a second. Every tracked directory, not just `fixtures/chains/`: the seed
 * imports `fixtures/script/` and `fixtures/services/` too, so a fixture there costs the same run if
 * it is wrong. The generated `fixtures/axes/` is left out because it is absent in a fresh clone, and
 * the generator validates what it writes. `specs/harness/fixtures.spec.ts` covers the templating
 * and the archive layout.
 */
import fs from "node:fs";
import path from "node:path";
import { test, expect } from "@playwright/test";
import yaml from "js-yaml";
import { QIP_MODEL_DIR } from "../../registry/discriminators.js";
import { TRACKED_FIXTURE_DIRS, chainFixtureNames, readFixtureDocument, renderFixture } from "../../fixtures/templating.js";
import { EXPORTED_CHAIN_SCHEMA, schemaValidator, validateDocument } from "../../fixtures/validate.js";
import { EXAMPLE_RUN_TOKEN } from "../../support/run.js";

const RUN = EXAMPLE_RUN_TOKEN;

/** Every tracked fixture with the directory it came from. */
const fixtures = TRACKED_FIXTURE_DIRS.flatMap((dir) =>
  chainFixtureNames(dir).map((name) => ({ dir, name })),
);

test.describe("tracked fixtures against the schemas", { tag: ["@schema"] }, () => {
  for (const { dir, name } of fixtures) {
    test(`${name} validates against the tracked schema source`, () => {
      const tree = renderFixture(dir, name, RUN);
      const fixture = readFixtureDocument(name, tree);
      expect(validateDocument(fixture.document)).toEqual([]);
      // A service's specification group and specifications travel beside it, each with a schema of its own.
      for (const [rel, source] of fixture.companions) {
        if (!rel.endsWith(".cip.yaml")) continue;
        expect(validateDocument(yaml.load(source)), rel).toEqual([]);
      }
    });
  }

  test("the validator resolves the chain schema by its $id", () => {
    // Validation looks a document's `$schema` up as a schema `$id`, with no rewriting in between.
    const chain = yaml.load(fs.readFileSync(path.join(QIP_MODEL_DIR, "chain.schema.yaml"), "utf-8")) as { $id?: unknown };
    expect(chain.$id).toBe(EXPORTED_CHAIN_SCHEMA);
    expect(schemaValidator().getSchema(EXPORTED_CHAIN_SCHEMA)).toBeDefined();
  });
});
