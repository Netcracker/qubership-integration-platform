// The extension ships as `@netcracker/qip-vscode-extension` to GitHub Packages, and another
// extension outside this repository consumes it as a library: it implements `FileApi` and installs
// it through `setFileApi`. That consumer is out of CI's reach, so a renamed export or a dropped
// `export *` line breaks it with nothing here going red. These cases pin the names it resolves.

import * as serviceApiModify from "../../../src/web/response/serviceApiModify";
import * as responseBarrel from "../../../src/web/response";
import * as publicEntry from "../../../src/web/index";

import * as responseFileBarrel from "../../../src/web/response/file";
import * as servicesBarrel from "../../../src/web/services";
import * as apiServicesBarrel from "../../../src/web/api-services";
import * as parsersBarrel from "../../../src/web/api-services/parsers";
import * as editorViewTypesBarrel from "../../../src/web/editorViewTypes";
import * as chainDiffEditorBarrel from "../../../src/web/chainDiffEditor";
import * as exportImagesHandlerBarrel from "../../../src/web/exportImagesHandler";
import * as exportImageUtilsBarrel from "../../../src/web/exportImageUtils";

import { fileApi } from "../../../src/web/response/file/fileApiProvider";
import type { QipExtensionAPI } from "../../../src/web/index";

// The consumer implements each of these through `setFileApi`. The list is plain strings rather than
// `keyof FileApi`, so a rename shows up as a diff here instead of as a compile error.
const fileApiMethods = [
  "deleteFile",
  "findAndBuildChainsRecursively",
  "findFile",
  "findFileById",
  "findFileByNavigationPath",
  "findFiles",
  "getContextService",
  "getDirectoriesToRemove",
  "getFileCreatedWhen",
  "getFileType",
  "getLibrary",
  "getMainChain",
  "getMainService",
  "getMcpService",
  "getRootDirectory",
  "getService",
  "getSpecApiFiles",
  "getSpecificationFiles",
  "getSpecificationGroupFiles",
  "parseFile",
  "readFile",
  "readFileContent",
  "removeFile",
  "writeFile",
  "writeMainChain",
  "writeMainService",
  "writePropertyFile",
  "writeServiceFile",
] as const;

// `QipExtensionAPI` is an interface, so the public entry point carries no runtime value for it.
// This mapped type is the guard: it stops compiling when the type export or a method goes.
const extensionApiMethods: Record<keyof QipExtensionAPI, true> = {
  loadConfigFromPath: true,
  registerConfig: true,
  unregisterConfig: true,
  getConfig: true,
};

// The modules `src/web/index.ts` re-exports with `export *`, bar `exportImageTypes`: that one
// declares types alone, so its `Object.keys` is empty and the comparison below would hold whatever
// the entry point does.
const barrels: [string, Record<string, unknown>][] = [
  ["response", responseBarrel],
  ["response/file", responseFileBarrel],
  ["services", servicesBarrel],
  ["api-services", apiServicesBarrel],
  ["api-services/parsers", parsersBarrel],
  ["editorViewTypes", editorViewTypesBarrel],
  ["chainDiffEditor", chainDiffEditorBarrel],
  ["exportImagesHandler", exportImagesHandlerBarrel],
  ["exportImageUtils", exportImageUtilsBarrel],
];

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

describe("the FileApi contract the consumer implements", () => {
  it("declares exactly the methods the consumer has to supply", () => {
    // `fileApi` is typed `FileApi` and built as an object literal, so its keys are the interface's
    // keys: a method added to or removed from `FileApi` moves this list.
    expect(Object.keys(fileApi).sort()).toEqual([...fileApiMethods]);
  });
});

describe("the names the consumer resolves through the public entry point", () => {
  it.each(["setFileApi", "getDefaultExtensions", "getExtensionsForUri"] as const)(
    "re-exports %s as a function",
    (name) => {
      expect(typeof (publicEntry as Record<string, unknown>)[name]).toBe(
        "function",
      );
    },
  );

  it("re-exports the fileApi facade as an object", () => {
    expect((publicEntry as Record<string, unknown>).fileApi).toBe(fileApi);
  });

  // Each barrel's own exports are compared against the entry point rather than listed:
  // `src/web/response/**` alone exports 153 declarations, so a list would churn on every helper.
  it.each(barrels)("re-exports every name of the %s barrel", (_name, barrel) => {
    const missing = Object.keys(barrel).filter((key) => !(key in publicEntry));
    expect(missing).toEqual([]);
  });
});
