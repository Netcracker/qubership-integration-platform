import { Uri } from "vscode";
import {
  createElement,
  transferElement,
} from "../../../src/web/response/chainApiModify";
import { getLibraryElementByType } from "../../../src/web/response/chainApiRead";
import { fileApi } from "../../../src/web/response/file";
import type {
  Chain as ChainSchema,
  DataType,
  Element as ElementSchema,
} from "@netcracker/qip-schemas";
import type { LibraryElement } from "@netcracker/qip-ui";

// Keep the read path real (getMainChain, parseElement, getElement) and mock only
// what reaches the file system, so that reads inside chainApiRead see the mock too.
jest.mock("../../../src/web/response/chainApiRead", () => ({
  ...jest.requireActual("../../../src/web/response/chainApiRead"),
  getLibraryElementByType: jest.fn(),
}));

jest.mock("../../../src/web/response/file", () => ({
  fileApi: {
    getMainChain: jest.fn(),
    writeMainChain: jest.fn(),
  },
}));

const getMainChainMock = fileApi.getMainChain as jest.Mock;
const getLibraryElementByTypeMock = getLibraryElementByType as jest.Mock;
const writeMainChainMock = fileApi.writeMainChain as jest.Mock;

const fileUri = Uri.file("/workspace/chain-1.chain.qip.yaml");
const chainId = "chain-1";

// Trimmed-down library.json entries: only the flags the transfer and create
// paths read.
const library: Record<string, Partial<LibraryElement>> = {
  "http-trigger": { outputEnabled: true },
  "service-call": { inputEnabled: true, outputEnabled: true },
  script: { inputEnabled: true, outputEnabled: true },
  // A container that accepts anything, like reuse does.
  reuse: { container: true, inputEnabled: true, outputEnabled: true },
  // A container that accepts only its own child types.
  "try-catch-finally-2": {
    container: true,
    allowedChildren: { "try-2": "one" } as LibraryElement["allowedChildren"],
    inputEnabled: true,
    outputEnabled: true,
  },
};

const libraryElement = (type: string): LibraryElement =>
  ({
    name: type,
    title: type,
    type,
    container: false,
    ordered: false,
    allowedInContainers: true,
    inputEnabled: false,
    outputEnabled: false,
    inputQuantity: "any",
    parentRestriction: [],
    allowedChildren: {},
    properties: {},
    ...library[type],
  }) as unknown as LibraryElement;

const element = (
  id: string,
  type: string,
  overrides: Partial<ElementSchema> = {},
): ElementSchema => ({
  id,
  name: id,
  description: "",
  type: type as unknown as DataType,
  properties: {},
  ...overrides,
});

const newChain = (
  elements: ElementSchema[],
  dependencies: { from: string; to: string }[] = [],
  content: Record<string, unknown> = {},
): ChainSchema =>
  ({
    $schema: "http://qubership.org/schemas/product/qip/chain.schema.yaml",
    id: chainId,
    name: "Chain 1",
    content: { elements, dependencies, ...content },
  }) as unknown as ChainSchema;

const rootIds = (chain: ChainSchema) =>
  (chain.content.elements as ElementSchema[]).map((e) => e.id);

const childIds = (chain: ChainSchema, parentId: string) =>
  (
    (chain.content.elements as ElementSchema[]).find((e) => e.id === parentId)!
      .children as ElementSchema[]
  )?.map((e) => e.id) ?? [];

beforeEach(() => {
  jest.clearAllMocks();
  getLibraryElementByTypeMock.mockImplementation(async (type: string) =>
    libraryElement(type),
  );
  writeMainChainMock.mockResolvedValue(undefined);
});

describe("createElement with a parent that cannot hold the element", () => {
  it("creates the element next to a plain parent and connects them", async () => {
    const chain = newChain(
      [
        element("call-1", "service-call", { swimlaneId: "swimlane-1" }),
        element("swimlane-1", "swimlane"),
        element("swimlane-default", "swimlane"),
      ],
      [],
      { defaultSwimlaneId: "swimlane-default" },
    );
    getMainChainMock.mockResolvedValue(chain);

    const diff = await createElement(fileUri, chainId, {
      type: "script",
      parentElementId: "call-1",
    });

    const created = diff.createdElements![0];
    expect(childIds(chain, "call-1")).toEqual([]);
    expect(rootIds(chain)).toContain(created.id);
    expect(created.parentElementId).toBeUndefined();
    expect(created.swimlaneId).toBe("swimlane-1");
    expect(diff.createdDependencies).toEqual([
      { from: "call-1", to: created.id, id: `call-1-${created.id}` },
    ]);
  });

  it("connects instead of nesting when the container forbids the type", async () => {
    const chain = newChain([element("try-1", "try-catch-finally-2")]);
    getMainChainMock.mockResolvedValue(chain);

    const diff = await createElement(fileUri, chainId, {
      type: "script",
      parentElementId: "try-1",
    });

    const created = diff.createdElements![0];
    expect(childIds(chain, "try-1")).toEqual([]);
    expect(rootIds(chain)).toContain(created.id);
    expect(diff.createdDependencies).toHaveLength(1);
  });

  it("keeps the derived dependency id out of the chain file", async () => {
    const chain = newChain([element("call-1", "service-call")]);
    getMainChainMock.mockResolvedValue(chain);

    await createElement(fileUri, chainId, {
      type: "script",
      parentElementId: "call-1",
    });

    expect(chain.content.dependencies).toHaveLength(1);
    expect((chain.content.dependencies as any[])[0]).not.toHaveProperty("id");
  });

  it("still nests into a container that allows the type", async () => {
    const chain = newChain([element("reuse-1", "reuse")]);
    getMainChainMock.mockResolvedValue(chain);

    const diff = await createElement(fileUri, chainId, {
      type: "script",
      parentElementId: "reuse-1",
    });

    const created = diff.createdElements![0];
    expect(childIds(chain, "reuse-1")).toEqual([created.id]);
    expect(diff.createdDependencies).toBeUndefined();
  });

  it("rejects a parent that is not in the chain", async () => {
    getMainChainMock.mockResolvedValue(newChain([]));

    await expect(
      createElement(fileUri, chainId, {
        type: "script",
        parentElementId: "missing",
      }),
    ).rejects.toThrow(/missing/);
  });
});

describe("transferElement onto a target that cannot hold the elements", () => {
  it("moves nothing and connects the target to the elements", async () => {
    const chain = newChain([
      element("call-1", "service-call"),
      element("script-1", "script"),
    ]);
    getMainChainMock.mockResolvedValue(chain);

    const diff = await transferElement(fileUri, chainId, {
      elements: ["script-1"],
      parentId: "call-1",
      swimlaneId: null,
    });

    expect(rootIds(chain)).toEqual(["call-1", "script-1"]);
    expect(childIds(chain, "call-1")).toEqual([]);
    expect(diff.updatedElements).toBeUndefined();
    expect(diff.createdDependencies).toEqual([
      { from: "call-1", to: "script-1", id: "call-1-script-1" },
    ]);
  });

  it("skips an element that already has an input dependency", async () => {
    const chain = newChain(
      [
        element("call-1", "service-call"),
        element("script-1", "script"),
        element("script-2", "script"),
      ],
      [{ from: "script-1", to: "script-2" }],
    );
    getMainChainMock.mockResolvedValue(chain);

    const diff = await transferElement(fileUri, chainId, {
      elements: ["script-1", "script-2"],
      parentId: "call-1",
      swimlaneId: null,
    });

    expect(diff.createdDependencies).toEqual([
      { from: "call-1", to: "script-1", id: "call-1-script-1" },
    ]);
  });

  it("rejects a dependency that leaves the transferred elements", async () => {
    const chain = newChain(
      [
        element("call-1", "service-call"),
        element("trigger-1", "http-trigger"),
        element("script-1", "script"),
      ],
      [{ from: "trigger-1", to: "script-1" }],
    );
    getMainChainMock.mockResolvedValue(chain);

    await expect(
      transferElement(fileUri, chainId, {
        elements: ["script-1"],
        parentId: "call-1",
        swimlaneId: null,
      }),
    ).rejects.toThrow(/outside the transferred elements/);
  });

  it("rejects a transfer into the element's own subtree", async () => {
    const chain = newChain([
      element("reuse-1", "reuse", {
        children: [element("script-1", "script")],
      }),
    ]);
    getMainChainMock.mockResolvedValue(chain);

    await expect(
      transferElement(fileUri, chainId, {
        elements: ["reuse-1"],
        parentId: "script-1",
        swimlaneId: null,
      }),
    ).rejects.toThrow("Element cannot be transferred into itself");
  });

  it("still nests when the target is a container that allows the type", async () => {
    const chain = newChain([
      element("reuse-1", "reuse"),
      element("script-1", "script"),
    ]);
    getMainChainMock.mockResolvedValue(chain);

    const diff = await transferElement(fileUri, chainId, {
      elements: ["script-1"],
      parentId: "reuse-1",
      swimlaneId: null,
    });

    expect(rootIds(chain)).toEqual(["reuse-1"]);
    expect(childIds(chain, "reuse-1")).toEqual(["script-1"]);
    expect(diff.createdDependencies).toBeUndefined();
  });
});
