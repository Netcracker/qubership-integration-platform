/**
 * What the stackless projects are allowed to import, read off their directories.
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
 *
 * The `vscode` project needs no stack either, and its specs take `test` and `expect` from
 * `support/vscode-guard.js`, which builds on `@playwright/test` and carries the page guard.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { test, expect } from "@playwright/test";

const SPECS = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");

/** The spec directories of the projects that need no stack, each with the one module its specs may take `test` or `expect` from. */
const ALLOWED: Record<string, string> = {
  schema: "@playwright/test",
  harness: "@playwright/test",
  vscode: path.join(SPECS, "..", "support", "vscode-guard.js"),
};

const STACKLESS_DIRS = Object.keys(ALLOWED);

/** A relative specifier resolved against the spec's directory, and a package name as it is. */
function moduleOf(spec: string, from: string): string {
  return from.startsWith(".") ? path.resolve(SPECS, path.dirname(spec), from) : from;
}

/** How a message names a module: relative to `e2e/`, or by its package name. */
const shown = (module: string) => (path.isAbsolute(module) ? path.relative(path.join(SPECS, ".."), module) : module);

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
  const allowed = ALLOWED[spec.split("/")[0]];
  for (const [, , clause, from] of source.matchAll(IMPORT)) {
    if (moduleOf(spec, from) === allowed) continue;
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

test.describe("stackless imports", () => {
  test("a stackless spec takes test and expect from its own module", () => {
    // A scan over an empty directory passes for the worst possible reason.
    for (const dir of STACKLESS_DIRS) expect(specs.filter((spec) => spec.startsWith(`${dir}/`)).length, dir).toBeGreaterThan(0);

    const offenses = specs.flatMap(offensesIn);
    expect(
      offenses.map((each) => `${each.spec} imports ${each.name} from ${each.from}, where only ${shown(ALLOWED[each.spec.split("/")[0]])} is allowed`),
      `a stackless spec may take test and expect from its directory's module only: ` +
        `any other module can install fixtures, and support/fixtures.js installs ones that need a running stack`,
    ).toEqual([]);
  });
});
