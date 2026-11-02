/**
 * The title rules of the `e2e-spec-authoring` skill (rules 10 and 21), read off every spec file.
 *
 * Whether every case inherits a component tag is checked by `npm run reconcile` over the report
 * instead, since a case declared in a helper function carries no tag of its own here.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { test, expect } from "@playwright/test";
import { parseCases, titleProblems } from "../../support/title-rules.js";

const SPECS = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");

/** Every spec file, relative to `specs/`. */
const SPEC_FILES = fs
  .readdirSync(SPECS, { recursive: true, withFileTypes: true })
  .filter((entry) => entry.isFile() && entry.name.endsWith(".spec.ts"))
  .map((entry) => path.relative(SPECS, path.join(entry.parentPath, entry.name)));

const problemsIn = (file: string) => titleProblems(fs.readFileSync(path.join(SPECS, file), "utf-8"), `specs/${file}`);

/** A compliant file around `body`, which stands inside its one describe. */
const inDescribe = (body: string) => `test.describe("subject", { tag: ["@engine"] }, () => {\n${body}\n});\n`;
const problemsOf = (source: string) => titleProblems(source, "a.spec.ts");

test.describe("title rules", () => {
  test("every spec keeps them", () => {
    expect(SPEC_FILES, "the lint does not read a known spec file").toEqual(expect.arrayContaining(["harness/title-rules.spec.ts", "api/chains.spec.ts"]));
    expect(SPEC_FILES.flatMap(problemsIn)).toEqual([]);
  });

  test.describe("titleProblems", () => {
    test("returns nothing for a compliant file", () => {
      const source = `import { test } from "@playwright/test";
// A comment that names test("x") and test.fail().
${inDescribe(`  test("reports UP", { tag: ["@slow"] }, async () => {});
  test.describe("nested", { tag: ["@kafka"] }, () => {
    test("sends", async () => {});
  });`)}`;
      expect(problemsOf(source)).toEqual([]);
    });

    test("flags a file with no describe", () => {
      expect(problemsOf(`test("sends", async () => {});\n`)).toEqual(["a.spec.ts:1: a case outside any test.describe"]);
    });

    test("flags a second top-level describe", () => {
      const source = `${inDescribe(`test("a", async () => {});`)}test.describe("other", () => {\n  test("b", async () => {});\n});\n`;
      expect(problemsOf(source)).toEqual(["a.spec.ts:4: a second top-level test.describe"]);
    });

    test("flags a title of 61 characters and passes one of 60", () => {
      expect(problemsOf(inDescribe(`test("${"a".repeat(60)}", async () => {});`))).toEqual([]);
      expect(problemsOf(inDescribe(`test("${"a".repeat(61)}", async () => {});`))).toEqual([
        "a.spec.ts:2: a title of 61 characters, over 60",
      ]);
    });

    test("counts only the literal part of a template", () => {
      const loop = (literal: string) => inDescribe(`for (const mode of MODES) test(\`\${mode.name} ${literal}\`, async () => {});`);
      expect(problemsOf(loop("a".repeat(59)))).toEqual([]);
      expect(problemsOf(loop("a".repeat(60)))).toEqual(["a.spec.ts:2: a title of 61 characters, over 60"]);
    });

    test("does not measure a title taken from a variable", () => {
      expect(problemsOf(inDescribe(`for (const each of GOLDEN) test(each.title, async () => {});`))).toEqual([]);
    });

    test("accepts a loop over an imported array", () => {
      const source = `import { HTTP_METHODS } from "./methods.js";\n${inDescribe(`for (const method of HTTP_METHODS) test(\`sends \${method}\`, async () => {});`)}`;
      expect(problemsOf(source)).toEqual([]);
    });

    test("flags a reason phrase in a title", () => {
      for (const phrase of ["because", "which is why", "rather than", "so that"]) {
        expect(problemsOf(inDescribe(`test("fails ${phrase} it fails", async () => {});`))).toEqual([
          `a.spec.ts:2: "${phrase}" in a title: the reason goes in a comment above the case`,
        ]);
      }
    });

    test("flags an unknown literal tag", () => {
      expect(problemsOf(inDescribe(`test("a", { tag: ["@engine", "@tier1"] }, async () => {});`))).toEqual([
        "a.spec.ts:2: the tag @tier1 is not in the closed list",
      ]);
      expect(problemsOf(`test.describe("subject", { tag: "@extension" }, () => {\n  test("a", async () => {});\n});\n`)).toEqual([
        "a.spec.ts:1: the tag @extension is not in the closed list",
      ]);
    });

    test("skips a computed tag", () => {
      const source = inDescribe(`test("a", { tag: MOCKED_TAGS }, async () => {});
test("b", { tag: [COMPONENT_TAG[role], "@slow"] }, async () => {});`);
      expect(problemsOf(source)).toEqual([]);
    });

    test("ignores a test.fail() in a comment or a string", () => {
      const source = inDescribe(`// test.fail();
const pin = "test.fail()";
test("a", async () => {});`);
      expect(problemsOf(source)).toEqual([]);
    });

    test("flags a test.fail() with no arguments outside a case", () => {
      const rule = "a test.fail() with no arguments marks every case in its scope";
      expect(problemsOf(inDescribe(`test.fail();\ntest("a", async () => {});`))).toEqual([`a.spec.ts:2: ${rule}`]);
      expect(problemsOf(`test.fail();\n${inDescribe(`test("a", async () => {});`)}`)).toEqual([`a.spec.ts:1: ${rule}`]);
      // Inside a case body it marks that case alone.
      expect(problemsOf(inDescribe(`test("a", async () => { test.fail(); });`))).toEqual([]);
    });
  });

  test.describe("parseCases", () => {
    test("reads test.fail(title, body) as a case", () => {
      const spec = parseCases(inDescribe(`test.fail("drops the request id", { tag: ["@kafka"] }, async () => {});`));
      expect(spec.describes[0].cases).toEqual([
        { title: "drops the request id", line: 2, tags: ["@kafka"], annotations: [] },
      ]);
      expect(spec.bareFails).toEqual([]);
    });

    test("reads configure, skip, step, and setTimeout as neither", () => {
      const spec = parseCases(`test.describe.configure({ mode: "serial" });
test.skip(!env.listsPods, "no pod to expand");
test.setTimeout(60_000);
test.describe.serial("subject", () => {
  test.describe.configure({ retries: 0 });
  test("a", async () => {
    await test.step("create", async () => {});
    test.skip(true, "a reason");
  });
});
`);
      expect(spec.cases).toEqual([]);
      expect(spec.describes).toHaveLength(1);
      expect(spec.describes[0].describes).toEqual([]);
      expect(spec.describes[0].cases.map((each) => each.title)).toEqual(["a"]);
    });

    test("puts the cases of a function inside a describe under it", () => {
      const spec = parseCases(`test.describe("actuator health", () => {
  function actuatorCases(role: string) {
    test("reports UP", async () => {});
  }
  test.describe("engine", { tag: ["@engine"] }, () => actuatorCases("engine"));
});
`);
      expect(spec.cases).toEqual([]);
      expect(spec.describes[0].cases.map((each) => each.line)).toEqual([3]);
      expect(spec.describes[0].describes[0].tags).toEqual(["@engine"]);
    });

    test("reads a tag and an annotation off options on three lines", () => {
      const spec = parseCases(`test.describe("subject", () => {
  test(
    "a",
    { tag: ["@classic-only"], annotation: [{ type: "classic-only", description: "a reason" }, { type: "issue", description: REASON }] },
    async () => {},
  );
});
`);
      expect(spec.describes[0].cases).toEqual([
        {
          title: "a",
          line: 2,
          tags: ["@classic-only"],
          annotations: [
            { type: "classic-only", description: "a reason" },
            { type: "issue", description: "REASON" },
          ],
        },
      ]);
    });
  });
});
