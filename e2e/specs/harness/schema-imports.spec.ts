/**
 * What the `schema` and `harness` projects are allowed to import, read off their directories.
 *
 * Their whole value is that they need no stack: `--project=schema --project=harness` runs on a
 * machine with no Docker. `support/fixtures.js` is what would take that
 * away, because its `test` installs `auto: true` fixtures — one creates a per-worker folder over
 * HTTP, another shells out to `docker logs` — so a single spec taking `test` or `expect` from
 * there turns the gate red on a machine that never had a stack to be wrong about.
 *
 * Nothing else says so. Every sibling gets it right today and each was written by reading the
 * others, which is the arrangement that holds until it doesn't. Importing a *helper* from
 * `support/` stays fine and several siblings do: `test` and `expect` are what carry the fixtures.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { test, expect } from "@playwright/test";

const SPECS = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");

/** The spec directories of the projects that need no stack. */
const STACKLESS_DIRS = ["schema", "harness"];

/** The only module a stackless spec may take `test` or `expect` from. */
const ALLOWED = "@playwright/test";

/** Every static import in a source file, as the clause it binds and the module it names. */
const IMPORT = /import\s+(type\s+)?([\s\S]*?)\s+from\s+["']([^"']+)["']/g;

/**
 * The names an import clause binds, by the name the module exports rather than the local alias.
 *
 * `test as base` is the form `support/fixtures.ts` itself uses, so reading the local side would
 * miss the one spelling most likely to be copied out of it.
 */
function importedNames(clause: string): string[] {
  const braces = /\{([\s\S]*)\}/.exec(clause);
  const named = braces
    ? braces[1]
        .split(",")
        .map((each) => each.trim().replace(/^type\s+/, "").split(/\s+as\s+/)[0].trim())
        .filter((each) => each.length > 0)
    : [];
  // A namespace import reaches every export including `test`, so it counts as binding both.
  return /\*\s+as\s+/.test(clause) ? [...named, "test", "expect"] : named;
}

/** One offending line, in the words a reader can act on without opening the file. */
interface Offense {
  spec: string;
  name: string;
  from: string;
}

function offensesIn(spec: string): Offense[] {
  const source = fs.readFileSync(path.join(SPECS, spec), "utf-8");
  const found: Offense[] = [];
  for (const [, , clause, from] of source.matchAll(IMPORT)) {
    if (from === ALLOWED) continue;
    for (const name of importedNames(clause)) {
      if (name === "test" || name === "expect") found.push({ spec, name, from });
    }
  }
  return found;
}

/** Every spec file of the stackless projects, relative to `specs/`. */
const specs = STACKLESS_DIRS.flatMap((dir) =>
  fs.readdirSync(path.join(SPECS, dir)).filter((name) => name.endsWith(".spec.ts")).map((name) => `${dir}/${name}`),
);

test.describe("stackless imports", { tag: ["@harness"] }, () => {
  test("stackless specs take test and expect from Playwright only", () => {
    // A scan over an empty directory passes for the worst possible reason.
    for (const dir of STACKLESS_DIRS) expect(specs.filter((spec) => spec.startsWith(`${dir}/`)).length, dir).toBeGreaterThan(0);

    const offenses = specs.flatMap(offensesIn);
    expect(
      offenses.map((each) => `${each.spec} imports ${each.name} from ${each.from}`),
      `a specs/schema/ or specs/harness/ spec may take test and expect from ${ALLOWED} only: ` +
        `any other module can install fixtures, and support/fixtures.js installs ones that need a running stack`,
    ).toEqual([]);
  });
});
