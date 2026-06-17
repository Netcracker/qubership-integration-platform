// tests/web/response/chainApiRead.test.ts
import { Uri } from "vscode";
import { parseElement } from "../../../src/web/response/chainApiRead";
import { fileApi } from "../../../src/web/response/file";
import type { Element as ElementSchema } from "@netcracker/qip-schemas";

jest.mock("../../../src/web/response/file", () => ({
  fileApi: { readFile: jest.fn() },
}));

jest.mock("../../../src/web/response/file/fileExtensions", () => ({
  getExtensionsForUri: jest.fn(),
}));

const readFile = fileApi.readFile as jest.Mock;
const fileUri = {} as Uri;
const CHAIN_ID = "chain-1";

beforeEach(() => {
  readFile.mockReset();
});

const serviceCall = (properties: Record<string, unknown>): ElementSchema =>
  ({
    id: "el-1",
    name: "Service Call",
    type: "service-call",
    properties,
  }) as unknown as ElementSchema;

describe("parseElement - service-call before/after blocks", () => {
  test("should read the script file when a script block has a properties filename", async () => {
    readFile.mockResolvedValue("println 'hi'");
    const element = serviceCall({
      before: { type: "script", propertiesFilename: "s.groovy" },
    });

    const result = await parseElement(fileUri, element, CHAIN_ID);

    expect(readFile).toHaveBeenCalledWith(fileUri, "s.groovy");
    expect((result.properties as any).before.script).toBe("println 'hi'");
  });

  test("should set script to an empty string when a script block has no filename", async () => {
    const element = serviceCall({ before: { type: "script" } });

    const result = await parseElement(fileUri, element, CHAIN_ID);

    expect(readFile).not.toHaveBeenCalled();
    expect((result.properties as any).before.script).toBe("");
  });

  test("should merge mapper properties from a valid json file", async () => {
    readFile.mockResolvedValue(
      JSON.stringify({ mappingDescription: { a: 1 } }),
    );
    const element = serviceCall({
      after: [{ type: "mapper", propertiesFilename: "m.json" }],
    });

    const result = await parseElement(fileUri, element, CHAIN_ID);

    expect((result.properties as any).after[0].mappingDescription).toEqual({
      a: 1,
    });
  });

  test("should not merge mapper properties when the file content is empty", async () => {
    readFile.mockResolvedValue("");
    const element = serviceCall({
      after: [{ type: "mapper", propertiesFilename: "m.json" }],
    });

    const result = await parseElement(fileUri, element, CHAIN_ID);

    expect(
      (result.properties as any).after[0].mappingDescription,
    ).toBeUndefined();
  });

  test("should not merge mapper properties when the file content is whitespace only", async () => {
    readFile.mockResolvedValue("   \n  ");
    const element = serviceCall({
      after: [{ type: "mapper", propertiesFilename: "m.json" }],
    });

    const result = await parseElement(fileUri, element, CHAIN_ID);

    expect(
      (result.properties as any).after[0].mappingDescription,
    ).toBeUndefined();
  });

  test("should skip the file read for a mapper block without a filename", async () => {
    const element = serviceCall({ after: [{ type: "mapper" }] });

    await parseElement(fileUri, element, CHAIN_ID);

    expect(readFile).not.toHaveBeenCalled();
  });
});

describe("parseElement - properties exported to a separate file", () => {
  const separateFile = (properties: Record<string, unknown>): ElementSchema =>
    ({
      id: "el-2",
      name: "El",
      type: "some-type",
      properties,
    }) as unknown as ElementSchema;

  test("should populate listed json properties from the separate file", async () => {
    readFile.mockResolvedValue(JSON.stringify({ foo: 1, bar: 2 }));
    const element = separateFile({
      propertiesToExportInSeparateFile: "foo, bar",
      propertiesFilename: "p.json",
      exportFileExtension: "json",
    });

    const result = await parseElement(fileUri, element, CHAIN_ID);

    expect((result.properties as any).foo).toBe(1);
    expect((result.properties as any).bar).toBe(2);
  });

  test("should leave properties unset when the separate json file is empty", async () => {
    readFile.mockResolvedValue("");
    const element = separateFile({
      propertiesToExportInSeparateFile: "foo",
      propertiesFilename: "p.json",
      exportFileExtension: "json",
    });

    const result = await parseElement(fileUri, element, CHAIN_ID);

    expect((result.properties as any).foo).toBeUndefined();
  });

  test("should skip the read when no properties filename is present", async () => {
    const element = separateFile({
      propertiesToExportInSeparateFile: "foo",
      exportFileExtension: "json",
    });

    await parseElement(fileUri, element, CHAIN_ID);

    expect(readFile).not.toHaveBeenCalled();
  });

  test("should set the property to the raw file content for a non-json export", async () => {
    readFile.mockResolvedValue("raw-body");
    const element = separateFile({
      propertiesToExportInSeparateFile: "body",
      propertiesFilename: "b.txt",
      exportFileExtension: "txt",
    });

    const result = await parseElement(fileUri, element, CHAIN_ID);

    expect((result.properties as any).body).toBe("raw-body");
  });
});
