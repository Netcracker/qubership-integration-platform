import { Uri } from "vscode";
import { updateElement } from "../../../src/web/response/chainApiModify";
import { getMainChain, getElement, getLibraryElementByType } from "../../../src/web/response/chainApiRead";
import { fileApi } from "../../../src/web/response/file";

jest.mock("../../../src/web/response/chainApiRead", () => ({
  getMainChain: jest.fn(),
  getElement: jest.fn(),
  getLibraryElementByType: jest.fn(),
  getDependencyId: jest.fn((d: any) => `${d.from}-${d.to}`),
  getMaskedField: jest.fn(),
  parseElement: jest.fn(),
  parseMaskedField: jest.fn(),
}));

jest.mock("../../../src/web/response/file", () => ({
  fileApi: {
    writeMainChain: jest.fn().mockResolvedValue(undefined),
    writePropertyFile: jest.fn().mockResolvedValue(undefined),
    removeFile: jest.fn().mockResolvedValue(undefined),
    getMainChain: jest.fn(),
    getDirectoriesToRemove: jest.fn(),
    deleteFile: jest.fn(),
  },
}));

jest.mock("../../../src/web/api-services/OrderedElementService", () => ({
  OrderedElementService: jest.fn().mockImplementation(() => ({
    updateProperties: jest.fn().mockResolvedValue(undefined),
    updatePriority: jest.fn().mockResolvedValue(undefined),
  })),
}));

const getMainChainMock = getMainChain as jest.Mock;
const getElementMock = getElement as jest.Mock;
const getLibraryElementByTypeMock = getLibraryElementByType as jest.Mock;
const writeMainChainMock = fileApi.writeMainChain as jest.Mock;
const writePropertyFileMock = fileApi.writePropertyFile as jest.Mock;
const removeFileMock = fileApi.removeFile as jest.Mock;

const fileUri = { path: "/workspace/chain-1.chain.qip.yaml" } as Uri;
const chainId = "chain-1";

function libraryFor(type: string) {
  const base: any = {
    name: type,
    title: type,
    type,
    container: false,
    allowedChildren: {},
    allowedInContainers: true,
    parentRestriction: [],
    outputEnabled: true,
    inputEnabled: true,
    inputQuantity: "any",
    ordered: false,
    properties: { common: [], advanced: [], hidden: [], unknown: [] },
  };
  if (type === "container") {
    return { ...base, container: true, allowedChildren: {} };
  }
  return base;
}

function makeElement(overrides: any = {}) {
  return {
    id: "el-1",
    name: "Element 1",
    description: "",
    type: "http-trigger" as any,
    properties: {},
    ...overrides,
  };
}

function makeChain(elements: any[], dependencies: any[] = []) {
  return {
    id: chainId,
    name: "Chain",
    content: { elements, dependencies },
    metaInfo: {},
  } as any;
}

beforeEach(() => {
  jest.clearAllMocks();
  getLibraryElementByTypeMock.mockImplementation(async (type: string) => libraryFor(type));
  getElementMock.mockImplementation(async (_uri: any, _cId: any, elementId: string) => ({
    id: elementId,
    name: "updated",
    type: "http-trigger",
    properties: {},
  }));
  writeMainChainMock.mockResolvedValue(undefined);
  writePropertyFileMock.mockResolvedValue(undefined);
  removeFileMock.mockResolvedValue(undefined);
});

describe("updateElement – chain and element validation", () => {
  it("throws when chainId does not match", async () => {
    getMainChainMock.mockResolvedValue(makeChain([]));
    (makeChain([]) as any).id = "other";
    getMainChainMock.mockResolvedValue({ id: "other", content: { elements: [] } });

    await expect(updateElement(fileUri, chainId, "el-1", { name: "x", description: "", parentElementId: undefined, properties: {} } as any)).rejects.toThrow("ChainId mismatch");
  });

  it("throws when element not found", async () => {
    getMainChainMock.mockResolvedValue(makeChain([]));

    await expect(updateElement(fileUri, chainId, "missing", { name: "x", description: "", parentElementId: undefined, properties: {} } as any)).rejects.toThrow("ElementId not found");
  });

  it("throws when parent not found (getParentElementForUpdate)", async () => {
    const el = makeElement({ id: "el-1", type: "http-trigger" });
    getMainChainMock.mockResolvedValue(makeChain([el]));

    await expect(
      updateElement(fileUri, chainId, "el-1", { name: "x", description: "", parentElementId: "missing-parent", properties: {} } as any),
    ).rejects.toThrow("Parent ElementId not found");
  });
});

describe("updateElement – parent reattachment (getParentElementForUpdate / reattachElementOnUpdate)", () => {
  it("does not move element when parent unchanged (isChangeParent false)", async () => {
    const el = makeElement({ id: "el-1", type: "http-trigger" });
    const chain = makeChain([el]);
    getMainChainMock.mockResolvedValue(chain);

    await updateElement(fileUri, chainId, "el-1", { name: "new name", description: "desc", parentElementId: undefined, properties: {} } as any);

    // element stays at root, not duplicated
    expect(chain.content.elements).toHaveLength(1);
    expect(chain.content.elements[0].id).toBe("el-1");
    expect(writeMainChainMock).toHaveBeenCalled();
  });

  it("moves element to new container parent when parent changes", async () => {
    const el = makeElement({ id: "el-1", type: "http-trigger" });
    const container = makeElement({ id: "cont-1", type: "container", children: [] });
    const chain = makeChain([el, container]);
    getMainChainMock.mockResolvedValue(chain);

    await updateElement(fileUri, chainId, "el-1", { name: "new name", description: "", parentElementId: "cont-1", properties: {} } as any);

    expect(chain.content.elements).toHaveLength(1);
    expect(chain.content.elements[0].id).toBe("cont-1");
    expect((chain.content.elements[0].children as any[])[0].id).toBe("el-1");
    expect((chain.content.elements[0].children as any[])[0].parentElementId).toBe("cont-1");
  });

  it("moves element from container back to root (parentElement undefined)", async () => {
    const inner = makeElement({ id: "el-1", type: "http-trigger", parentElementId: "cont-1" });
    const container = makeElement({ id: "cont-1", type: "container", children: [inner] });
    // findElementById will locate el-1 under cont-1 with parentId cont-1
    const chain = makeChain([container]);
    getMainChainMock.mockResolvedValue(chain);

    await updateElement(fileUri, chainId, "el-1", { name: "new name", description: "", parentElementId: undefined, properties: {} } as any);

    // after move, container should have no children, root should contain el-1
    expect(container.children).toHaveLength(0);
    expect(chain.content.elements).toContainEqual(expect.objectContaining({ id: "el-1" }));
  });

  it("initializes parent children array when undefined", async () => {
    const el = makeElement({ id: "el-1", type: "http-trigger" });
    const container = makeElement({ id: "cont-1", type: "container" }); // children undefined
    delete (container as any).children;
    const chain = makeChain([el, container]);
    getMainChainMock.mockResolvedValue(chain);

    await updateElement(fileUri, chainId, "el-1", { name: "x", description: "", parentElementId: "cont-1", properties: {} } as any);

    expect((container as any).children).toEqual([expect.objectContaining({ id: "el-1" })]);
  });

  it("returns parent undefined when parentElementId is empty", async () => {
    const el = makeElement({ id: "el-1" });
    const chain = makeChain([el]);
    getMainChainMock.mockResolvedValue(chain);

    await updateElement(fileUri, chainId, "el-1", { name: "x", description: "", parentElementId: undefined, properties: {} } as any);

    expect(chain.content.elements[0].name).toBe("x");
  });
});

describe("updateElement – generic file name generation (getOrCreatePropertyFilename → buildCipFilename)", () => {
  it("generates a new cip filename for a single mappingDescription on a mapper type", async () => {
    const el = makeElement({ id: "el-1", type: "mapper-custom", properties: {} });
    const chain = makeChain([el]);
    getMainChainMock.mockResolvedValue(chain);

    await updateElement(fileUri, chainId, "el-1", {
      name: "x",
      description: "",
      parentElementId: undefined,
      properties: {
        propertiesToExportInSeparateFile: "mappingDescription",
        exportFileExtension: "json",
        mappingDescription: '{"a":1}',
      },
    } as any);

    expect(writePropertyFileMock).toHaveBeenCalledWith(fileUri, "el-1.element.mapper.cip.json", JSON.stringify({ mappingDescription: '{"a":1}' }, null, 2));
    // original property removed after write
    expect((el.properties as any).mappingDescription).toBeUndefined();
    expect((el.properties as any).propertiesFilename).toBe("el-1.element.mapper.cip.json");
  });

  it("preserves existing generic filename (ResourceFileNames)", async () => {
    const el = makeElement({
      id: "el-1",
      type: "http-trigger",
      properties: {
        propertiesFilename: "el-1.element.mapper.cip.json",
        propertiesToExportInSeparateFile: "mappingDescription",
        exportFileExtension: "json",
        mappingDescription: "old",
      },
    });
    const chain = makeChain([el]);
    getMainChainMock.mockResolvedValue(chain);

    await updateElement(fileUri, chainId, "el-1", {
      name: "x",
      description: "",
      parentElementId: undefined,
      properties: {
        propertiesToExportInSeparateFile: "mappingDescription",
        exportFileExtension: "json",
        mappingDescription: "new",
      },
    } as any);

    expect(writePropertyFileMock).toHaveBeenCalledWith(fileUri, "el-1.element.mapper.cip.json", expect.any(String));
    expect(writePropertyFileMock).not.toHaveBeenCalledWith(fileUri, expect.stringContaining("keep"), expect.anything());
  });

  it("generates script kind for single script property on non-mapper type", async () => {
    const el = makeElement({ id: "el-7", type: "http-sender", properties: {} });
    const chain = makeChain([el]);
    getMainChainMock.mockResolvedValue(chain);

    await updateElement(fileUri, chainId, "el-7", {
      name: "x",
      description: "",
      parentElementId: undefined,
      properties: {
        propertiesToExportInSeparateFile: "script",
        exportFileExtension: "groovy",
        script: "println 1",
      },
    } as any);

    expect(writePropertyFileMock).toHaveBeenCalledWith(fileUri, "el-7.element.script.cip.groovy", "println 1");
  });

  it("preserves custom single property name for non-mapper type", async () => {
    const el = makeElement({ id: "el-7", type: "http-sender", properties: {} });
    const chain = makeChain([el]);
    getMainChainMock.mockResolvedValue(chain);

    await updateElement(fileUri, chainId, "el-7", {
      name: "x",
      description: "",
      parentElementId: undefined,
      properties: {
        propertiesToExportInSeparateFile: "myProp",
        exportFileExtension: "json",
        myProp: "value",
      },
    } as any);

    expect(writePropertyFileMock).toHaveBeenCalledWith(fileUri, "el-7.element.myProp.cip.json", JSON.stringify({ myProp: "value" }, null, 2));
  });

  it("collapses multiple properties on non-mapper to properties kind", async () => {
    const el = makeElement({ id: "el-1", type: "service-call", properties: {} });
    const chain = makeChain([el]);
    getMainChainMock.mockResolvedValue(chain);

    await updateElement(fileUri, chainId, "el-1", {
      name: "x",
      description: "",
      parentElementId: undefined,
      properties: {
        propertiesToExportInSeparateFile: "a, b",
        exportFileExtension: "json",
        a: "1",
        b: "2",
      },
    } as any);

    expect(writePropertyFileMock).toHaveBeenCalledWith(fileUri, "el-1.element.properties.cip.json", JSON.stringify({ a: "1", b: "2" }, null, 2));
    expect((el.properties as any).a).toBeUndefined();
    expect((el.properties as any).b).toBeUndefined();
  });

  it("collapses multiple properties on mapper type to mapper kind", async () => {
    const el = makeElement({ id: "el-1", type: "mapper", properties: {} });
    const chain = makeChain([el]);
    getMainChainMock.mockResolvedValue(chain);

    await updateElement(fileUri, chainId, "el-1", {
      name: "x",
      description: "",
      parentElementId: undefined,
      properties: {
        propertiesToExportInSeparateFile: "a, b",
        exportFileExtension: "json",
        a: "1",
        b: "2",
      },
    } as any);

    expect(writePropertyFileMock).toHaveBeenCalledWith(fileUri, "el-1.element.mapper.cip.json", expect.any(String));
  });

  it("writes single non-json property as raw string", async () => {
    const el = makeElement({ id: "el-1", type: "http-trigger", properties: {} });
    const chain = makeChain([el]);
    getMainChainMock.mockResolvedValue(chain);

    await updateElement(fileUri, chainId, "el-1", {
      name: "x",
      description: "",
      parentElementId: undefined,
      properties: {
        propertiesToExportInSeparateFile: "body",
        exportFileExtension: "txt",
        body: "hello world",
      },
    } as any);

    expect(writePropertyFileMock).toHaveBeenCalledWith(fileUri, "el-1.element.body.cip.txt", "hello world");
  });
});

describe("updateElement – service-call before/after handling (buildServiceCallFilename)", () => {
  it("creates a before script filename with cip scheme", async () => {
    const el = makeElement({
      id: "el-1",
      type: "service-call",
      properties: {},
    });
    const chain = makeChain([el]);
    getMainChainMock.mockResolvedValue(chain);

    await updateElement(fileUri, chainId, "el-1", {
      name: "x",
      description: "",
      parentElementId: undefined,
      properties: {
        before: { type: "script", script: "groovy code", id: "before-id" },
      },
    } as any);

    expect(writePropertyFileMock).toHaveBeenCalledWith(fileUri, "el-1.before.script.cip.groovy", "groovy code");
    const storedBefore = (el.properties as any).before;
    expect(storedBefore.propertiesFilename).toBe("el-1.before.script.cip.groovy");
    expect(storedBefore.script).toBeUndefined();
  });

  it("preserves existing before filename", async () => {
    const el = makeElement({
      id: "el-1",
      type: "service-call",
      properties: {
        before: { type: "script", propertiesFilename: "keep-before.groovy", script: "old" },
      },
    });
    const chain = makeChain([el]);
    getMainChainMock.mockResolvedValue(chain);

    await updateElement(fileUri, chainId, "el-1", {
      name: "x",
      description: "",
      parentElementId: undefined,
      properties: {
        before: { type: "script", script: "new code", id: "before-id" },
      },
    } as any);

    expect(writePropertyFileMock).toHaveBeenCalledWith(fileUri, "keep-before.groovy", "new code");
  });

  it("creates an after script filename with id", async () => {
    const el = makeElement({ id: "el-1", type: "service-call", properties: {} });
    const chain = makeChain([el]);
    getMainChainMock.mockResolvedValue(chain);

    await updateElement(fileUri, chainId, "el-1", {
      name: "x",
      description: "",
      parentElementId: undefined,
      properties: {
        after: [{ type: "script", id: "404", script: "code404" }],
      },
    } as any);

    expect(writePropertyFileMock).toHaveBeenCalledWith(fileUri, "el-1.after-404.script.cip.groovy", "code404");
  });

  it("normalizes a status-code range for after mapper (200..299 → 2xx)", async () => {
    const el = makeElement({ id: "el-1", type: "service-call", properties: {} });
    const chain = makeChain([el]);
    getMainChainMock.mockResolvedValue(chain);

    await updateElement(fileUri, chainId, "el-1", {
      name: "x",
      description: "",
      parentElementId: undefined,
      properties: {
        after: [{ type: "mapper-advanced", id: "200..299", mappingDescription: '{"a":1}' }],
      },
    } as any);

    expect(writePropertyFileMock).toHaveBeenCalledWith(fileUri, "el-1.after-2xx.mapper.cip.json", JSON.stringify({ mappingDescription: '{"a":1}' }, null, 2));
    const storedAfter = (el.properties as any).after[0];
    expect(storedAfter.propertiesFilename).toBe("el-1.after-2xx.mapper.cip.json");
    expect(storedAfter.mappingDescription).toBeUndefined();
  });

  it("falls back to code when id is absent for after block", async () => {
    const el = makeElement({ id: "el-1", type: "service-call", properties: {} });
    const chain = makeChain([el]);
    getMainChainMock.mockResolvedValue(chain);

    await updateElement(fileUri, chainId, "el-1", {
      name: "x",
      description: "",
      parentElementId: undefined,
      properties: {
        after: [{ type: "script", code: "myCode", script: "code" }],
      },
    } as any);

    expect(writePropertyFileMock).toHaveBeenCalledWith(fileUri, "el-1.after-myCode.script.cip.groovy", "code");
  });

  it("keeps a non-matching range unchanged for after block", async () => {
    const el = makeElement({ id: "el-1", type: "service-call", properties: {} });
    const chain = makeChain([el]);
    getMainChainMock.mockResolvedValue(chain);

    await updateElement(fileUri, chainId, "el-1", {
      name: "x",
      description: "",
      parentElementId: undefined,
      properties: {
        after: [{ type: "script", id: "100..299", script: "code" }],
      },
    } as any);

    expect(writePropertyFileMock).toHaveBeenCalledWith(fileUri, "el-1.after-100..299.script.cip.groovy", "code");
  });

  it("preserves existing after filename even when id is a range", async () => {
    const el = makeElement({
      id: "el-1",
      type: "service-call",
      properties: {
        after: [{ type: "mapper-advanced", id: "200..299", propertiesFilename: "keep.json", mappingDescription: "old" }],
      },
    });
    const chain = makeChain([el]);
    getMainChainMock.mockResolvedValue(chain);

    await updateElement(fileUri, chainId, "el-1", {
      name: "x",
      description: "",
      parentElementId: undefined,
      properties: {
        after: [{ type: "mapper-advanced", id: "200..299", mappingDescription: "new" }],
      },
    } as any);

    expect(writePropertyFileMock).toHaveBeenCalledWith(fileUri, "keep.json", expect.any(String));
  });

  it("handles multiple after blocks", async () => {
    const el = makeElement({ id: "el-1", type: "service-call", properties: {} });
    const chain = makeChain([el]);
    getMainChainMock.mockResolvedValue(chain);

    await updateElement(fileUri, chainId, "el-1", {
      name: "x",
      description: "",
      parentElementId: undefined,
      properties: {
        after: [
          { type: "script", id: "404", script: "s1" },
          { type: "mapper-advanced", id: "500..599", mappingDescription: "m1" },
        ],
      },
    } as any);

    expect(writePropertyFileMock).toHaveBeenCalledWith(fileUri, "el-1.after-404.script.cip.groovy", "s1");
    expect(writePropertyFileMock).toHaveBeenCalledWith(fileUri, "el-1.after-5xx.mapper.cip.json", JSON.stringify({ mappingDescription: "m1" }, null, 2));
  });

  it("throws for deprecated mapper type", async () => {
    const el = makeElement({ id: "el-1", type: "service-call", properties: {} });
    const chain = makeChain([el]);
    getMainChainMock.mockResolvedValue(chain);

    await expect(
      updateElement(fileUri, chainId, "el-1", {
        name: "x",
        description: "",
        parentElementId: undefined,
        properties: {
          after: [{ type: "mapper", id: "404", mappingDescription: "x" }],
        },
      } as any),
    ).rejects.toThrow("Deprecated Mapper element is not supported");
  });

  it("ignores unsupported before/after types (no file write)", async () => {
    const el = makeElement({ id: "el-1", type: "service-call", properties: {} });
    const chain = makeChain([el]);
    getMainChainMock.mockResolvedValue(chain);

    await updateElement(fileUri, chainId, "el-1", {
      name: "x",
      description: "",
      parentElementId: undefined,
      properties: {
        before: { type: "unknown", script: "x" },
        after: [{ type: "unknown", id: "404", script: "y" }],
      },
    } as any);

    expect(writePropertyFileMock).not.toHaveBeenCalled();
  });
});

describe("updateElement – writeElementProperties integration and orphan cleanup", () => {
  it("does not write property file when no propertiesToExportInSeparateFile and not service-call", async () => {
    const el = makeElement({ id: "el-1", type: "http-trigger", properties: { some: "value" } });
    const chain = makeChain([el]);
    getMainChainMock.mockResolvedValue(chain);

    await updateElement(fileUri, chainId, "el-1", {
      name: "x",
      description: "",
      parentElementId: undefined,
      properties: { some: "new" },
    } as any);

    expect(writePropertyFileMock).not.toHaveBeenCalled();
  });

  it("writes generic and service-call files together, and cleans up orphans", async () => {
    const el = makeElement({
      id: "el-1",
      type: "service-call",
      properties: {
        propertiesFilename: "el-1.element.properties.cip.json",
        propertiesToExportInSeparateFile: "a, b",
        exportFileExtension: "json",
        a: "1",
        b: "2",
        before: { type: "script", propertiesFilename: "el-1.before.script.cip.groovy", script: "old" },
        after: [{ type: "script", id: "404", propertiesFilename: "el-1.after-404.script.cip.groovy", script: "old404" }],
      },
    });
    const chain = makeChain([el]);
    getMainChainMock.mockResolvedValue(chain);

    // Update with different after id, so old after filename becomes orphan.
    // Provide before with existing filename to preserve, but after with new id.
    await updateElement(fileUri, chainId, "el-1", {
      name: "x",
      description: "",
      parentElementId: undefined,
      properties: {
        propertiesToExportInSeparateFile: "a, b",
        exportFileExtension: "json",
        a: "1",
        b: "2",
        before: { type: "script", script: "newBefore", id: "b" },
        after: [{ type: "script", id: "500", script: "new500" }],
      },
    } as any);

    // generic preserved
    expect(writePropertyFileMock).toHaveBeenCalledWith(fileUri, "el-1.element.properties.cip.json", expect.any(String));
    // before preserved (existing before filename)
    expect(writePropertyFileMock).toHaveBeenCalledWith(fileUri, "el-1.before.script.cip.groovy", "newBefore");
    // new after file
    expect(writePropertyFileMock).toHaveBeenCalledWith(fileUri, "el-1.after-500.script.cip.groovy", "new500");
    // orphan cleanup: old after-404 should be removed (oldFilenames had it, newFilenames has after-500, live chain has after-500 not after-404)
    expect(removeFileMock).toHaveBeenCalledWith(fileUri, "el-1.after-404.script.cip.groovy");
  });

  it("handles non-service-call element with generic file only (no before/after)", async () => {
    const el = makeElement({ id: "el-1", type: "http-trigger", properties: {} });
    const chain = makeChain([el]);
    getMainChainMock.mockResolvedValue(chain);

    await updateElement(fileUri, chainId, "el-1", {
      name: "x",
      description: "",
      parentElementId: undefined,
      properties: {
        propertiesToExportInSeparateFile: "body",
        exportFileExtension: "txt",
        body: "txt body",
      },
    } as any);

    expect(writePropertyFileMock).toHaveBeenCalledTimes(1);
    expect(writePropertyFileMock).toHaveBeenCalledWith(fileUri, "el-1.element.body.cip.txt", "txt body");
  });

  it("covers 500..599 normalization in after block via updateElement", async () => {
    const el = makeElement({ id: "el-1", type: "service-call", properties: {} });
    const chain = makeChain([el]);
    getMainChainMock.mockResolvedValue(chain);

    await updateElement(fileUri, chainId, "el-1", {
      name: "x",
      description: "",
      parentElementId: undefined,
      properties: {
        after: [{ type: "script", id: "500..599", script: "code" }],
      },
    } as any);

    expect(writePropertyFileMock).toHaveBeenCalledWith(fileUri, "el-1.after-5xx.script.cip.groovy", "code");
  });

  it("merges OrderedElementService diff when it returns updatedElements", async () => {
    const el = makeElement({ id: "el-1", type: "http-trigger", properties: {} });
    const chain = makeChain([el]);
    getMainChainMock.mockResolvedValue(chain);
    getElementMock.mockResolvedValue({ id: "el-1", name: "updated", type: "http-trigger", properties: {} } as any);

    const { OrderedElementService } = await import("../../../src/web/api-services/OrderedElementService");
    (OrderedElementService as unknown as jest.Mock).mockImplementationOnce(() => ({
      updateProperties: jest.fn().mockResolvedValue({ updatedElements: [{ id: "sibling-1", name: "Sibling" }] }),
    }));

    const result = await updateElement(fileUri, chainId, "el-1", {
      name: "x",
      description: "",
      parentElementId: undefined,
      properties: {},
    } as any);

    expect(result.updatedElements).toHaveLength(2);
    expect(result.updatedElements).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ id: "sibling-1" }),
        expect.objectContaining({ id: "el-1" }),
      ]),
    );
  });
});
