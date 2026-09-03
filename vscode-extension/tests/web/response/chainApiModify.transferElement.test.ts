import { Uri } from "vscode";
import type {
  Element as ElementSchema,
  DataType,
} from "@netcracker/qip-schemas";
import {
  getElement,
  getLibraryElementByType,
  getMainChain,
} from "../../../src/web/response/chainApiRead";
import { transferElement } from "../../../src/web/response/chainApiModify";
import { fileApi } from "../../../src/web/response/file";

jest.mock("../../../src/web/response/chainApiRead", () => ({
  getElement: jest.fn(),
  getLibraryElementByType: jest.fn(),
  getMainChain: jest.fn(),
}));

jest.mock("../../../src/web/response/file", () => ({
  fileApi: {
    writeMainChain: jest.fn(),
  },
}));

describe("transferElement dependency validation", () => {
  const fileUri = { path: "/workspace/test" } as Uri;
  const chainId = "chain-id";
  const movedElementId = "moved-element";
  let consoleErrorSpy: jest.SpyInstance;

  beforeEach(() => {
    jest.clearAllMocks();
    consoleErrorSpy = jest.spyOn(console, "error").mockImplementation();
    (getLibraryElementByType as jest.Mock).mockResolvedValue(undefined);
    (getElement as jest.Mock).mockImplementation(
      async (_fileUri, _chainId, elementId) => ({ id: elementId }),
    );
  });

  afterEach(() => {
    consoleErrorSpy.mockRestore();
  });

  it("transfers an element with an external dependency from a group container to the canvas", async () => {
    const { chain, movedElement, sourceParent } =
      createTransferFixture("container");
    (getMainChain as jest.Mock).mockResolvedValue(chain);

    await expect(
      transferElement(fileUri, chainId, {
        parentId: null,
        elements: [movedElementId],
        swimlaneId: null,
      }),
    ).resolves.toEqual({
      updatedElements: [{ id: movedElementId }],
    });

    expect(sourceParent.children).toEqual([]);
    expect(chain.content.elements).toContain(movedElement);
    expect(fileApi.writeMainChain).toHaveBeenCalledWith(fileUri, chain);
  });

  it("transfers an element with an external dependency between group containers", async () => {
    const { chain, movedElement, sourceParent, targetParent } =
      createTransferFixture("container", "container");
    (getMainChain as jest.Mock).mockResolvedValue(chain);

    await expect(
      transferElement(fileUri, chainId, {
        parentId: targetParent?.id ?? null,
        elements: [movedElementId],
        swimlaneId: null,
      }),
    ).resolves.toEqual({
      updatedElements: [{ id: movedElementId }],
    });

    expect(sourceParent.children).toEqual([]);
    expect(targetParent?.children).toEqual([movedElement]);
    expect(fileApi.writeMainChain).toHaveBeenCalledWith(fileUri, chain);
  });

  it("transfers an element with an external dependency from the canvas into a group container", async () => {
    const movedElement = createElement(movedElementId, "log");
    const externalElement = createElement("external-element", "trigger");
    const targetParent = createElement("target-parent", "container");
    const chain = {
      id: chainId,
      content: {
        elements: [externalElement, movedElement, targetParent],
        dependencies: [
          {
            id: "dependency-id",
            from: externalElement.id,
            to: movedElement.id,
          },
        ],
      },
    };
    (getMainChain as jest.Mock).mockResolvedValue(chain);

    await expect(
      transferElement(fileUri, chainId, {
        parentId: targetParent.id,
        elements: [movedElementId],
        swimlaneId: null,
      }),
    ).resolves.toEqual({
      updatedElements: [{ id: movedElementId }],
    });

    expect(targetParent.children).toEqual([movedElement]);
    expect(fileApi.writeMainChain).toHaveBeenCalledWith(fileUri, chain);
  });

  it("rejects an element with an external dependency moved out of an element group", async () => {
    const { chain } = createTransferFixture("split");
    (getMainChain as jest.Mock).mockResolvedValue(chain);

    await expect(
      transferElement(fileUri, chainId, {
        parentId: null,
        elements: [movedElementId],
        swimlaneId: null,
      }),
    ).rejects.toThrow("outside dependencies");

    expect(fileApi.writeMainChain).not.toHaveBeenCalled();
  });

  it("rejects an element with an external dependency moved into an element group", async () => {
    const { chain, targetParent } = createTransferFixture("container", "split");
    (getMainChain as jest.Mock).mockResolvedValue(chain);

    await expect(
      transferElement(fileUri, chainId, {
        parentId: targetParent?.id ?? null,
        elements: [movedElementId],
        swimlaneId: null,
      }),
    ).rejects.toThrow("outside dependencies");

    expect(fileApi.writeMainChain).not.toHaveBeenCalled();
  });

  it("validates dependencies for a batch moved from mixed group types", async () => {
    const movedFromGroupContainer = createElement(movedElementId, "log");
    const movedFromElementGroup = createElement("other-moved-element", "log");
    const externalElement = createElement("external-element", "trigger");
    const groupContainer = createElement("group-container", "container", [
      movedFromGroupContainer,
    ]);
    const elementGroup = createElement("element-group", "split", [
      movedFromElementGroup,
    ]);
    const chain = {
      id: chainId,
      content: {
        elements: [externalElement, groupContainer, elementGroup],
        dependencies: [
          {
            id: "dependency-id",
            from: externalElement.id,
            to: movedFromGroupContainer.id,
          },
        ],
      },
    };
    (getMainChain as jest.Mock).mockResolvedValue(chain);

    await expect(
      transferElement(fileUri, chainId, {
        parentId: null,
        elements: [movedFromGroupContainer.id, movedFromElementGroup.id],
        swimlaneId: null,
      }),
    ).rejects.toThrow("outside dependencies");

    expect(fileApi.writeMainChain).not.toHaveBeenCalled();
  });

  function createTransferFixture(
    sourceParentType: string,
    targetParentType?: string,
  ) {
    const movedElement = createElement(movedElementId, "log");
    const externalElement = createElement("external-element", "trigger");
    const sourceParent = createElement("source-parent", sourceParentType, [
      movedElement,
    ]);
    const targetParent = targetParentType
      ? createElement("target-parent", targetParentType)
      : undefined;

    const elements = [externalElement, sourceParent];
    if (targetParent) {
      elements.push(targetParent);
    }

    return {
      chain: {
        id: chainId,
        content: {
          elements,
          dependencies: [
            {
              id: "dependency-id",
              from: externalElement.id,
              to: movedElement.id,
            },
          ],
        },
      },
      movedElement,
      sourceParent,
      targetParent,
    };
  }

  function createElement(
    id: string,
    type: string,
    children: ElementSchema[] = [],
  ): ElementSchema {
    return {
      id,
      name: id,
      type: type as unknown as DataType,
      properties: {},
      children,
    };
  }
});
