import {
  findElementByIdOrError,
  replaceElementPlaceholders,
  replacePlaceholders,
  cloneElementSchema,
  resetPropertiesToDefault,
  ChainElementPlaceholders,
} from "../../../src/web/response/chainApiUtils";
import { LibraryElement, LibraryElementProperty } from "@netcracker/qip-ui";
import { Element as ElementSchema, DataType } from "@netcracker/qip-schemas";
import { createVscodeMock } from "../../helpers/mocks";

// Mock the dependency
jest.mock("../../../src/web/response/chainApiRead", () => ({
  getCurrentChainId: jest.fn(),
}));

jest.mock("vscode", () => createVscodeMock(), { virtual: true });

describe("chainApiUtils", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe("findElementByIdOrError", () => {
    const mockElements: ElementSchema[] = [
      {
        id: "existing",
        name: "Existing",
        type: "service" as unknown as DataType,
        properties: {},
        children: [],
      },
    ];

    it("should return element when found", () => {
      const result: ElementSchema = findElementByIdOrError(
        mockElements,
        "existing",
      ).element;

      expect(result.id).toBe("existing");
    });

    it("should throw error with descriptive message when element not found without parentId", () => {
      const consoleSpy = jest.spyOn(console, "error").mockImplementation();

      expect(() => {
        findElementByIdOrError(mockElements, "missing");
      }).toThrow("Element with id=missing and parentId=undefined not found");

      expect(consoleSpy).toHaveBeenCalledWith(
        "Element with id=missing and parentId=undefined not found",
      );

      consoleSpy.mockRestore();
    });

    it("should throw error with parentId in message when provided", () => {
      const consoleSpy = jest.spyOn(console, "error").mockImplementation();

      expect(() => {
        findElementByIdOrError(mockElements, "missing", "parent-123");
      }).toThrow("Element with id=missing and parentId=parent-123 not found");

      consoleSpy.mockRestore();
    });

    it("should handle undefined elements array", () => {
      const consoleSpy = jest.spyOn(console, "error").mockImplementation();

      expect(() => {
        findElementByIdOrError(undefined, "any-id");
      }).toThrow("Element with id=any-id and parentId=undefined not found");

      consoleSpy.mockRestore();
    });
  });

  describe("replacePlaceholders", () => {
    const mockChainId = "chain-abc-123";
    const mockElementId = "element-xyz-789";

    it("should replace CHAIN_ID_PLACEHOLDER", () => {
      const input = `URL: ${ChainElementPlaceholders.CHAIN_ID_PLACEHOLDER}/api`;
      const result = replacePlaceholders(input, mockChainId, mockElementId);

      expect(result).toBe(`URL: ${mockChainId}/api`);
    });

    it("should replace CREATED_ELEMENT_ID_PLACEHOLDER", () => {
      const input = `Ref: ${ChainElementPlaceholders.CREATED_ELEMENT_ID_PLACEHOLDER}`;
      const result = replacePlaceholders(input, mockChainId, mockElementId);

      expect(result).toBe(`Ref: ${mockElementId}`);
    });

    it("should replace both placeholders in same string", () => {
      const input = `${ChainElementPlaceholders.CHAIN_ID_PLACEHOLDER}/${ChainElementPlaceholders.CREATED_ELEMENT_ID_PLACEHOLDER}`;
      const result = replacePlaceholders(input, mockChainId, mockElementId);

      expect(result).toBe(`${mockChainId}/${mockElementId}`);
    });

    it("should handle string with no placeholders", () => {
      const input = "plain string without placeholders";
      const result = replacePlaceholders(input, mockChainId, mockElementId);

      expect(result).toBe(input);
    });

    it("should handle empty string", () => {
      const result = replacePlaceholders("", mockChainId, mockElementId);
      expect(result).toBe("");
    });
  });

  describe("replaceElementPlaceholders", () => {
    const mockChainId = "chain-test";
    const mockElementId = "elem-test";

    it("should replace placeholders in string properties", () => {
      const properties = {
        url: `${ChainElementPlaceholders.CHAIN_ID_PLACEHOLDER}/endpoint`,
        ref: ChainElementPlaceholders.CREATED_ELEMENT_ID_PLACEHOLDER,
        count: 42, // non-string property should be unchanged
      };

      replaceElementPlaceholders(properties, mockChainId, mockElementId);

      expect(properties.url).toBe(`${mockChainId}/endpoint`);
      expect(properties.ref).toBe(mockElementId);
      expect(properties.count).toBe(42);
    });

    it("should handle empty properties object", () => {
      const properties = {};
      replaceElementPlaceholders(properties, mockChainId, mockElementId);
      expect(properties).toEqual({});
    });

    it("should handle properties with only non-string values", () => {
      const properties = {
        number: 123,
        boolean: true,
        array: [1, 2, 3],
        object: { nested: "value" },
      };

      replaceElementPlaceholders(properties, mockChainId, mockElementId);

      expect(properties).toEqual({
        number: 123,
        boolean: true,
        array: [1, 2, 3],
        object: { nested: "value" },
      });
    });

    it("should modify the original properties object (mutates in place)", () => {
      const properties = {
        value: ChainElementPlaceholders.CHAIN_ID_PLACEHOLDER,
      };
      const originalRef = properties;

      replaceElementPlaceholders(properties, mockChainId, mockElementId);

      expect(originalRef.value).toBe(mockChainId);
      expect(properties).toBe(originalRef);
    });
  });

  describe("cloneElementSchema", () => {
    it("should create a deep clone with new UUID", () => {
      const source: ElementSchema = {
        id: "original-id",
        name: "Test Element",
        type: "service" as unknown as DataType,
        properties: { key: "value" },
        children: [],
      };

      // Mock crypto.randomUUID
      const mockUuid = "new-uuid-12345";
      const originalRandomUUID = crypto.randomUUID;
      crypto.randomUUID = jest.fn().mockReturnValue(mockUuid);

      const result = cloneElementSchema(source);

      expect(result.id).toBe(mockUuid);
      expect(result.id).not.toBe(source.id);
      expect(result.name).toBe(source.name);
      expect(result.type).toBe(source.type);
      expect(result.properties).toEqual(source.properties);
      expect(result.properties).not.toBe(source.properties); // deep clone

      // Restore
      crypto.randomUUID = originalRandomUUID;
    });

    it("should handle nested children in clone", () => {
      const source: ElementSchema = {
        id: "parent",
        name: "Parent",
        type: "container" as unknown as DataType,
        properties: {},
        children: [
          {
            id: "child",
            name: "Child",
            type: "service" as unknown as DataType,
            properties: { nested: "value" },
            children: [],
          },
        ],
      };

      const mockUuid = "new-parent-uuid";
      const originalRandomUUID = crypto.randomUUID;
      crypto.randomUUID = jest.fn().mockReturnValue(mockUuid);

      const result: ElementSchema = cloneElementSchema(source);

      const resultChildren = result.children
        ? (result.children as ElementSchema[])
        : undefined;

      expect(resultChildren?.[0].id).toBe("child"); // children IDs not changed
      expect(resultChildren?.[0].properties).toEqual({ nested: "value" });
      expect(resultChildren?.[0].properties).not.toBe(
        (source.children as ElementSchema[])?.[0].properties,
      );

      crypto.randomUUID = originalRandomUUID;
    });
  });

  describe("resetPropertiesToDefault", () => {
    const mockChainId = "chain-default";
    const mockElementId = "elem-default";

    it("should reset properties marked with resetValueOnCopy", () => {
      const element: ElementSchema = {
        id: mockElementId,
        name: "Test",
        type: "service" as unknown as DataType,
        properties: {
          existingProp: "old-value",
          resetProp: "to-be-reset",
        },
      };

      const libraryElement: Partial<LibraryElement> = {
        name: "Test Lib",
        properties: {
          common: [
            {
              name: "resetProp",
              default: `default-${ChainElementPlaceholders.CHAIN_ID_PLACEHOLDER}`,
              resetValueOnCopy: true,
              type: "string",
            } as LibraryElementProperty,
            {
              name: "keepProp",
              default: "should-not-apply",
              resetValueOnCopy: false,
              type: "string",
            } as LibraryElementProperty,
          ],
          advanced: [],
          hidden: [],
          unknown: [],
        },
      };

      resetPropertiesToDefault(
        mockChainId,
        element,
        libraryElement as LibraryElement,
      );

      expect((element.properties as any).resetProp).toBe(
        `default-${mockChainId}`,
      );
      expect((element.properties as any).existingProp).toBe("old-value"); // unchanged
      expect((element.properties as any).keepProp).toBeUndefined(); // not reset
    });

    it("should handle undefined default value", () => {
      const element: ElementSchema = {
        id: mockElementId,
        name: "Test",
        type: "service" as unknown as DataType,
        properties: {
          resetProp: "old-value",
        },
      };

      const libraryElement: Partial<LibraryElement> = {
        name: "Test Lib",
        properties: {
          common: [
            {
              name: "resetProp",
              resetValueOnCopy: true,
              type: "string",
              // default is undefined
            } as LibraryElementProperty,
          ],
          advanced: [],
          hidden: [],
          unknown: [],
        },
      };

      resetPropertiesToDefault(
        mockChainId,
        element,
        libraryElement as LibraryElement,
      );

      expect((element.properties as any).resetProp).toBe("");
    });

    it("should replace placeholders in default values", () => {
      const element: ElementSchema = {
        id: mockElementId,
        name: "Test",
        type: "service" as unknown as DataType,
        properties: {},
      };

      const libraryElement: Partial<LibraryElement> = {
        name: "Test Lib",
        properties: {
          common: [
            {
              name: "urlProp",
              default: `/${ChainElementPlaceholders.CHAIN_ID_PLACEHOLDER}/${ChainElementPlaceholders.CREATED_ELEMENT_ID_PLACEHOLDER}`,
              resetValueOnCopy: true,
              type: "string",
            } as LibraryElementProperty,
          ],
          advanced: [],
          hidden: [],
          unknown: [],
        },
      };

      resetPropertiesToDefault(
        mockChainId,
        element,
        libraryElement as LibraryElement,
      );

      expect((element.properties as any).urlProp).toBe(
        `/${mockChainId}/${mockElementId}`,
      );
    });

    it("should process properties from common, advanced, and hidden arrays", () => {
      const element: ElementSchema = {
        id: mockElementId,
        name: "Test",
        type: "service" as unknown as DataType,
        properties: {},
      };

      const libraryElement: Partial<LibraryElement> = {
        name: "Test Lib",
        properties: {
          common: [
            {
              name: "commonProp",
              default: "common-default",
              resetValueOnCopy: true,
              type: "string",
            } as LibraryElementProperty,
          ],
          advanced: [
            {
              name: "advancedProp",
              default: "advanced-default",
              resetValueOnCopy: true,
              type: "string",
            } as LibraryElementProperty,
          ],
          hidden: [
            {
              name: "hiddenProp",
              default: "hidden-default",
              resetValueOnCopy: true,
              type: "string",
            } as LibraryElementProperty,
          ],
          unknown: [
            {
              name: "unknownProp",
              default: "unknown-default",
              resetValueOnCopy: true,
              type: "string",
            } as LibraryElementProperty,
          ],
        },
      };

      resetPropertiesToDefault(
        mockChainId,
        element,
        libraryElement as LibraryElement,
      );

      expect((element.properties as any).commonProp).toBe("common-default");
      expect((element.properties as any).advancedProp).toBe("advanced-default");
      expect((element.properties as any).hiddenProp).toBe("hidden-default");
      expect((element.properties as any).unknownProp).toBe("unknown-default");
    });
  });
});
