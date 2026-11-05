/**
 * How a `vscode` case compares a written file with its golden: the ids the extension generated are
 * numbered, and the ids the fixture already held stay as they are.
 */
import { test, expect } from "@playwright/test";
import { normalizeIds } from "../../support/vscode-golden.js";

const FIXTURE_ID = "7f15ea75-516e-4104-81c6-e68550a9b9e5";
const FIRST = "0f3a1c2e-9b4d-4e6f-8a1b-2c3d4e5f6a7b";
const SECOND = "c0ffee00-1234-4abc-9def-0123456789ab";

test.describe("vscode goldens", () => {
  test("generated ids are numbered, and fixture ids are kept", () => {
    const written = [`id: ${SECOND}`, `from: ${FIXTURE_ID}`, `to: ${SECOND}`, `parent: ${FIRST}`].join("\n");
    expect(normalizeIds(written, `id: ${FIXTURE_ID}`)).toBe(
      [`id: <new-id-1>`, `from: ${FIXTURE_ID}`, `to: <new-id-1>`, `parent: <new-id-2>`].join("\n"),
    );
  });

  test("an id inside a file name is normalized like any other", () => {
    expect(normalizeIds(`propertiesFilename: ${FIRST}.element.script.cip.groovy`, "")).toBe(
      "propertiesFilename: <new-id-1>.element.script.cip.groovy",
    );
  });
});
