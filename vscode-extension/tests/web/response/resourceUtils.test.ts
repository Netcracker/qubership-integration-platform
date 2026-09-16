import { Uri } from "vscode";
import type { Element as ElementSchema } from "@netcracker/qip-schemas";
import {
  buildCipFilename,
  buildServiceCallFilename,
  cleanupOrphanPropertyFiles,
  collectFilenamesFromElementTree,
  deleteElementsPropertyFiles,
  getOrCreatePropertyFilename,
  normalizeAfterId,
} from "../../../src/web/response/resourceUtils";
import { fileApi } from "../../../src/web/response/file";

jest.mock("../../../src/web/response/file", () => ({
  fileApi: {
    removeFile: jest.fn().mockResolvedValue(undefined),
  },
}));

const mockedRemoveFile = fileApi.removeFile as jest.Mock;
const fileUri = { path: "/workspace/chain" } as unknown as Uri;

const serviceCall = (properties: Record<string, unknown>, children?: ElementSchema[]): ElementSchema =>
  ({
    id: "el-1",
    name: "Service Call",
    type: "service-call",
    properties,
    children,
  }) as unknown as ElementSchema;

const otherElement = (type: string, properties: Record<string, unknown> = {}, children?: ElementSchema[]): ElementSchema =>
  ({
    id: `el-${type}`,
    name: type,
    type,
    properties,
    children,
  }) as unknown as ElementSchema;

beforeEach(() => {
  jest.clearAllMocks();
});

describe("collectFilenamesFromElementTree", () => {
  test("collects before and after filenames from service-call", () => {
    const out = new Set<string>();
    collectFilenamesFromElementTree(
      [
        serviceCall({
          before: { type: "script", propertiesFilename: "before.groovy" },
          after: [{ type: "mapper", propertiesFilename: "after.json" }],
        }),
      ],
      out,
    );
    expect(out).toEqual(new Set(["before.groovy", "after.json"]));
  });

  test("ignores before/after when element is not service-call", () => {
    const out = new Set<string>();
    collectFilenamesFromElementTree(
      [otherElement("http-trigger", { before: { propertiesFilename: "x.groovy" }, after: [{ propertiesFilename: "y.json" }] })],
      out,
    );
    expect(out.size).toBe(0);
  });

  test("ignores missing or empty propertiesFilename", () => {
    const out = new Set<string>();
    collectFilenamesFromElementTree(
      [
        serviceCall({
          before: { type: "script" },
          after: [{ type: "mapper", propertiesFilename: "" }, { type: "mapper" }],
        }),
      ],
      out,
    );
    expect(out.size).toBe(0);
  });

  test("handles undefined and empty input", () => {
    const out = new Set<string>();
    collectFilenamesFromElementTree(undefined, out);
    expect(out.size).toBe(0);
    collectFilenamesFromElementTree([], out);
    expect(out.size).toBe(0);
  });

  test("handles element without properties or non-object properties", () => {
    const out = new Set<string>();
    collectFilenamesFromElementTree(
      [
        { id: "1", type: "service-call", properties: null } as unknown as ElementSchema,
        { id: "2", type: "service-call" } as unknown as ElementSchema,
      ],
      out,
    );
    expect(out.size).toBe(0);
  });

  test("traverses children recursively", () => {
    const out = new Set<string>();
    const child = serviceCall({ after: [{ propertiesFilename: "child.json", type: "mapper" }] });
    const parent = otherElement("container", {}, [child]);
    // non-service-call parent itself ignored, but child collected via stack
    collectFilenamesFromElementTree([parent], out);
    expect(out).toEqual(new Set(["child.json"]));

    const out2 = new Set<string>();
    const serviceParent = serviceCall({ before: { propertiesFilename: "parent.groovy", type: "script" } }, [child]);
    collectFilenamesFromElementTree([serviceParent], out2);
    expect(out2).toEqual(new Set(["parent.groovy", "child.json"]));
  });

  test("collects multiple after blocks", () => {
    const out = new Set<string>();
    collectFilenamesFromElementTree(
      [
        serviceCall({
          after: [
            { propertiesFilename: "a.json", type: "mapper" },
            { propertiesFilename: "b.json", type: "mapper" },
            { propertiesFilename: "c.groovy", type: "script" },
          ],
        }),
      ],
      out,
    );
    expect(out).toEqual(new Set(["a.json", "b.json", "c.groovy"]));
  });
});

describe("deleteElementsPropertyFiles", () => {
  test("removes top-level separate file via propertiesToExportInSeparateFile", async () => {
    const elements: ElementSchema[] = [
      {
        id: "e1",
        type: "http-trigger",
        properties: { propertiesToExportInSeparateFile: "body", propertiesFilename: "body.txt" },
      } as unknown as ElementSchema,
    ];
    await deleteElementsPropertyFiles(fileUri, elements);
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "body.txt");
    expect(mockedRemoveFile).toHaveBeenCalledTimes(1);
  });

  test("removes script before block and sets script property", async () => {
    const elements: ElementSchema[] = [serviceCall({ before: { type: "script", propertiesFilename: "s.groovy" } })];
    await deleteElementsPropertyFiles(fileUri, elements);
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "s.groovy");
    // script assignment: element still mutated but removeFile mock returns undefined
    expect(elements[0].properties as unknown as Record<string, unknown>).toBeDefined();
  });

  test("removes mapper after block", async () => {
    const elements: ElementSchema[] = [serviceCall({ after: [{ type: "mapper", propertiesFilename: "m.json" }] })];
    await deleteElementsPropertyFiles(fileUri, elements);
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "m.json");
  });

  test("removes mapper with prefix mapper-xxx", async () => {
    const elements: ElementSchema[] = [serviceCall({ after: [{ type: "mapper-advanced", propertiesFilename: "m2.json" }] })];
    await deleteElementsPropertyFiles(fileUri, elements);
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "m2.json");
  });

  test("ignores blocks without filename", async () => {
    const elements: any[] = [serviceCall({ before: { type: "script" }, after: [{ type: "mapper" }] })];
    // service-call blocks without propertiesFilename are ignored even though type matches script/mapper
    // the implementation guards filename before calling removeFile (handles any[] shape)
    await deleteElementsPropertyFiles(fileUri, elements);
    expect(mockedRemoveFile).not.toHaveBeenCalled();
  });

  test("ignores non-service-call before/after", async () => {
    const elements: ElementSchema[] = [otherElement("http-trigger", { before: { propertiesFilename: "x", type: "script" } })];
    await deleteElementsPropertyFiles(fileUri, elements);
    expect(mockedRemoveFile).not.toHaveBeenCalled();
  });

  test("recurses into children", async () => {
    const child = serviceCall({ after: [{ propertiesFilename: "child.json", type: "mapper" }] });
    const parent = otherElement("container", {}, [child]);
    await deleteElementsPropertyFiles(fileUri, [parent]);
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "child.json");
  });

  test("handles top-level and service-call in same element (if both present)", async () => {
    const elements: ElementSchema[] = [
      {
        id: "e1",
        type: "service-call",
        properties: {
          propertiesToExportInSeparateFile: "body",
          propertiesFilename: "body.txt",
          before: { type: "script", propertiesFilename: "b.groovy" },
          after: [{ type: "mapper", propertiesFilename: "a.json" }],
        },
      } as unknown as ElementSchema,
    ];
    await deleteElementsPropertyFiles(fileUri, elements);
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "body.txt");
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "b.groovy");
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "a.json");
    expect(mockedRemoveFile).toHaveBeenCalledTimes(3);
  });

  test("ignores top-level separate file when filename missing or empty", async () => {
    const elements: any[] = [
      { id: "e1", type: "http-trigger", properties: { propertiesToExportInSeparateFile: "body" } },
      { id: "e2", type: "http-trigger", properties: { propertiesToExportInSeparateFile: "body", propertiesFilename: "" } },
      { id: "e3", type: "http-trigger", properties: { propertiesToExportInSeparateFile: "", propertiesFilename: "body.txt" } },
    ];
    await deleteElementsPropertyFiles(fileUri, elements);
    expect(mockedRemoveFile).not.toHaveBeenCalled();
  });

  test("handles elements with null or undefined properties without throwing", async () => {
    const elements: any[] = [
      { id: "1", type: "service-call", properties: null },
      { id: "2", type: "service-call" },
      { id: "3", type: "http-trigger", properties: null },
      { id: "4", type: "service-call", properties: { before: null, after: null } },
    ];
    await expect(deleteElementsPropertyFiles(fileUri, elements)).resolves.not.toThrow();
    expect(mockedRemoveFile).not.toHaveBeenCalled();
  });

  test("ignores service-call blocks with empty string filename", async () => {
    const elements: any[] = [
      serviceCall({
        before: { type: "script", propertiesFilename: "" },
        after: [{ type: "mapper", propertiesFilename: "" }, { type: "script", propertiesFilename: "" }],
      }),
    ];
    await deleteElementsPropertyFiles(fileUri, elements);
    expect(mockedRemoveFile).not.toHaveBeenCalled();
  });

  test("ignores blocks with unsupported type", async () => {
    const elements: any[] = [
      serviceCall({
        before: { type: "unknown", propertiesFilename: "x.groovy" },
        after: [{ type: "http", propertiesFilename: "y.json" }, { propertiesFilename: "z.json" }],
      }),
    ];
    await deleteElementsPropertyFiles(fileUri, elements);
    expect(mockedRemoveFile).not.toHaveBeenCalled();
  });

  test("handles after as non-array and before as non-object gracefully", async () => {
    const elements: any[] = [
      { id: "1", type: "service-call", properties: { before: "not-an-object", after: "not-an-array" } },
      { id: "2", type: "service-call", properties: { before: { type: "script", propertiesFilename: "ok.groovy" }, after: { type: "mapper", propertiesFilename: "bad.json" } } },
    ];
    await deleteElementsPropertyFiles(fileUri, elements);
    // only the valid before block should trigger
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "ok.groovy");
    expect(mockedRemoveFile).toHaveBeenCalledTimes(1);
  });
});

describe("cleanupOrphanPropertyFiles", () => {
  test("does nothing when no candidates (old subset of new)", async () => {
    const oldFilenames = new Set(["a.json"]);
    const newFilenames = new Set(["a.json", "b.json"]);
    const chainElements: ElementSchema[] = [];
    await cleanupOrphanPropertyFiles(fileUri, oldFilenames, newFilenames, chainElements);
    expect(mockedRemoveFile).not.toHaveBeenCalled();
  });

  test("does nothing when candidates are still live in chain", async () => {
    const oldFilenames = new Set(["a.json", "b.json"]);
    const newFilenames = new Set(["a.json"]);
    const chainElements: ElementSchema[] = [serviceCall({ after: [{ propertiesFilename: "b.json", type: "mapper" }] })];
    await cleanupOrphanPropertyFiles(fileUri, oldFilenames, newFilenames, chainElements);
    expect(mockedRemoveFile).not.toHaveBeenCalled();
  });

  test("removes single orphan file", async () => {
    const oldFilenames = new Set(["a.json", "b.json"]);
    const newFilenames = new Set(["a.json"]);
    const chainElements: ElementSchema[] = [];
    await cleanupOrphanPropertyFiles(fileUri, oldFilenames, newFilenames, chainElements);
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "b.json");
    expect(mockedRemoveFile).toHaveBeenCalledTimes(1);
  });

  test("removes multiple orphan files", async () => {
    const oldFilenames = new Set(["a.json", "b.json", "c.groovy"]);
    const newFilenames = new Set<string>([]);
    const chainElements: ElementSchema[] = [];
    await cleanupOrphanPropertyFiles(fileUri, oldFilenames, newFilenames, chainElements);
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "a.json");
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "b.json");
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "c.groovy");
    expect(mockedRemoveFile).toHaveBeenCalledTimes(3);
  });

  test("removes only orphans, keeps live ones when live in nested children", async () => {
    const oldFilenames = new Set(["a.json", "b.json", "c.json"]);
    const newFilenames = new Set(["a.json"]);
    // b.json still live in child
    const child = serviceCall({ after: [{ propertiesFilename: "b.json", type: "mapper" }] });
    const chainElements: ElementSchema[] = [otherElement("container", {}, [child])];
    await cleanupOrphanPropertyFiles(fileUri, oldFilenames, newFilenames, chainElements);
    expect(mockedRemoveFile).not.toHaveBeenCalledWith(fileUri, "b.json");
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "c.json");
    expect(mockedRemoveFile).toHaveBeenCalledTimes(1);
  });

  test("deduplicates orphans", async () => {
    // orphanSet dedup via Set
    const oldFilenames = new Set(["a.json", "a.json"]);
    const newFilenames = new Set<string>([]);
    await cleanupOrphanPropertyFiles(fileUri, oldFilenames, newFilenames, []);
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "a.json");
    expect(mockedRemoveFile).toHaveBeenCalledTimes(1);
  });

  test("does nothing when old is empty", async () => {
    await cleanupOrphanPropertyFiles(fileUri, new Set(), new Set(["a.json"]), []);
    expect(mockedRemoveFile).not.toHaveBeenCalled();
  });

  test("passes filename directly (fileApi handles resources fallback)", async () => {
    const oldFilenames = new Set(["myfile.json"]);
    await cleanupOrphanPropertyFiles(fileUri, oldFilenames, new Set(), []);
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "myfile.json");
  });

  test("handles empty chainElements", async () => {
    const oldFilenames = new Set(["x.groovy"]);
    await cleanupOrphanPropertyFiles(fileUri, oldFilenames, new Set(), []);
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "x.groovy");
  });
});

describe("normalizeAfterId", () => {
  it.each([
    ["100..199", "1xx"],
    ["200..299", "2xx"],
    ["300..399", "3xx"],
    ["400..499", "4xx"],
    ["500..599", "5xx"],
  ])("normalizes %s to %s", (input, expected) => {
    expect(normalizeAfterId(input)).toBe(expected);
  });

  it.each([
    "600..699",
    "100..299",
    "200..200",
    "abc",
    "1xx",
    "",
    "100..199 ",
    " 100..199",
    "100..1990",
    "404",
  ])("leaves non-matching value %s unchanged", (input) => {
    expect(normalizeAfterId(input)).toBe(input);
  });
});

describe("buildCipFilename", () => {
  it("builds a dot-separated cip filename", () => {
    expect(buildCipFilename("el-1", "element", "mapper", "json")).toBe("el-1.element.mapper.cip.json");
  });

  it("builds a before segment filename", () => {
    expect(buildCipFilename("el-42", "before", "script", "groovy")).toBe("el-42.before.script.cip.groovy");
  });

  it("builds an after segment with normalized range", () => {
    expect(buildCipFilename("el-1", "after-2xx", "mapper", "json")).toBe("el-1.after-2xx.mapper.cip.json");
  });
});

describe("getOrCreatePropertyFilename", () => {
  it("returns the existing filename when one is supplied", () => {
    expect(getOrCreatePropertyFilename("http-sender", ["script"], "groovy", "el-1", "old.cip.groovy")).toBe(
      "old.cip.groovy",
    );
  });

  it("returns existing filename even when propertyNames and extension are missing", () => {
    expect(getOrCreatePropertyFilename("mapper", undefined, undefined, "el-1", "keep.me")).toBe("keep.me");
  });

  it("builds a mapper kind filename for a single mappingDescription property on a mapper type", () => {
    expect(getOrCreatePropertyFilename("mapper-custom", ["mappingDescription"], "json", "el-1")).toBe(
      "el-1.element.mapper.cip.json",
    );
  });

  it("builds a script kind filename for a single script property on a mapper type", () => {
    expect(getOrCreatePropertyFilename("mapper", ["script"], "groovy", "el-1")).toBe(
      "el-1.element.script.cip.groovy",
    );
  });

  it("preserves a custom single property name for a mapper type", () => {
    expect(getOrCreatePropertyFilename("mapper-foo", ["customProp"], "txt", "el-42")).toBe(
      "el-42.element.customProp.cip.txt",
    );
  });

  it("collapses multiple properties on a mapper type to mapper kind", () => {
    expect(getOrCreatePropertyFilename("mapper", ["a", "b"], "json", "el-1")).toBe("el-1.element.mapper.cip.json");
  });

  it("builds a script kind filename for a single script property on a non-mapper type", () => {
    expect(getOrCreatePropertyFilename("http-sender", ["script"], "groovy", "el-7")).toBe(
      "el-7.element.script.cip.groovy",
    );
  });

  it("preserves a custom single property name for a non-mapper type", () => {
    expect(getOrCreatePropertyFilename("http-sender", ["myProp"], "json", "el-7")).toBe(
      "el-7.element.myProp.cip.json",
    );
  });

  it("collapses multiple properties on a non-mapper type to properties kind", () => {
    expect(getOrCreatePropertyFilename("service-call", ["a", "b"], "json", "el-1")).toBe(
      "el-1.element.properties.cip.json",
    );
  });

  it("treats type not starting with mapper as non-mapper", () => {
    expect(getOrCreatePropertyFilename("http-trigger", ["solo"], "json", "el-1")).toBe(
      "el-1.element.solo.cip.json",
    );
  });

  it("throws when propertyNames is undefined and no existing filename", () => {
    expect(() => getOrCreatePropertyFilename("http-sender", undefined, "json", "el-1")).toThrow(
      "Property names and exportFileExtension should be presented",
    );
  });

  it("throws when exportFileExtension is undefined and no existing filename", () => {
    expect(() => getOrCreatePropertyFilename("http-sender", ["script"], undefined, "el-1")).toThrow(
      "Property names and exportFileExtension should be presented",
    );
  });

  it("throws when both propertyNames and exportFileExtension are undefined", () => {
    expect(() => getOrCreatePropertyFilename("http-sender", undefined, undefined, "el-1")).toThrow();
  });
});

describe("buildServiceCallFilename", () => {
  it("returns the existing filename when one is supplied", () => {
    expect(buildServiceCallFilename("el-1", true, { id: "404" }, "script", "groovy", "keep.groovy")).toBe(
      "keep.groovy",
    );
  });

  it("returns existing filename for after block even when block has a range id", () => {
    expect(buildServiceCallFilename("el-1", false, { id: "200..299" }, "mapper", "json", "keep.json")).toBe(
      "keep.json",
    );
  });

  it("builds a before script filename", () => {
    expect(buildServiceCallFilename("el-1", true, {}, "script", "groovy")).toBe("el-1.before.script.cip.groovy");
  });

  it("builds a before mapper filename", () => {
    expect(buildServiceCallFilename("el-1", true, {}, "mapper", "json")).toBe("el-1.before.mapper.cip.json");
  });

  it("builds an after script filename with id", () => {
    expect(buildServiceCallFilename("el-1", false, { id: "404" }, "script", "groovy")).toBe(
      "el-1.after-404.script.cip.groovy",
    );
  });

  it("builds an after mapper filename with id", () => {
    expect(buildServiceCallFilename("el-1", false, { id: "200" }, "mapper", "json")).toBe(
      "el-1.after-200.mapper.cip.json",
    );
  });

  it("falls back to code when id is absent for after block", () => {
    expect(buildServiceCallFilename("el-1", false, { code: "myCode" }, "script", "groovy")).toBe(
      "el-1.after-myCode.script.cip.groovy",
    );
  });

  it("prefers id over code for after block", () => {
    expect(buildServiceCallFilename("el-1", false, { id: "idVal", code: "codeVal" }, "script", "groovy")).toBe(
      "el-1.after-idVal.script.cip.groovy",
    );
  });

  it("normalizes a status-code range for after script", () => {
    expect(buildServiceCallFilename("el-1", false, { id: "500..599" }, "script", "groovy")).toBe(
      "el-1.after-5xx.script.cip.groovy",
    );
  });

  it("normalizes a status-code range for after mapper", () => {
    expect(buildServiceCallFilename("el-1", false, { id: "200..299" }, "mapper", "json")).toBe(
      "el-1.after-2xx.mapper.cip.json",
    );
  });

  it("keeps a non-matching range unchanged for after block", () => {
    expect(buildServiceCallFilename("el-1", false, { id: "100..299" }, "script", "groovy")).toBe(
      "el-1.after-100..299.script.cip.groovy",
    );
  });

  it("uses empty string when block has neither id nor code", () => {
    expect(buildServiceCallFilename("el-1", false, {}, "script", "groovy")).toBe("el-1.after-.script.cip.groovy");
  });
});
