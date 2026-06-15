import { OrderedElementUtils } from "../../../src/web/api-services/OrderedElementUtils";
import { getLibraryElementByType } from "../../../src/web/response/chainApiRead";
import { LibraryElement } from "@netcracker/qip-ui";
import { Element as ElementSchema } from "@netcracker/qip-schemas";

// Mock dependencies
jest.mock("../../../src/web/response/chainApiRead");

const mockGetLibraryElementByType =
  getLibraryElementByType as jest.MockedFunction<
    typeof getLibraryElementByType
  >;

describe("OrderedElementUtils", () => {
  let mockElement: ElementSchema;
  let mockLibraryElement: LibraryElement;
  let utils: OrderedElementUtils;

  beforeEach(() => {
    jest.clearAllMocks();
    mockElement = {
      id: "test-element-id",
      type: { name: "test-ordered-type" } as any,
      parentElementId: "parent-id",
      properties: { priority: 0 },
      children: [],
    } as unknown as ElementSchema;

    mockLibraryElement = {
      name: "Test Ordered Type",
      ordered: true,
      priorityProperty: "priority",
    } as LibraryElement;
  });

  describe("create", () => {
    it("should create OrderedElementUtils instance with element and library data", async () => {
      mockGetLibraryElementByType.mockResolvedValue(mockLibraryElement);

      const result = await OrderedElementUtils.create(mockElement);

      expect(mockGetLibraryElementByType).toHaveBeenCalledWith({
        name: "test-ordered-type",
      });
      expect(result.element).toEqual(mockElement);
    });

    it("should use default priority property when not specified in library element", async () => {
      mockGetLibraryElementByType.mockResolvedValue({
        name: "Test Type",
        ordered: true,
      } as LibraryElement);

      const result = await OrderedElementUtils.create(mockElement);

      expect(getPriorityProperty(result)).toBe("priority");
    });
  });

  describe("getPriority", () => {
    beforeEach(async () => {
      mockGetLibraryElementByType.mockResolvedValue(mockLibraryElement);
      utils = await OrderedElementUtils.create(mockElement);
    });

    it("should return priority from element when called without arguments", () => {
      const result = utils.getPriority();
      expect(result).toBe(0);
    });

    it("should return priority from provided element when called with element argument", () => {
      const otherElement = {
        ...mockElement,
        properties: { priority: 5 },
      } as ElementSchema;

      const result = utils.getPriority(otherElement);
      expect(result).toBe(5);
    });

    it("should use custom priority property from library element", async () => {
      mockGetLibraryElementByType.mockResolvedValue({
        ...mockLibraryElement,
        priorityProperty: "customPriority",
      } as LibraryElement);

      const elementWithCustomPriority = {
        ...mockElement,
        properties: { customPriority: 3 },
      } as ElementSchema;

      const customUtils = await OrderedElementUtils.create(
        elementWithCustomPriority,
      );
      const result = customUtils.getPriority();

      expect(result).toBe(3);
    });
  });

  describe("getPriorityOrUndefined", () => {
    beforeEach(async () => {
      mockGetLibraryElementByType.mockResolvedValue(mockLibraryElement);
      utils = await OrderedElementUtils.create(mockElement);
    });

    it("should return priority value when property exists", () => {
      const properties = { priority: 2 };
      const result = utils.getPriorityOrUndefined(properties);
      expect(result).toBe(2);
    });

    it("should return undefined when priority property does not exist", () => {
      const properties = { otherProp: "value" };
      const result = utils.getPriorityOrUndefined(properties);
      expect(result).toBeUndefined();
    });
  });

  describe("updatePriority", () => {
    beforeEach(async () => {
      mockGetLibraryElementByType.mockResolvedValue(mockLibraryElement);
      utils = await OrderedElementUtils.create(mockElement);
    });

    it("should update priority on the element when called without element argument", () => {
      const initialPriority = (mockElement.properties as any).priority;
      expect(initialPriority).toBe(0);

      utils.updatePriority(5);

      expect((mockElement.properties as any).priority).toBe(5);
    });

    it("should update priority on provided element when called with element argument", () => {
      const otherElement = {
        ...mockElement,
        properties: { priority: 1 },
      } as ElementSchema;

      utils.updatePriority(3, otherElement);

      expect((otherElement.properties as any).priority).toBe(3);
      expect((mockElement.properties as any).priority).toBe(0); // Original unchanged
    });

    it("should use custom priority property when updating", async () => {
      mockGetLibraryElementByType.mockResolvedValue({
        ...mockLibraryElement,
        priorityProperty: "customPriority",
      } as LibraryElement);

      const elementWithCustomPriority = {
        ...mockElement,
        properties: { customPriority: 1 },
      } as ElementSchema;

      const customUtils = await OrderedElementUtils.create(
        elementWithCustomPriority,
      );
      customUtils.updatePriority(2);

      expect((elementWithCustomPriority.properties as any).customPriority).toBe(
        2,
      );
    });
  });

  describe("getIndex", () => {
    beforeEach(async () => {
      mockGetLibraryElementByType.mockResolvedValue(mockLibraryElement);
      utils = await OrderedElementUtils.create(mockElement);
    });

    it("should find index by element id when priority is undefined", () => {
      const sortedElements = [
        {
          id: "other-element",
          properties: { priority: 0 },
        } as unknown as ElementSchema,
        { ...mockElement },
        {
          id: "another-element",
          properties: { priority: 2 },
        } as unknown as ElementSchema,
      ];

      const result = utils.getIndex(sortedElements);
      expect(result).toBe(1);
    });

    it("should find index by priority when priority provided", () => {
      const sortedElements = [
        {
          id: "elem-1",
          properties: { priority: 0 },
        } as unknown as ElementSchema,
        {
          id: "elem-2",
          properties: { priority: 2 },
        } as unknown as ElementSchema,
        {
          id: "elem-3",
          properties: { priority: 1 },
        } as unknown as ElementSchema,
      ];

      const result = utils.getIndex(sortedElements, 1);
      expect(result).toBe(2);
    });

    it("should return -1 when element not found by id", () => {
      const sortedElements = [
        {
          id: "other-element",
          properties: { priority: 0 },
        } as unknown as ElementSchema,
      ];

      const result = utils.getIndex(sortedElements);
      expect(result).toBe(-1);
    });

    it("should return -1 when priority not found", () => {
      const sortedElements = [
        {
          id: "elem-1",
          properties: { priority: 0 },
        } as unknown as ElementSchema,
        {
          id: "elem-2",
          properties: { priority: 1 },
        } as unknown as ElementSchema,
      ];

      const result = utils.getIndex(sortedElements, 5);
      expect(result).toBe(-1);
    });
  });

  describe("extractOtherOrderedElements", () => {
    beforeEach(async () => {
      mockGetLibraryElementByType.mockResolvedValue(mockLibraryElement);
      utils = await OrderedElementUtils.create(mockElement);
    });

    it("should return children with same type excluding current element", () => {
      const parentElement = {
        id: "parent-id",
        children: [
          // Use the same element reference, not a spread copy
          mockElement,
          {
            id: "child-1",
            type: mockElement.type,
            properties: { priority: 1 },
          } as unknown as ElementSchema,
          {
            id: "child-2",
            type: { name: "different-type" } as any,
            properties: { priority: 2 },
          } as unknown as ElementSchema,
          {
            id: "child-3",
            type: mockElement.type,
            properties: { priority: 3 },
          } as unknown as ElementSchema,
        ] as any,
      } as unknown as ElementSchema;

      const result = utils.extractOtherOrderedElements(parentElement);

      expect(result).toHaveLength(2);
      expect(result.map((e: any) => e.id)).toEqual(
        expect.arrayContaining(["child-1", "child-3"]),
      );
      expect(
        result.find((e: any) => e.id === "test-element-id"),
      ).toBeUndefined();
    });

    it("should return empty array when no other elements have same type", () => {
      const parentElement = {
        id: "parent-id",
        children: [
          { ...mockElement },
          {
            id: "child-1",
            type: { name: "different-type" } as any,
            properties: {},
          } as unknown as ElementSchema,
        ] as any,
      } as unknown as ElementSchema;

      const result = utils.extractOtherOrderedElements(parentElement);

      expect(result).toHaveLength(0);
    });
  });

  describe("extractSortedOrderedElements", () => {
    beforeEach(async () => {
      mockGetLibraryElementByType.mockResolvedValue(mockLibraryElement);
      utils = await OrderedElementUtils.create(mockElement);
    });

    it("should sort children by priority ascending", () => {
      const parentElement = {
        id: "parent-id",
        children: [
          {
            id: "child-1",
            type: mockElement.type, // Use same reference as mockElement.type
            properties: { priority: 2 },
          } as unknown as ElementSchema,
          {
            id: "child-2",
            type: mockElement.type, // Use same reference as mockElement.type
            properties: { priority: 0 },
          } as unknown as ElementSchema,
          {
            id: "child-3",
            type: mockElement.type, // Use same reference as mockElement.type
            properties: { priority: 1 },
          } as unknown as ElementSchema,
          {
            id: "child-4",
            type: { name: "different-type" } as any, // Different type
            properties: { priority: 0 },
          } as unknown as ElementSchema,
        ] as any,
      } as unknown as ElementSchema;

      const result = utils.extractSortedOrderedElements(parentElement);

      expect(result).toHaveLength(3); // Only elements with same type
      expect(result[0].id).toBe("child-2"); // priority 0
      expect(result[1].id).toBe("child-3"); // priority 1
      expect(result[2].id).toBe("child-1"); // priority 2
    });

    it("should handle equal priorities by maintaining original order", () => {
      const parentElement = {
        id: "parent-id",
        children: [
          {
            id: "child-1",
            type: mockElement.type, // Use same reference
            properties: { priority: 1 },
          } as unknown as ElementSchema,
          {
            id: "child-2",
            type: mockElement.type, // Use same reference
            properties: { priority: 1 },
          } as unknown as ElementSchema,
        ] as any,
      } as unknown as ElementSchema;

      const result = utils.extractSortedOrderedElements(parentElement);

      expect(result).toHaveLength(2);
      // For equal priorities, order should be maintained (stable sort)
      expect(result[0].id).toBe("child-1");
      expect(result[1].id).toBe("child-2");
    });

    it("should return empty array when no children of same type exist", () => {
      const parentElement = {
        id: "parent-id",
        children: [
          {
            id: "child-1",
            type: { name: "different-type" } as any,
            properties: {},
          } as unknown as ElementSchema,
        ] as any,
      } as unknown as ElementSchema;

      const result = utils.extractSortedOrderedElements(parentElement);

      expect(result).toHaveLength(0);
    });
  });
});

// Helper function to access private method for testing
function getPriorityProperty(utils: OrderedElementUtils): string {
  return (utils as any).getPriorityProperty();
}
