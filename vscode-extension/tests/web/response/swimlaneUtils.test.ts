import {
  createSwimlane,
  enrichElementWithSwimlaneId,
  isTransferOutOfSwimlane,
  SWIMLANE_TYPE_NAME,
  swimlaneValidations,
  transferToSwimlaneValidations,
} from "../../../src/web/response/swimlaneUtils";
import { Uri } from "vscode";
import {
  ActionDifference,
  CreateElementRequest,
  TransferElementRequest,
} from "@netcracker/qip-ui";
import {
  Element as ElementSchema,
  Chain as ChainSchema,
  DataType,
} from "@netcracker/qip-schemas";
import { deleteSwimlane } from "../../../src/web/response/swimlaneUtils";

/* --------------------------- Mocks --------------------------------------- */
jest.mock("../../../src/web/response/chainApiModify", () => ({
  getDefaultElementByType: jest.fn(),
  getDefaultElement: jest.fn(),
  findAndRemoveElementById: jest.fn(),
}));

jest.mock("../../../src/web/response/chainApiUtils", () => ({
  getElementChildren: jest.fn(),
}));

jest.mock("../../../src/web/response/chainApiRead", () => ({
  parseElement: jest.fn(),
  parseElements: jest.fn(),
}));

jest.mock("../../../src/web/response/file", () => ({
  fileApi: {
    writeMainChain: jest.fn(),
  },
}));

jest.mock("../../../src/web/response/chainApiUtils", () => ({
  findElementById: jest.fn(),
  getElementChildren: jest.fn(),
}));

import {
  findAndRemoveElementById,
  getDefaultElement,
  getDefaultElementByType,
} from "../../../src/web/response/chainApiModify";
import { parseElement } from "../../../src/web/response/chainApiRead";
import { parseElements } from "../../../src/web/response/chainApiRead";
import {
  findElementById,
  getElementChildren,
} from "../../../src/web/response/chainApiUtils";
import { fileApi } from "../../../src/web/response/file";

/* --------------------------- Helpers ------------------------------------ */
const mockParseElement = parseElement as jest.MockedFunction<
  typeof parseElement
>;
const mockParseElements = parseElements as jest.MockedFunction<
  typeof parseElements
>;
const mockFindAndRemove = findAndRemoveElementById as jest.MockedFunction<
  typeof findAndRemoveElementById
>;
const mockGetElementChildren = getElementChildren as jest.MockedFunction<
  typeof getElementChildren
>;
const mockGetDefaultElementByType =
  getDefaultElementByType as jest.MockedFunction<
    typeof getDefaultElementByType
  >;
const mockFileApiWriteMainChain = fileApi.writeMainChain as jest.MockedFunction<
  typeof fileApi.writeMainChain
>;
const mockGetDefaultElement = getDefaultElement as jest.MockedFunction<
  typeof getDefaultElement
>;
const mockFindElementById = findElementById as jest.MockedFunction<
  typeof findElementById
>;

const createChain = (overrides?: Partial<ChainSchema>): ChainSchema => ({
  content: {
    defaultSwimlaneId: undefined,
    reuseSwimlaneId: undefined,
    elements: [] as ElementSchema[],
    ...overrides,
  },
  id: "test-chain",
  name: "test-chain",
  $schema: "test-chain",
});

const createSwimlaneElement = (
  id: string,
  chain: ChainSchema,
  name?: string,
): ElementSchema => ({
  id,
  type: SWIMLANE_TYPE_NAME as unknown as DataType,
  name: name ?? "Swimlane",
  chain,
  properties: {},
});

const createDefaultSwimlane = (chain: ChainSchema): ElementSchema => ({
  id: "default-id",
  type: SWIMLANE_TYPE_NAME as unknown as DataType,
  name: "Default swimlane",
  chain,
});

const createElement = (element: ElementSchema, chain: ChainSchema) => ({
  id: element.id,
  name: element.name,
  description: element.id,
  chainId: chain.id,
  type: String(element.type),
  mandatoryChecksPassed: false,
  swimlaneId: element.swimlaneId as string,
  properties: null as any as never,
});

const createTaskElement = (
  id: string,
  swimlaneId: string,
  chain: ChainSchema,
): ElementSchema => ({
  id,
  type: "richText" as unknown as DataType,
  name: "Task",
  swimlaneId,
  chain,
});

const createReuseElement = (id: string, chain: ChainSchema): ElementSchema => ({
  id,
  type: "reuse" as unknown as DataType,
  name: "Reuse",
  chain,
});

/* --------------------------- Test suite ---------------------------------- */
describe("deleteSwimlane", () => {
  let fileUri: Uri;
  let chain: ChainSchema;
  let chainDiff: ActionDifference;

  beforeEach(() => {
    fileUri = {
      uri: "file:///tmp/test",
      fileName: "test",
      basename: "test",
      dirname: "/",
      scheme: "file",
    } as unknown as Uri;
    chain = createChain();
    chainDiff = {
      removedElements: [],
      updatedElements: [],
    } as ActionDifference;
    jest.clearAllMocks();
  });

  describe("default swimlane", () => {
    it("deletes default swimlane when no other swimlanes exist", async () => {
      const defaultSwimlane = createSwimlaneElement("default-id", chain);
      chain.content.defaultSwimlaneId = "default-id";
      chain.content.elements = [defaultSwimlane];

      mockFindAndRemove.mockReturnValue(defaultSwimlane);
      mockParseElement.mockResolvedValue(createElement(defaultSwimlane, chain));

      const result = await deleteSwimlane(fileUri, defaultSwimlane, chain);

      expect(findAndRemoveElementById).toHaveBeenCalledWith(
        chain.content.elements,
        "default-id",
      );
      expect(mockParseElement).toHaveBeenCalledWith(
        fileUri,
        defaultSwimlane,
        chain.id,
      );
      expect(result.removedElements).toHaveLength(1);
      expect(result.updatedElements).toHaveLength(0);
      expect(chain.content.defaultSwimlaneId).toBeUndefined();
    });

    it("throws error when default swimlane exists alongside other swimlanes", async () => {
      const defaultSwimlane = createSwimlaneElement("default-id", chain);
      const otherSwimlane = createSwimlaneElement("other-id", chain);
      chain.content.defaultSwimlaneId = "default-id";
      chain.content.elements = [defaultSwimlane, otherSwimlane];

      await expect(
        deleteSwimlane(fileUri, defaultSwimlane, chain),
      ).rejects.toThrow(
        "Default and Reuse swimlanes cannot be removed if the chain contains other swimlanes",
      );
    });

    it("deletes both default and reuse swimlanes when deleting default", async () => {
      const defaultSwimlane = createSwimlaneElement("default-id", chain);
      const reuseSwimlane = createSwimlaneElement("reuse-id", chain);
      const taskElement = createTaskElement("task-1", "default-id", chain);
      chain.content.defaultSwimlaneId = defaultSwimlane.id;
      chain.content.reuseSwimlaneId = reuseSwimlane.id;
      chain.content.elements = [defaultSwimlane, reuseSwimlane, taskElement];

      mockFindAndRemove
        .mockReturnValueOnce(defaultSwimlane)
        .mockReturnValueOnce(reuseSwimlane);
      mockParseElement
        .mockResolvedValueOnce(createElement(defaultSwimlane, chain))
        .mockResolvedValueOnce(createElement(reuseSwimlane, chain));
      mockParseElements.mockResolvedValue([createElement(taskElement, chain)]);
      mockGetElementChildren.mockReturnValue([]);

      const result = await deleteSwimlane(fileUri, defaultSwimlane, chain);

      expect(result.removedElements).toHaveLength(2);
      expect(result.removedElements?.[0].id).toEqual(defaultSwimlane.id);
      expect(result.removedElements?.[1].id).toEqual(reuseSwimlane.id);

      expect(result.updatedElements).toHaveLength(1);
      expect(result.updatedElements?.[0].id).toEqual(taskElement.id);

      expect(chain.content.defaultSwimlaneId).toBeUndefined();
      expect(chain.content.reuseSwimlaneId).toBeUndefined();
      expect(mockParseElement).toHaveBeenCalledTimes(2);
      expect(mockParseElements).toHaveBeenCalledTimes(1);
    });
  });

  describe("reuse swimlane", () => {
    it("deletes reuse swimlane when no elements belong to it", async () => {
      const reuseSwimlane = createSwimlaneElement("reuse-id", chain);
      chain.content.reuseSwimlaneId = reuseSwimlane.id;
      chain.content.elements = [reuseSwimlane];

      mockFindAndRemove.mockReturnValue(reuseSwimlane);
      mockParseElement.mockResolvedValue(createElement(reuseSwimlane, chain));

      const result = await deleteSwimlane(fileUri, reuseSwimlane, chain);

      expect(findAndRemoveElementById).toHaveBeenCalledWith(
        chain.content.elements,
        reuseSwimlane.id,
      );
      expect(result.removedElements).toHaveLength(1);
      expect(result.updatedElements).toHaveLength(0);
      expect(chain.content.reuseSwimlaneId).toBeUndefined();
    });

    it("triggers default swimlane deletion when reuse swimlane has elements", async () => {
      const defaultSwimlane = createSwimlaneElement("default-id", chain);
      const reuseSwimlane = createSwimlaneElement("reuse-id", chain);
      const taskInReuse = createTaskElement("task-1", reuseSwimlane.id, chain);
      chain.content.defaultSwimlaneId = defaultSwimlane.id;
      chain.content.reuseSwimlaneId = reuseSwimlane.id;
      chain.content.elements = [defaultSwimlane, reuseSwimlane, taskInReuse];

      mockFindAndRemove
        .mockReturnValueOnce(defaultSwimlane)
        .mockReturnValueOnce(reuseSwimlane);
      mockParseElement
        .mockResolvedValueOnce(createElement(defaultSwimlane, chain))
        .mockResolvedValueOnce(createElement(reuseSwimlane, chain));
      mockParseElements.mockResolvedValue([createElement(taskInReuse, chain)]);
      mockGetElementChildren.mockReturnValue([]);

      const result = await deleteSwimlane(fileUri, reuseSwimlane, chain);

      expect(result.removedElements).toHaveLength(2);
      expect(result.removedElements?.[0].id).toEqual(defaultSwimlane.id);
      expect(result.removedElements?.[1].id).toEqual(reuseSwimlane.id);

      expect(result.updatedElements).toHaveLength(1);
      expect(result.updatedElements?.[0].id).toEqual(taskInReuse.id);

      expect(chain.content.defaultSwimlaneId).toBeUndefined();
      expect(chain.content.reuseSwimlaneId).toBeUndefined();
    });
  });

  describe("non-default, non-reuse swimlane", () => {
    it("reassigns elements to default swimlane and deletes the swimlane", async () => {
      const defaultSwimlane = createSwimlaneElement("default-id", chain);
      const otherSwimlane = createSwimlaneElement("other-id", chain);
      const taskInOther = createTaskElement("task-1", otherSwimlane.id, chain);
      chain.content.defaultSwimlaneId = defaultSwimlane.id;
      chain.content.elements = [defaultSwimlane, otherSwimlane, taskInOther];

      mockFindAndRemove.mockReturnValue(otherSwimlane);
      mockParseElement.mockResolvedValue(createElement(otherSwimlane, chain));
      const updatedElement = { ...taskInOther, swimlaneId: defaultSwimlane.id };
      mockParseElements.mockResolvedValue([
        createElement(updatedElement, chain),
      ]);
      mockGetElementChildren.mockReturnValue([]);

      const result = await deleteSwimlane(fileUri, otherSwimlane, chain);

      expect(result.removedElements).toHaveLength(1);
      expect(result.removedElements?.[0].id).toEqual(otherSwimlane.id);

      expect(result.updatedElements).toHaveLength(1);
      expect(result.updatedElements?.[0].id).toEqual(taskInOther.id);
      expect(result.updatedElements?.[0].swimlaneId).toEqual(
        defaultSwimlane.id,
      );
    });
  });
});

const createRequest = (
  overrides?: Partial<CreateElementRequest>,
): CreateElementRequest => ({
  type: SWIMLANE_TYPE_NAME,
  ...overrides,
});

describe("createSwimlane", () => {
  let fileUri: Uri;
  let chain: ChainSchema;

  beforeEach(() => {
    fileUri = {
      uri: "file:///tmp/test",
      fileName: "test",
      basename: "test",
      dirname: "/",
      scheme: "file",
    } as unknown as Uri;
    chain = createChain();
    jest.clearAllMocks();
  });

  describe("when default swimlane already exists", () => {
    it("creates a new swimlane and adds it to the chain", async () => {
      chain.content.defaultSwimlaneId = "existing-default-id";
      const newSwimlane: ElementSchema = createSwimlaneElement(
        "new-swimlane-id",
        chain,
      );
      mockGetDefaultElementByType.mockResolvedValue(newSwimlane);
      mockParseElement.mockResolvedValue(createElement(newSwimlane, chain));

      const request: CreateElementRequest = createRequest();
      const result = await createSwimlane(fileUri, chain, request);

      expect(mockGetDefaultElementByType).toHaveBeenCalledWith(
        chain.id,
        request,
      );
      expect(chain.content.elements).toContain(newSwimlane);
      expect(mockParseElement).toHaveBeenCalledWith(
        fileUri,
        newSwimlane,
        chain.id,
      );
      expect(result.createdElements).toHaveLength(1);
      expect(result.createdElements?.[0].id).toBe(newSwimlane.id);
      expect(result.updatedElements).toHaveLength(0);
      expect(mockFileApiWriteMainChain).toHaveBeenCalledWith(fileUri, chain);
    });
  });

  describe("when default swimlane does not exist", () => {
    it("creates default swimlane with correct name and id when no elements exist", async () => {
      const defaultSwimlane: ElementSchema = createDefaultSwimlane(chain);
      mockGetDefaultElementByType.mockResolvedValue(defaultSwimlane);
      mockParseElement.mockResolvedValue(createElement(defaultSwimlane, chain));

      const request: CreateElementRequest = createRequest();
      const result = await createSwimlane(fileUri, chain, request);

      expect(mockGetDefaultElementByType).toHaveBeenCalledWith(
        chain.id,
        request,
      );
      expect(chain.content.defaultSwimlaneId).toBe(defaultSwimlane.id);
      expect(chain.content.elements).toContain(defaultSwimlane);
      expect(result.createdDefaultSwimlaneId).toBe(defaultSwimlane.id);
      expect(result.createdElements).toHaveLength(1);
      expect(result.updatedElements).toHaveLength(0);
      expect(mockFileApiWriteMainChain).toHaveBeenCalledWith(fileUri, chain);
    });

    it("updates non-reuse elements with new swimlane id when existing elements present", async () => {
      const taskElement = createTaskElement("task-1", "old-swimlane", chain);
      chain.content.elements = [taskElement];

      const defaultSwimlane: ElementSchema = createDefaultSwimlane(chain);
      mockGetDefaultElementByType.mockResolvedValue(defaultSwimlane);
      mockParseElement.mockResolvedValue(createElement(defaultSwimlane, chain));
      const parsedElement = createElement(taskElement, chain);
      parsedElement.swimlaneId = defaultSwimlane.id;
      mockParseElements.mockResolvedValue([parsedElement]);
      mockGetElementChildren.mockReturnValue([]);

      const request: CreateElementRequest = createRequest();
      const result = await createSwimlane(fileUri, chain, request);

      expect(result.createdElements).toHaveLength(1);
      expect(result.updatedElements).toHaveLength(1);
      expect(taskElement.swimlaneId).toBe(defaultSwimlane.id);
      expect(mockParseElements).toHaveBeenCalledTimes(1);
      expect(mockFileApiWriteMainChain).toHaveBeenCalledWith(fileUri, chain);
    });

    it("creates reuse swimlane when reuse elements exist in chain", async () => {
      const reuseElement = createReuseElement("reuse-1", chain);
      chain.content.elements = [reuseElement];

      const defaultSwimlane: ElementSchema = createDefaultSwimlane(chain);
      const reuseSwimlane: ElementSchema = createSwimlaneElement(
        "reuse-swimlane-id",
        chain,
        "Reuse swimlane",
      );

      mockGetDefaultElementByType.mockResolvedValue(defaultSwimlane);
      mockGetDefaultElement.mockResolvedValue(reuseSwimlane);
      mockParseElement
        .mockResolvedValueOnce(createElement(defaultSwimlane, chain))
        .mockResolvedValueOnce(createElement(reuseSwimlane, chain));
      mockParseElements.mockResolvedValue([createElement(reuseElement, chain)]);
      mockGetElementChildren.mockReturnValue([]);

      const request: CreateElementRequest = createRequest();
      const result = await createSwimlane(fileUri, chain, request);

      expect(mockGetDefaultElement).toHaveBeenCalledWith(
        chain.id,
        SWIMLANE_TYPE_NAME,
      );
      expect(chain.content.reuseSwimlaneId).toBe(reuseSwimlane.id);
      expect(chain.content.defaultSwimlaneId).toBe(defaultSwimlane.id);
      expect((reuseSwimlane as any).properties.color).toBe("Green");

      expect(result.createdDefaultSwimlaneId).toBe(defaultSwimlane.id);
      expect(result.createdReuseSwimlaneId).toBe(reuseSwimlane.id);

      expect(result.createdElements).toHaveLength(2); // default + reuse
      expect(result.createdElements?.[0].id).toBe(defaultSwimlane.id);
      expect(result.createdElements?.[1].id).toBe(reuseSwimlane.id);

      expect(result.updatedElements).toHaveLength(1); // reuse element
      expect(result.updatedElements?.[0].id).toBe(reuseElement.id);

      expect(mockFileApiWriteMainChain).toHaveBeenCalledWith(fileUri, chain);
    });
  });
});

describe("enrichElementWithSwimlaneId", () => {
  let fileUri: Uri;
  let chain: ChainSchema;
  let chainDiff: ActionDifference;

  beforeEach(() => {
    fileUri = {
      uri: "file:///tmp/test",
      fileName: "test",
      basename: "test",
      dirname: "/",
      scheme: "file",
    } as unknown as Uri;
    chain = createChain();
    chainDiff = {
      createdElements: [],
      updatedElements: [],
    } as ActionDifference;
    jest.clearAllMocks();
  });

  it("creates reuse swimlane and sets its id to the reuse element when only default swimlane present", async () => {
    const defaultSwimlane: ElementSchema = createDefaultSwimlane(chain);
    chain.content.defaultSwimlaneId = defaultSwimlane.id;
    chain.content.elements = [defaultSwimlane];

    const reuseSwimlane: ElementSchema = createSwimlaneElement(
      "reuse-swimlane-id",
      chain,
      "Reuse swimlane",
    );

    mockGetDefaultElement.mockResolvedValue(reuseSwimlane);
    mockParseElement.mockResolvedValue(createElement(reuseSwimlane, chain));

    const newElement: ElementSchema = {
      id: "reuse-element-1",
      type: "reuse" as unknown as DataType,
      name: "Reuse Element",
      chain,
    };

    const elementRequest: CreateElementRequest = { type: "reuse" };

    await enrichElementWithSwimlaneId(
      fileUri,
      chain,
      elementRequest,
      newElement,
      chainDiff,
    );

    expect(mockGetDefaultElement).toHaveBeenCalledWith(
      chain.id,
      SWIMLANE_TYPE_NAME,
    );
    expect(chain.content.reuseSwimlaneId).toBe(reuseSwimlane.id);
    expect((reuseSwimlane as any).properties.color).toBe("Green");
    expect(newElement.swimlaneId).toBe(reuseSwimlane.id);

    expect(chainDiff.createdReuseSwimlaneId).toBe(reuseSwimlane.id);
    expect(chainDiff.createdElements).toHaveLength(1);
    expect(chainDiff.createdElements?.[0].id).toBe(reuseSwimlane.id);

    expect(mockParseElement).toHaveBeenCalledWith(
      fileUri,
      reuseSwimlane,
      chain.id,
    );
  });

  it("assigns existing reuse swimlane id to the reuse element when reuse swimlane already present", async () => {
    const defaultSwimlane: ElementSchema = createDefaultSwimlane(chain);
    const existingReuseSwimlane: ElementSchema = createSwimlaneElement(
      "existing-reuse-swimlane-id",
      chain,
      "Reuse swimlane",
    );
    chain.content.defaultSwimlaneId = defaultSwimlane.id;
    chain.content.reuseSwimlaneId = existingReuseSwimlane.id;
    chain.content.elements = [defaultSwimlane, existingReuseSwimlane];

    const newElement: ElementSchema = {
      id: "reuse-element-1",
      type: "reuse" as unknown as DataType,
      name: "Reuse Element",
      chain,
    };

    const elementRequest: CreateElementRequest = { type: "reuse" };

    await enrichElementWithSwimlaneId(
      fileUri,
      chain,
      elementRequest,
      newElement,
      chainDiff,
    );

    expect(newElement.swimlaneId).toBe("existing-reuse-swimlane-id");

    expect(mockGetDefaultElement).not.toHaveBeenCalled();
    expect(mockParseElement).not.toHaveBeenCalled();

    expect(chainDiff.createdElements).toHaveLength(0);
    expect(chainDiff.createdReuseSwimlaneId).toBeUndefined();
    expect(chainDiff.updatedElements).toHaveLength(0);
  });

  describe("swimlaneValidations", () => {
    let chain: ChainSchema;

    beforeEach(() => {
      chain = createChain();
      jest.clearAllMocks();
    });

    it("throws error when non-reuse element is added to reuse swimlane without reuse parent", () => {
      const defaultSwimlane = createDefaultSwimlane(chain);
      chain.content.defaultSwimlaneId = defaultSwimlane.id;
      chain.content.reuseSwimlaneId = "reuse-swimlane-id";
      chain.content.elements = [defaultSwimlane];

      const nonReuseElement: ElementSchema = {
        id: "task-1",
        type: "richText" as unknown as DataType,
        name: "Task",
        chain,
      };

      const elementRequest: CreateElementRequest = {
        type: "richText",
        swimlaneId: "reuse-swimlane-id",
      };

      expect(() =>
        swimlaneValidations(chain, nonReuseElement, elementRequest),
      ).toThrow("Only Reuse element can be added to Reuse Swimlane");
    });

    it("does not throw when reuse element is added to reuse swimlane", () => {
      const defaultSwimlane = createDefaultSwimlane(chain);
      chain.content.defaultSwimlaneId = defaultSwimlane.id;
      chain.content.reuseSwimlaneId = "reuse-swimlane-id";
      chain.content.elements = [defaultSwimlane];

      const reuseElement: ElementSchema = {
        id: "reuse-1",
        type: "reuse" as unknown as DataType,
        name: "Reuse",
        chain,
      };

      const elementRequest: CreateElementRequest = {
        type: "reuse",
        swimlaneId: "reuse-swimlane-id",
      };

      expect(() =>
        swimlaneValidations(chain, reuseElement, elementRequest),
      ).not.toThrow();
    });

    it("does not throw when non-reuse element is added to default swimlane", () => {
      const defaultSwimlane = createDefaultSwimlane(chain);
      chain.content.defaultSwimlaneId = defaultSwimlane.id;
      chain.content.reuseSwimlaneId = "reuse-swimlane-id";
      chain.content.elements = [defaultSwimlane];

      const nonReuseElement: ElementSchema = {
        id: "task-1",
        type: "richText" as unknown as DataType,
        name: "Task",
        chain,
      };

      const elementRequest: CreateElementRequest = {
        type: "richText",
        swimlaneId: "default-id",
      };

      expect(() =>
        swimlaneValidations(chain, nonReuseElement, elementRequest),
      ).not.toThrow();
    });

    it("does not throw when non-reuse element is added under a reuse parent element in reuse swimlane", () => {
      const defaultSwimlane = createDefaultSwimlane(chain);
      const reuseElement = createReuseElement("reuse-parent-id", chain);
      chain.content.defaultSwimlaneId = defaultSwimlane.id;
      chain.content.reuseSwimlaneId = "reuse-swimlane-id";
      chain.content.elements = [defaultSwimlane, reuseElement];

      mockFindElementById.mockReturnValue({
        element: {
          id: "reuse-parent-id",
          type: "reuse" as unknown as DataType,
          name: "Reuse Parent",
        },
        parentId: undefined,
      });

      const nonReuseElement: ElementSchema = {
        id: "task-1",
        type: "richText" as unknown as DataType,
        name: "Task",
        chain,
      };

      const elementRequest: CreateElementRequest = {
        type: "richText",
        parentElementId: "reuse-parent-id",
        swimlaneId: "reuse-swimlane-id",
      };

      expect(() =>
        swimlaneValidations(chain, nonReuseElement, elementRequest),
      ).not.toThrow();
      expect(mockFindElementById).toHaveBeenCalledWith(
        chain.content.elements,
        "reuse-parent-id",
      );
    });

    it("throws error when non-reuse element is added to reuse swimlane with non-reuse parent", () => {
      const defaultSwimlane = createDefaultSwimlane(chain);
      const taskElement = createTaskElement(
        "task-parent-id",
        "reuse-swimlane-id",
        chain,
      );
      chain.content.defaultSwimlaneId = defaultSwimlane.id;
      chain.content.reuseSwimlaneId = "reuse-swimlane-id";
      chain.content.elements = [defaultSwimlane, taskElement];

      mockFindElementById.mockReturnValue({
        element: {
          id: "task-parent-id",
          type: "richText" as unknown as DataType,
          name: "Task Parent",
        },
        parentId: undefined,
      });

      const nonReuseElement: ElementSchema = {
        id: "task-1",
        type: "richText" as unknown as DataType,
        name: "Task",
        chain,
      };

      const elementRequest: CreateElementRequest = {
        type: "richText",
        parentElementId: "task-parent-id",
        swimlaneId: "reuse-swimlane-id",
      };

      expect(() =>
        swimlaneValidations(chain, nonReuseElement, elementRequest),
      ).toThrow("Only Reuse element can be added to Reuse Swimlane");
    });
  });

  describe("transferToSwimlaneValidations", () => {
    let chain: ChainSchema;

    beforeEach(() => {
      chain = createChain();
      jest.clearAllMocks();
    });

    it("throws error when non-reuse element is transferred to reuse swimlane without reuse parent", () => {
      const defaultSwimlane = createDefaultSwimlane(chain);
      chain.content.defaultSwimlaneId = defaultSwimlane.id;
      chain.content.reuseSwimlaneId = "reuse-swimlane-id";
      chain.content.elements = [defaultSwimlane];

      const nonReuseElement: ElementSchema = {
        id: "task-1",
        type: "richText" as unknown as DataType,
        name: "Task",
        chain,
      };

      const elementRequest: TransferElementRequest = {
        parentId: null,
        elements: [],
        swimlaneId: "reuse-swimlane-id",
      };

      expect(() =>
        transferToSwimlaneValidations(chain, nonReuseElement, elementRequest),
      ).toThrow("Element task-1 cannot be moved to Reuse group");
    });

    it("does not throw when reuse element is transferred to reuse swimlane", () => {
      const defaultSwimlane = createDefaultSwimlane(chain);
      chain.content.defaultSwimlaneId = defaultSwimlane.id;
      chain.content.reuseSwimlaneId = "reuse-swimlane-id";
      chain.content.elements = [defaultSwimlane];

      const reuseElement: ElementSchema = {
        id: "reuse-1",
        type: "reuse" as unknown as DataType,
        name: "Reuse",
        chain,
      };

      const elementRequest: TransferElementRequest = {
        parentId: null,
        elements: [],
        swimlaneId: "reuse-swimlane-id",
      };

      expect(() =>
        transferToSwimlaneValidations(chain, reuseElement, elementRequest),
      ).not.toThrow();
    });

    it("does not throw when non-reuse element is transferred to default swimlane", () => {
      const defaultSwimlane = createDefaultSwimlane(chain);
      chain.content.defaultSwimlaneId = defaultSwimlane.id;
      chain.content.reuseSwimlaneId = "reuse-swimlane-id";
      chain.content.elements = [defaultSwimlane];

      const nonReuseElement: ElementSchema = {
        id: "task-1",
        type: "richText" as unknown as DataType,
        name: "Task",
        chain,
      };

      const elementRequest: TransferElementRequest = {
        parentId: null,
        elements: [],
        swimlaneId: "default-id",
      };

      expect(() =>
        transferToSwimlaneValidations(chain, nonReuseElement, elementRequest),
      ).not.toThrow();
    });

    it("does not throw when non-reuse element is transferred to reuse swimlane under reuse parent", () => {
      const defaultSwimlane = createDefaultSwimlane(chain);
      const reuseElement = createReuseElement("reuse-parent-id", chain);
      chain.content.defaultSwimlaneId = defaultSwimlane.id;
      chain.content.reuseSwimlaneId = "reuse-swimlane-id";
      chain.content.elements = [defaultSwimlane, reuseElement];

      mockFindElementById.mockReturnValue({
        element: {
          id: "reuse-parent-id",
          type: "reuse" as unknown as DataType,
          name: "Reuse Parent",
        },
        parentId: undefined,
      });

      const nonReuseElement: ElementSchema = {
        id: "task-1",
        type: "richText" as unknown as DataType,
        name: "Task",
        chain,
      };

      const elementRequest: TransferElementRequest = {
        parentId: "reuse-parent-id",
        elements: [],
        swimlaneId: "reuse-swimlane-id",
      };

      expect(() =>
        transferToSwimlaneValidations(chain, nonReuseElement, elementRequest),
      ).not.toThrow();
      expect(mockFindElementById).toHaveBeenCalledWith(
        chain.content.elements,
        "reuse-parent-id",
      );
    });

    it("throws error when non-reuse element is transferred to reuse swimlane under non-reuse parent", () => {
      const defaultSwimlane = createDefaultSwimlane(chain);
      const taskElement = createTaskElement(
        "task-parent-id",
        "reuse-swimlane-id",
        chain,
      );
      chain.content.defaultSwimlaneId = defaultSwimlane.id;
      chain.content.reuseSwimlaneId = "reuse-swimlane-id";
      chain.content.elements = [defaultSwimlane, taskElement];

      mockFindElementById.mockReturnValue({
        element: {
          id: "task-parent-id",
          type: "richText" as unknown as DataType,
          name: "Task Parent",
        },
        parentId: undefined,
      });

      const nonReuseElement: ElementSchema = {
        id: "task-1",
        type: "richText" as unknown as DataType,
        name: "Task",
        chain,
      };

      const elementRequest: TransferElementRequest = {
        parentId: "task-parent-id",
        elements: [],
        swimlaneId: "reuse-swimlane-id",
      };

      expect(() =>
        transferToSwimlaneValidations(chain, nonReuseElement, elementRequest),
      ).toThrow("Element task-1 cannot be moved to Reuse group");
    });
  });

  describe("isTransferOutOfSwimlane", () => {
    let chain: ChainSchema;

    beforeEach(() => {
      chain = createChain();
      jest.clearAllMocks();
    });

    it("returns true when element has swimlaneId and request has no swimlaneId", () => {
      const defaultSwimlane = createDefaultSwimlane(chain);
      chain.content.defaultSwimlaneId = defaultSwimlane.id;
      chain.content.elements = [defaultSwimlane];

      const element: ElementSchema = {
        id: "task-1",
        type: "richText" as unknown as DataType,
        name: "Task",
        swimlaneId: "default-id",
        chain,
      };

      const elementRequest: TransferElementRequest = {
        parentId: null,
        elements: [],
        swimlaneId: null,
      };

      const result = isTransferOutOfSwimlane(elementRequest, element, chain);
      expect(result).toBe(true);
    });

    it("returns false when element has swimlaneId and request has a swimlaneId", () => {
      const defaultSwimlane = createDefaultSwimlane(chain);
      chain.content.defaultSwimlaneId = defaultSwimlane.id;
      chain.content.reuseSwimlaneId = "reuse-swimlane-id";
      chain.content.elements = [defaultSwimlane];

      const element: ElementSchema = {
        id: "task-1",
        type: "richText" as unknown as DataType,
        name: "Task",
        swimlaneId: "default-id",
        chain,
      };

      const elementRequest: TransferElementRequest = {
        parentId: null,
        elements: [],
        swimlaneId: "reuse-swimlane-id",
      };

      const result = isTransferOutOfSwimlane(elementRequest, element, chain);
      expect(result).toBe(false);
    });

    it("returns false when element has no swimlaneId", () => {
      const defaultSwimlane = createDefaultSwimlane(chain);
      chain.content.defaultSwimlaneId = defaultSwimlane.id;
      chain.content.elements = [defaultSwimlane];

      const element: ElementSchema = {
        id: "task-1",
        type: "richText" as unknown as DataType,
        name: "Task",
        chain,
      };

      const elementRequest: TransferElementRequest = {
        parentId: null,
        elements: [],
        swimlaneId: null,
      };

      const result = isTransferOutOfSwimlane(elementRequest, element, chain);
      expect(result).toBe(false);
    });

    it("returns false when element has swimlaneId and new parent has swimlaneId", () => {
      const defaultSwimlane = createDefaultSwimlane(chain);
      const reuseSwimlane: ElementSchema = createSwimlaneElement(
        "reuse-swimlane-id",
        chain,
        "Reuse swimlane",
      );
      const parentElement = createTaskElement(
        "parent-id",
        "reuse-swimlane-id",
        chain,
      );
      chain.content.defaultSwimlaneId = defaultSwimlane.id;
      chain.content.reuseSwimlaneId = reuseSwimlane.id;
      chain.content.elements = [defaultSwimlane, reuseSwimlane, parentElement];

      mockFindElementById.mockReturnValue({
        element: {
          id: "parent-id",
          type: "richText" as unknown as DataType,
          name: "Parent",
          swimlaneId: "reuse-swimlane-id",
        },
        parentId: undefined,
      });

      const element: ElementSchema = {
        id: "task-1",
        type: "richText" as unknown as DataType,
        name: "Task",
        swimlaneId: "default-id",
        chain,
      };

      const elementRequest: TransferElementRequest = {
        parentId: "parent-id",
        elements: [],
        swimlaneId: null,
      };

      const result = isTransferOutOfSwimlane(elementRequest, element, chain);
      expect(result).toBe(false);
      expect(mockFindElementById).toHaveBeenCalledWith(
        chain.content.elements,
        "parent-id",
      );
    });

    it("returns true when element has swimlaneId and new parent has no swimlaneId", () => {
      const defaultSwimlane = createDefaultSwimlane(chain);
      const parentElement = createTaskElement(
        "parent-id",
        undefined as any,
        chain,
      );
      chain.content.defaultSwimlaneId = defaultSwimlane.id;
      chain.content.elements = [defaultSwimlane, parentElement];

      mockFindElementById.mockReturnValue({
        element: {
          id: "parent-id",
          type: "richText" as unknown as DataType,
          name: "Parent",
        },
        parentId: undefined,
      });

      const element: ElementSchema = {
        id: "task-1",
        type: "richText" as unknown as DataType,
        name: "Task",
        swimlaneId: "default-id",
        chain,
      };

      const elementRequest: TransferElementRequest = {
        parentId: "parent-id",
        elements: [],
        swimlaneId: null,
      };

      const result = isTransferOutOfSwimlane(elementRequest, element, chain);
      expect(result).toBe(true);
      expect(mockFindElementById).toHaveBeenCalledWith(
        chain.content.elements,
        "parent-id",
      );
    });

    it("returns false when element has no swimlaneId and request has parentId with swimlaneId", () => {
      const defaultSwimlane = createDefaultSwimlane(chain);
      const parentElement = createTaskElement(
        "parent-id",
        "reuse-swimlane-id",
        chain,
      );
      chain.content.defaultSwimlaneId = defaultSwimlane.id;
      chain.content.reuseSwimlaneId = "reuse-swimlane-id";
      chain.content.elements = [defaultSwimlane, parentElement];

      mockFindElementById.mockReturnValue({
        element: {
          id: "parent-id",
          type: "richText" as unknown as DataType,
          name: "Parent",
          swimlaneId: "reuse-swimlane-id",
        },
        parentId: undefined,
      });

      const element: ElementSchema = {
        id: "task-1",
        type: "richText" as unknown as DataType,
        name: "Task",
        chain,
      };

      const elementRequest: TransferElementRequest = {
        parentId: "parent-id",
        elements: [],
        swimlaneId: null,
      };

      const result = isTransferOutOfSwimlane(elementRequest, element, chain);
      expect(result).toBe(false);
    });
  });
});
