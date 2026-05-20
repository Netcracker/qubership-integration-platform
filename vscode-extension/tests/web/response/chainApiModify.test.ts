// tests/web/response/chainApiModify.test.ts
import { Uri } from "vscode";
import { cloneElements } from "../../../src/web/response/chainApiModify";
import {
  getMainChain,
  getElement,
  getLibraryElementByType,
} from "../../../src/web/response/chainApiRead";
import {
  findElementByIdOrError,
  cloneElementSchema,
  resetPropertiesToDefault,
  ChainElementPlaceholders,
} from "../../../src/web/response/chainApiUtils";
import { fileApi } from "../../../src/web/response/file";

// Import types from our mocks (Jest will resolve via moduleNameMapper)
import type {
  Element as ElementSchema,
  DataType,
} from "@netcracker/qip-schemas";
import type {
  Element,
  LibraryElement,
  LibraryElementProperty,
} from "@netcracker/qip-ui";

// Mock all dependencies
jest.mock("../../../src/web/response/chainApiRead", () => ({
  getMainChain: jest.fn(),
  getElement: jest.fn(),
  getLibraryElementByType: jest.fn(),
}));

jest.mock("../../../src/web/response/chainApiUtils", () => ({
  findElementByIdOrError: jest.fn(),
  cloneElementSchema: jest.fn(),
  resetPropertiesToDefault: jest.fn(),
  ChainElementPlaceholders: {
    CHAIN_ID_PLACEHOLDER: "%%{chain-id-placeholder}",
    CREATED_ELEMENT_ID_PLACEHOLDER: "%%{created-element-id-placeholder}",
  },
}));

jest.mock("../../../src/web/response/file", () => ({
  fileApi: {
    writeMainChain: jest.fn(),
  },
}));

// Mock crypto.randomUUID for consistent test IDs
const mockUuids = ["mock-uuid-1", "mock-uuid-2", "mock-uuid-3"];
let uuidIndex = 0;

describe("cloneElements", () => {
  const mockFileUri = { path: "/workspace/test" } as Uri;
  const mockChainId = "test-chain-123";
  const mockElementId = "element-abc-456";
  const mockContainerId = "container-xyz-789";

  beforeEach(() => {
    jest.clearAllMocks();
    uuidIndex = 0;
  });

  describe("chain validation", () => {
    it("should throw error when chainId does not match", async () => {
      const mockChain = {
        id: "different-chain-id",
        content: { elements: [] },
      };
      (getMainChain as jest.Mock).mockResolvedValue(mockChain);

      await expect(
        cloneElements(mockFileUri, mockChainId, [mockElementId]),
      ).rejects.toThrow("ChainId mismatch");

      expect(getMainChain).toHaveBeenCalledWith(mockFileUri);
      expect(fileApi.writeMainChain).not.toHaveBeenCalled();
    });

    it("should proceed when chainId matches", async () => {
      const mockChain = {
        id: mockChainId,
        content: { elements: [] },
      };
      (getMainChain as jest.Mock).mockResolvedValue(mockChain);
      (findElementByIdOrError as jest.Mock).mockReturnValue({
        element: undefined,
      });

      await expect(
        cloneElements(mockFileUri, mockChainId, []),
      ).resolves.toEqual([]);

      expect(getMainChain).toHaveBeenCalledWith(mockFileUri);
    });
  });

  describe("cloning to root (no container)", () => {
    const mockSourceElement: ElementSchema = {
      id: mockElementId,
      name: "Source Element",
      type: "service" as unknown as DataType,
      properties: { url: "http://original" },
      children: [],
    };

    const mockClonedElement: ElementSchema = {
      id: mockUuids[0],
      name: "Source Element",
      type: "service" as unknown as DataType,
      properties: { url: "http://original" },
      children: [],
    };

    const mockLibraryElement: Partial<LibraryElement> = {
      name: "Service Library",
      properties: {
        common: [
          {
            name: "url",
            type: "string",
            default: "http://default",
            resetValueOnCopy: false,
          } as LibraryElementProperty,
        ],
        advanced: [],
        hidden: [],
        unknown: [],
      },
    };

    const mockParsedElement: Partial<Element> = {
      id: mockUuids[0],
      name: "Source Element",
      type: "service",
      properties: { url: "http://default" } as never,
    };

    it("should clone single element to root level", async () => {
      const mockChain = {
        id: mockChainId,
        content: { elements: [mockSourceElement] },
      };
      (getMainChain as jest.Mock).mockResolvedValue(mockChain);
      (findElementByIdOrError as jest.Mock).mockReturnValue({
        element: mockSourceElement,
      });
      (cloneElementSchema as jest.Mock).mockReturnValue(mockClonedElement);
      (getLibraryElementByType as jest.Mock).mockResolvedValue(
        mockLibraryElement,
      );
      (resetPropertiesToDefault as jest.Mock).mockImplementation(
        (chainId, clone) => {
          clone.properties = { url: "http://default" };
        },
      );
      (getElement as jest.Mock).mockResolvedValue(mockParsedElement);

      const result = await cloneElements(mockFileUri, mockChainId, [
        mockElementId,
      ]);

      // Verify find and clone
      expect(findElementByIdOrError).toHaveBeenCalledWith(
        mockChain.content.elements,
        mockElementId,
      );
      expect(cloneElementSchema).toHaveBeenCalledWith(mockSourceElement);

      // Verify property reset
      expect(resetPropertiesToDefault).toHaveBeenCalledWith(
        mockChainId,
        mockClonedElement,
        mockLibraryElement,
      );

      // Verify clone added to root with undefined parent
      expect(mockClonedElement.parentElementId).toBeUndefined();
      expect(mockChain.content.elements).toContain(mockClonedElement);

      // Verify file write and return
      expect(fileApi.writeMainChain).toHaveBeenCalledWith(
        mockFileUri,
        mockChain,
      );
      expect(getElement).toHaveBeenCalledWith(
        mockFileUri,
        mockChainId,
        mockUuids[0],
      );
      expect(result).toEqual([mockParsedElement]);
    });

    it("should clone multiple elements to root level", async () => {
      const mockElement2: ElementSchema = {
        id: "element-2",
        name: "Element 2",
        type: "component" as unknown as DataType,
        properties: {},
        children: [],
      };
      const mockClone2: ElementSchema = {
        id: mockUuids[1],
        name: "Element 2",
        type: "component" as unknown as DataType,
        properties: {},
        children: [],
      };
      const mockParsedElement2: Partial<Element> = {
        id: mockUuids[1],
        name: "Element 2",
        type: "component",
        properties: {} as never,
      };

      const mockChain = {
        id: mockChainId,
        content: { elements: [mockSourceElement, mockElement2] },
      };
      (getMainChain as jest.Mock).mockResolvedValue(mockChain);
      (findElementByIdOrError as jest.Mock)
        .mockReturnValueOnce({ element: mockSourceElement })
        .mockReturnValueOnce({ element: mockElement2 });
      (cloneElementSchema as jest.Mock)
        .mockReturnValueOnce(mockClonedElement)
        .mockReturnValueOnce(mockClone2);
      (getLibraryElementByType as jest.Mock)
        .mockResolvedValueOnce(mockLibraryElement)
        .mockResolvedValueOnce({ ...mockLibraryElement, id: "lib-component" });
      (resetPropertiesToDefault as jest.Mock).mockImplementation(() => {});
      (getElement as jest.Mock)
        .mockResolvedValueOnce(mockParsedElement)
        .mockResolvedValueOnce(mockParsedElement2);

      const result = await cloneElements(mockFileUri, mockChainId, [
        mockElementId,
        "element-2",
      ]);

      expect(findElementByIdOrError).toHaveBeenCalledTimes(2);
      expect(cloneElementSchema).toHaveBeenCalledTimes(2);
      expect(fileApi.writeMainChain).toHaveBeenCalledWith(
        mockFileUri,
        mockChain,
      );
      expect(result).toHaveLength(2);
      expect(result).toEqual([mockParsedElement, mockParsedElement2]);
    });
  });

  describe("cloning to container", () => {
    const mockSourceElement: ElementSchema = {
      id: mockElementId,
      name: "Child Service",
      type: "service" as unknown as DataType,
      properties: {},
      children: [],
    };

    const mockContainerElement: ElementSchema = {
      id: mockContainerId,
      name: "Parent Container",
      type: "container" as unknown as DataType,
      properties: {},
      children: [],
    };

    const mockClonedElement: ElementSchema = {
      id: mockUuids[0],
      name: "Child Service",
      type: "service" as unknown as DataType,
      properties: {},
      children: [],
    };

    const mockLibraryElement: Partial<LibraryElement> = {
      name: "Service Library",
      properties: { common: [], advanced: [], hidden: [], unknown: [] },
    };

    const mockParsedElement: Partial<Element> = {
      id: mockUuids[0],
      name: "Child Service",
      type: "service",
      properties: {} as never,
      parentElementId: mockContainerId,
    };

    it("should clone element and add to specified container", async () => {
      const mockChain = {
        id: mockChainId,
        content: { elements: [mockContainerElement] },
      };
      (getMainChain as jest.Mock).mockResolvedValue(mockChain);
      (findElementByIdOrError as jest.Mock)
        .mockReturnValueOnce({ element: mockContainerElement })
        .mockReturnValueOnce({ element: mockSourceElement });
      (cloneElementSchema as jest.Mock).mockReturnValue(mockClonedElement);
      (getLibraryElementByType as jest.Mock).mockResolvedValue(
        mockLibraryElement,
      );
      (resetPropertiesToDefault as jest.Mock).mockImplementation(() => {});
      (getElement as jest.Mock).mockResolvedValue(mockParsedElement);

      const result = await cloneElements(
        mockFileUri,
        mockChainId,
        [mockElementId],
        mockContainerId,
      );

      // Verify container lookup
      expect(findElementByIdOrError).toHaveBeenCalledWith(
        mockChain.content.elements,
        mockContainerId,
      );

      // Verify clone added to container's children
      expect(mockClonedElement.parentElementId).toBe(mockContainerId);
      expect(mockContainerElement.children).toContain(mockClonedElement);

      // Verify return
      expect(fileApi.writeMainChain).toHaveBeenCalledWith(
        mockFileUri,
        mockChain,
      );
      expect(result).toEqual([mockParsedElement]);
    });

    it("should initialize container.children array if undefined", async () => {
      const mockContainerWithoutChildren: ElementSchema = {
        id: mockContainerId,
        name: "Empty Container",
        type: "container" as unknown as DataType,
        properties: {},
        // children is undefined
      };

      const mockChain = {
        id: mockChainId,
        content: { elements: [mockContainerWithoutChildren] },
      };
      (getMainChain as jest.Mock).mockResolvedValue(mockChain);
      (findElementByIdOrError as jest.Mock)
        .mockReturnValueOnce({ element: mockContainerWithoutChildren })
        .mockReturnValueOnce({ element: mockSourceElement });
      (cloneElementSchema as jest.Mock).mockReturnValue(mockClonedElement);
      (getLibraryElementByType as jest.Mock).mockResolvedValue(
        mockLibraryElement,
      );
      (resetPropertiesToDefault as jest.Mock).mockImplementation(() => {});
      (getElement as jest.Mock).mockResolvedValue(mockParsedElement);

      await cloneElements(
        mockFileUri,
        mockChainId,
        [mockElementId],
        mockContainerId,
      );

      // Verify children array was initialized
      expect(mockContainerWithoutChildren.children).toEqual([
        mockClonedElement,
      ]);
    });
  });

  describe("error handling", () => {
    it("should throw error when source element not found", async () => {
      const mockChain = {
        id: mockChainId,
        content: { elements: [] },
      };
      (getMainChain as jest.Mock).mockResolvedValue(mockChain);
      (findElementByIdOrError as jest.Mock).mockImplementation(() => {
        console.error("Element with id=nonexistent not found");
        throw new Error(
          "Element with id=nonexistent and parentId=undefined not found",
        );
      });

      await expect(
        cloneElements(mockFileUri, mockChainId, ["nonexistent"]),
      ).rejects.toThrow(
        "Element with id=nonexistent and parentId=undefined not found",
      );

      expect(fileApi.writeMainChain).not.toHaveBeenCalled();
    });

    it("should throw error when containerId specified but container not found", async () => {
      const mockChain = {
        id: mockChainId,
        content: { elements: [] },
      };
      (getMainChain as jest.Mock).mockResolvedValue(mockChain);
      (findElementByIdOrError as jest.Mock)
        /* .mockReturnValueOnce({
          element: {
            id: mockElementId,
            name: "Test",
            type: "service" as unknown as DataType,
            properties: {},
            children: [],
          },
        }) */
        .mockImplementationOnce(() => {
          console.error("Element with id=missing-container not found");
          throw new Error(
            "Element with id=missing-container and parentId=undefined not found",
          );
        });

      await expect(
        cloneElements(
          mockFileUri,
          mockChainId,
          [mockElementId],
          "missing-container",
        ),
      ).rejects.toThrow(
        "Element with id=missing-container and parentId=undefined not found",
      );

      expect(fileApi.writeMainChain).not.toHaveBeenCalled();
    });
  });

  describe("property reset behavior", () => {
    it("should call resetPropertiesToDefault with correct parameters", async () => {
      const mockSourceElement: ElementSchema = {
        id: mockElementId,
        name: "Test Service",
        type: "service" as unknown as DataType,
        properties: { apiKey: "secret123" },
        children: [],
      };

      const mockClonedElement: ElementSchema = {
        id: mockUuids[0],
        name: "Test Service",
        type: "service" as unknown as DataType,
        properties: { apiKey: "secret123" },
        children: [],
      };

      const mockLibraryElement: Partial<LibraryElement> = {
        name: "Service Library",
        properties: {
          common: [
            {
              name: "apiKey",
              type: "string",
              default: `${ChainElementPlaceholders.CHAIN_ID_PLACEHOLDER}-default-key`,
              resetValueOnCopy: true,
            } as LibraryElementProperty,
          ],
          advanced: [],
          hidden: [],
          unknown: [],
        },
      };

      const mockChain = {
        id: mockChainId,
        content: { elements: [mockSourceElement] },
      };
      (getMainChain as jest.Mock).mockResolvedValue(mockChain);
      (findElementByIdOrError as jest.Mock).mockReturnValue({
        element: mockSourceElement,
      });
      (cloneElementSchema as jest.Mock).mockReturnValue(mockClonedElement);
      (getLibraryElementByType as jest.Mock).mockResolvedValue(
        mockLibraryElement,
      );
      (resetPropertiesToDefault as jest.Mock).mockImplementation(
        (chainId, clone, libElement) => {
          // Simulate placeholder replacement in default value
          clone.properties = {
            apiKey: `default-${chainId}-key`,
          };
        },
      );
      (getElement as jest.Mock).mockResolvedValue({
        id: mockUuids[0],
        name: "Test Service",
        type: "service",
        properties: { apiKey: `default-${mockChainId}-key` },
      });

      await cloneElements(mockFileUri, mockChainId, [mockElementId]);

      expect(resetPropertiesToDefault).toHaveBeenCalledWith(
        mockChainId,
        mockClonedElement,
        mockLibraryElement,
      );
      // Verify the clone's properties were reset
      expect(mockClonedElement.properties).toEqual({
        apiKey: `default-${mockChainId}-key`,
      });
    });
  });

  describe("edge cases", () => {
    it("should handle empty ids array", async () => {
      const mockChain = {
        id: mockChainId,
        content: { elements: [] },
      };
      (getMainChain as jest.Mock).mockResolvedValue(mockChain);

      const result = await cloneElements(mockFileUri, mockChainId, []);

      expect(result).toEqual([]);
      expect(fileApi.writeMainChain).toHaveBeenCalledWith(
        mockFileUri,
        mockChain,
      );
      expect(getElement).not.toHaveBeenCalled();
    });

    it("should handle elements with nested children", async () => {
      const mockNestedElement: ElementSchema = {
        id: mockElementId,
        name: "Parent",
        type: "container" as unknown as DataType,
        properties: {},
        children: [
          {
            id: "child-1",
            name: "Child",
            type: "service" as unknown as DataType,
            properties: { nested: true },
            children: [],
          },
        ],
      };

      const mockClonedNested: ElementSchema = {
        id: mockUuids[0],
        name: "Parent",
        type: "container" as unknown as DataType,
        properties: {},
        children: [
          {
            id: "child-1",
            name: "Child",
            type: "service" as unknown as DataType,
            properties: { nested: true },
            children: [],
          },
        ],
      };

      const mockChain = {
        id: mockChainId,
        content: { elements: [mockNestedElement] },
      };
      (getMainChain as jest.Mock).mockResolvedValue(mockChain);
      (findElementByIdOrError as jest.Mock).mockReturnValue({
        element: mockNestedElement,
      });
      (cloneElementSchema as jest.Mock).mockReturnValue(mockClonedNested);
      (getLibraryElementByType as jest.Mock).mockResolvedValue({
        id: "lib-container",
        name: "Container",
        properties: { common: [], advanced: [], hidden: [] },
      });
      (resetPropertiesToDefault as jest.Mock).mockImplementation(() => {});
      (getElement as jest.Mock).mockResolvedValue({
        id: mockUuids[0],
        name: "Parent",
        type: "container",
        properties: {},
      });

      const result = await cloneElements(mockFileUri, mockChainId, [
        mockElementId,
      ]);

      expect(cloneElementSchema).toHaveBeenCalledWith(mockNestedElement);
      // Verify nested structure preserved in clone
      expect(mockClonedNested.children).toHaveLength(1);
      expect((mockClonedNested.children as ElementSchema[])?.[0].name).toBe(
        "Child",
      );
      expect(result).toHaveLength(1);
    });

    it("should handle getLibraryElementByType returning undefined gracefully", async () => {
      const mockSourceElement: ElementSchema = {
        id: mockElementId,
        name: "Unknown Type",
        type: "unknown-type" as unknown as DataType,
        properties: {},
        children: [],
      };

      const mockClonedElement: ElementSchema = {
        id: mockUuids[0],
        name: "Unknown Type",
        type: "unknown-type" as unknown as DataType,
        properties: {},
        children: [],
      };

      const mockChain = {
        id: mockChainId,
        content: { elements: [mockSourceElement] },
      };
      (getMainChain as jest.Mock).mockResolvedValue(mockChain);
      (findElementByIdOrError as jest.Mock).mockReturnValue({
        element: mockSourceElement,
      });
      (cloneElementSchema as jest.Mock).mockReturnValue(mockClonedElement);
      (getLibraryElementByType as jest.Mock).mockResolvedValue(undefined);
      // resetPropertiesToDefault should handle undefined libraryElement
      (resetPropertiesToDefault as jest.Mock).mockImplementation(() => {});
      (getElement as jest.Mock).mockResolvedValue({
        id: mockUuids[0],
        name: "Unknown Type",
        type: "unknown-type",
        properties: {},
      });

      // Should not throw - function should handle undefined library element
      await expect(
        cloneElements(mockFileUri, mockChainId, [mockElementId]),
      ).resolves.not.toThrow();

      expect(resetPropertiesToDefault).toHaveBeenCalledWith(
        mockChainId,
        mockClonedElement,
        undefined,
      );
    });
  });
});
