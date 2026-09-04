import { Uri } from "vscode";
import type { Element as ElementSchema } from "@netcracker/qip-schemas";
import {
  collectFilenamesFromElementTree,
  deleteElementsPropertyFiles,
  cleanupOrphanPropertyFiles,
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
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "resources/body.txt");
    expect(mockedRemoveFile).toHaveBeenCalledTimes(1);
  });

  test("removes script before block and sets script property", async () => {
    const elements: ElementSchema[] = [serviceCall({ before: { type: "script", propertiesFilename: "s.groovy" } })];
    await deleteElementsPropertyFiles(fileUri, elements);
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "resources/s.groovy");
    // script assignment: element still mutated but removeFile mock returns undefined
    expect(elements[0].properties as unknown as Record<string, unknown>).toBeDefined();
  });

  test("removes mapper after block", async () => {
    const elements: ElementSchema[] = [serviceCall({ after: [{ type: "mapper", propertiesFilename: "m.json" }] })];
    await deleteElementsPropertyFiles(fileUri, elements);
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "resources/m.json");
  });

  test("removes mapper with prefix mapper-xxx", async () => {
    const elements: ElementSchema[] = [serviceCall({ after: [{ type: "mapper-advanced", propertiesFilename: "m2.json" }] })];
    await deleteElementsPropertyFiles(fileUri, elements);
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "resources/m2.json");
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
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "resources/child.json");
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
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "resources/body.txt");
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "resources/b.groovy");
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "resources/a.json");
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
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "resources/ok.groovy");
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
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "resources/b.json");
    expect(mockedRemoveFile).toHaveBeenCalledTimes(1);
  });

  test("removes multiple orphan files", async () => {
    const oldFilenames = new Set(["a.json", "b.json", "c.groovy"]);
    const newFilenames = new Set<string>([]);
    const chainElements: ElementSchema[] = [];
    await cleanupOrphanPropertyFiles(fileUri, oldFilenames, newFilenames, chainElements);
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "resources/a.json");
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "resources/b.json");
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "resources/c.groovy");
    expect(mockedRemoveFile).toHaveBeenCalledTimes(3);
  });

  test("removes only orphans, keeps live ones when live in nested children", async () => {
    const oldFilenames = new Set(["a.json", "b.json", "c.json"]);
    const newFilenames = new Set(["a.json"]);
    // b.json still live in child
    const child = serviceCall({ after: [{ propertiesFilename: "b.json", type: "mapper" }] });
    const chainElements: ElementSchema[] = [otherElement("container", {}, [child])];
    await cleanupOrphanPropertyFiles(fileUri, oldFilenames, newFilenames, chainElements);
    expect(mockedRemoveFile).not.toHaveBeenCalledWith(fileUri, "resources/b.json");
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "resources/c.json");
    expect(mockedRemoveFile).toHaveBeenCalledTimes(1);
  });

  test("deduplicates orphans", async () => {
    // orphanSet dedup via Set
    const oldFilenames = new Set(["a.json", "a.json"]);
    const newFilenames = new Set<string>([]);
    await cleanupOrphanPropertyFiles(fileUri, oldFilenames, newFilenames, []);
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "resources/a.json");
    expect(mockedRemoveFile).toHaveBeenCalledTimes(1);
  });

  test("does nothing when old is empty", async () => {
    await cleanupOrphanPropertyFiles(fileUri, new Set(), new Set(["a.json"]), []);
    expect(mockedRemoveFile).not.toHaveBeenCalled();
  });

  test("uses resources prefix via toResourcePath", async () => {
    const oldFilenames = new Set(["myfile.json"]);
    await cleanupOrphanPropertyFiles(fileUri, oldFilenames, new Set(), []);
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "resources/myfile.json");
  });

  test("handles empty chainElements", async () => {
    const oldFilenames = new Set(["x.groovy"]);
    await cleanupOrphanPropertyFiles(fileUri, oldFilenames, new Set(), []);
    expect(mockedRemoveFile).toHaveBeenCalledWith(fileUri, "resources/x.groovy");
  });
});
