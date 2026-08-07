// The guard on the precedence declaration.
//
// Three entities live under two generations of names, and every read has to resolve to the current
// one. That was correct by inspection until it wasn't: a dozen candidate arrays each spelled its
// own order, and `serviceApiRead.findModelFileById` and the extension-less `fileApiImpl.findFileById`
// both had `.specification.` ahead of `.api.`. A read then answered from the file the last
// conversion superseded.
//
// So the order lives in `namePrecedence.ts` alone, and this walks the sources to keep it there.
// Three rules:
//
//   1. every extension key belongs to a declared name set, so a new pair of names cannot be added
//      without saying which of the two a write emits;
//   2. no array literal in `src/web` puts a legacy name of a set ahead of a current one — a
//      hand-written order that disagrees with the declaration is a failure here, not a review
//      comment;
//   3. the declaration itself agrees with what the write paths emit, which is the only independent
//      statement of which name is current.
//
// Rule 2 reads the sources syntactically, so it sees a list however it is built: `ext.api`,
// a destructured `apiGroup`, or either spelling mixed. What it cannot see is a list assembled
// somewhere other than an array literal — but there is no reason to build one, because
// `candidateExtensions` is the supported way and it derives the order from rule 1's declaration.

import * as fs from "fs";
import * as path from "path";
import * as ts from "typescript";

import { buildDefaultExtensions } from "../../../src/web/response/file/fileExtensions";
import {
  API_GROUP_NAMES,
  API_NAMES,
  candidateExtensions,
  currentExtension,
  legacyExtension,
  NAME_SETS,
  SERVICE_NAMES,
} from "../../../src/web/response/file/namePrecedence";
import { serviceExtensionForType } from "../../../src/web/response/file/serviceFileType";
import { IntegrationSystemType } from "../../../src/web/api-services/servicesTypes";

const SRC_ROOT = path.resolve(__dirname, "../../../src/web");
const DECLARATION = path.join(SRC_ROOT, "response/file/namePrecedence.ts");

const ext = buildDefaultExtensions("qip");

/** Keys that name no pair of generations: they carry one name and always have. */
const UNPAIRED_KEYS = new Set(["appName", "chain"]);

const NAME_SET_ENTRIES = Object.entries(NAME_SETS);

/** The sources of `src/web`, with the integration harness left out. */
function sourceFiles(dir: string): string[] {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      return entry.name === "test" ? [] : sourceFiles(full);
    }
    return entry.isFile() &&
      entry.name.endsWith(".ts") &&
      !entry.name.endsWith(".test.ts")
      ? [full]
      : [];
  });
}

/** The extension key an array element names, whether reached through a property or destructured. */
function extensionKeyOf(node: ts.Expression): string | undefined {
  if (ts.isPropertyAccessExpression(node)) {
    return node.name.text;
  }
  if (ts.isIdentifier(node)) {
    return node.text;
  }
  return undefined;
}

type Violation = {
  readonly file: string;
  readonly line: number;
  readonly set: string;
  readonly order: string;
};

/** Every array literal that puts a legacy name of one set ahead of a current name of the same set. */
function orderViolations(): Violation[] {
  const violations: Violation[] = [];

  for (const file of sourceFiles(SRC_ROOT)) {
    if (file === DECLARATION) {
      continue;
    }
    const source = ts.createSourceFile(
      file,
      fs.readFileSync(file, "utf8"),
      ts.ScriptTarget.ES2020,
      true,
    );

    const visit = (node: ts.Node): void => {
      if (ts.isArrayLiteralExpression(node)) {
        const keys = node.elements.map(extensionKeyOf);

        for (const [setName, names] of NAME_SET_ENTRIES) {
          const current = keys.flatMap((key, index) =>
            key !== undefined &&
            (names.current as readonly string[]).includes(key)
              ? [index]
              : [],
          );
          const legacy = keys.flatMap((key, index) =>
            key !== undefined &&
            (names.legacy as readonly string[]).includes(key)
              ? [index]
              : [],
          );
          if (current.length === 0 || legacy.length === 0) {
            continue;
          }
          if (Math.min(...legacy) < Math.max(...current)) {
            violations.push({
              file: path.relative(SRC_ROOT, file),
              line:
                source.getLineAndCharacterOfPosition(node.getStart(source))
                  .line + 1,
              set: setName,
              order: keys.join(", "),
            });
          }
        }
      }
      ts.forEachChild(node, visit);
    };

    visit(source);
  }

  return violations;
}

describe("every extension key belongs to a declared name set", () => {
  it("leaves no key undeclared", () => {
    const declared = new Set(
      NAME_SET_ENTRIES.flatMap(([, names]) => [
        ...names.current,
        ...names.legacy,
      ]),
    );

    const undeclared = Object.keys(ext).filter(
      (key) => !UNPAIRED_KEYS.has(key) && !declared.has(key as never),
    );

    expect(undeclared).toEqual([]);
  });
});

describe("the precedence declarations", () => {
  it.each(NAME_SET_ENTRIES)(
    "puts every current name of %s ahead of every legacy one",
    (_setName, names) => {
      const candidates = candidateExtensions(names as never, ext);

      expect(candidates).toHaveLength(
        names.current.length + names.legacy.length,
      );
      for (const key of names.legacy) {
        expect(candidates.indexOf(ext[key])).toBeGreaterThanOrEqual(
          names.current.length,
        );
      }
    },
  );

  // The independent statement of which name is current: the one a write emits.
  it("declares the extension the api write emits as current", () => {
    expect(currentExtension(API_NAMES, ext)).toBe(ext.api);
    expect(legacyExtension(API_NAMES, ext)).toBe(ext.specification);
  });

  it("declares the extension the group write emits as current", () => {
    expect(currentExtension(API_GROUP_NAMES, ext)).toBe(ext.apiGroup);
    expect(legacyExtension(API_GROUP_NAMES, ext)).toBe(ext.specificationGroup);
  });

  it("declares every extension a typed service write emits as current", () => {
    const current = SERVICE_NAMES.current.map((key) => ext[key]);

    for (const type of Object.values(IntegrationSystemType)) {
      expect(current).toContain(serviceExtensionForType(type, ext));
    }
    expect(current).not.toContain(ext.service);
  });
});

describe("no source hand-writes a candidate order", () => {
  it("puts no legacy name ahead of a current one in any array literal", () => {
    expect(orderViolations()).toEqual([]);
  });

  // The canary: a rule that matched nothing would pass whatever the sources said.
  it("reads the array literals the candidate lists are built from", () => {
    const seen = sourceFiles(SRC_ROOT).some((file) => {
      const source = ts.createSourceFile(
        file,
        fs.readFileSync(file, "utf8"),
        ts.ScriptTarget.ES2020,
        true,
      );
      let found = false;
      const visit = (node: ts.Node): void => {
        if (ts.isArrayLiteralExpression(node)) {
          const keys = node.elements
            .map(extensionKeyOf)
            .filter((key): key is string => key !== undefined);
          if (keys.some((key) => key in ext)) {
            found = true;
          }
        }
        ts.forEachChild(node, visit);
      };
      visit(source);
      return found;
    });

    expect(seen).toBe(true);
  });
});
