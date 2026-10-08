import * as serviceApiModify from "../../../src/web/response/serviceApiModify";
import * as responseBarrel from "../../../src/web/response";
import * as publicEntry from "../../../src/web/index";

describe("public API surface for custom actions in apps based on this extension", () => {
  it.each([
    "deleteSpecificationByUri",
    "deleteSpecificationGroupByUri",
  ] as const)("exports %s from the defining module", (name) => {
    expect(typeof serviceApiModify[name]).toBe("function");
  });

  it.each([
    "deleteSpecificationByUri",
    "deleteSpecificationGroupByUri",
  ] as const)("re-exports %s from the response barrel", (name) => {
    expect(
      typeof (responseBarrel as Record<string, unknown>)[name],
    ).toBe("function");
  });

  it.each([
    "deleteSpecificationByUri",
    "deleteSpecificationGroupByUri",
  ] as const)("re-exports %s from the public entry point", (name) => {
    expect(
      typeof (publicEntry as Record<string, unknown>)[name],
    ).toBe("function");
  });
});
