import { Uri } from "vscode";
import { DataType, Element as ElementSchema } from "@netcracker/qip-schemas";
import { OrderedElementService } from "../../../src/web/api-services/OrderedElementService";
import { OrderedElementUtils } from "../../../src/web/api-services/OrderedElementUtils";
import {
  getLibraryElementByType,
  parseElement,
} from "../../../src/web/response/chainApiRead";
import { findElementById } from "../../../src/web/response/chainApiUtils";
import { LibraryElement, ActionDifference } from "@netcracker/qip-ui";

// Mock dependencies
jest.mock("../../../src/web/response/chainApiRead");
jest.mock("../../../src/web/response/chainApiUtils");
jest.mock("../../../src/web/api-services/OrderedElementUtils");

const mockGetLibraryElementByType =
  getLibraryElementByType as jest.MockedFunction<
    typeof getLibraryElementByType
  >;
const mockParseElement = parseElement as jest.MockedFunction<
  typeof parseElement
>;
const mockFindElementById = findElementById as jest.MockedFunction<
  typeof findElementById
>;

describe("OrderedElementService", () => {
  let service: OrderedElementService;
  let mockChainFileUri: Uri;
  let mockChainId: string;
  let mockChainElements: ElementSchema[];

  // Mock OrderedElementUtils instance
  const createMockUtils = () => ({
    element: {} as ElementSchema,
    parentElementId: "parent-id",
    extractOtherOrderedElements: jest.fn().mockReturnValue([]),
    extractSortedOrderedElements: jest.fn().mockReturnValue([]),
    getPriority: jest.fn().mockReturnValue(0),
    updatePriority: jest.fn(),
    getIndex: jest.fn().mockReturnValue(0),
    getPriorityOrUndefined: jest.fn().mockReturnValue(undefined),
    getIndexToInsert: jest.fn().mockReturnValue(0),
  });

  beforeEach(() => {
    jest.clearAllMocks();
    mockChainFileUri = Uri.file("/test/chain.yaml");
    mockChainId = "test-chain-id";
    mockChainElements = [];

    // Default mock implementation
    (OrderedElementUtils.create as jest.Mock).mockImplementation(() =>
      Promise.resolve(createMockUtils()),
    );
  });

  describe("updatePriority", () => {
    it("should call calculatePriority when element is ordered", async () => {
      const mockElementSchema: ElementSchema = {
        id: "element-1",
        type: { name: "ordered-type" } as any,
        properties: { priority: 0 },
      } as unknown as ElementSchema;

      const mockElement = {
        element: mockElementSchema,
        parentElementId: "parent-id",
      };

      mockGetLibraryElementByType.mockResolvedValue({
        ordered: true,
      } as LibraryElement);

      service = new OrderedElementService(
        mockChainFileUri,
        mockChainId,
        mockChainElements,
      );

      const calculatePrioritySpy = jest.spyOn(
        service as any,
        "calculatePriority",
      );

      await service.updatePriority(mockElement);

      expect(calculatePrioritySpy).toHaveBeenCalledWith(mockElement);
    });

    it("should process children when element has container and ordered children", async () => {
      const orderedChild: ElementSchema = {
        id: "child-1",
        type: { name: "ordered-child-type" } as any,
        properties: { priority: 0 },
      } as unknown as ElementSchema;

      const mockElementSchema: ElementSchema = {
        id: "element-1",
        type: { name: "container-type" } as any,
        children: [orderedChild] as any,
        properties: {},
      } as unknown as ElementSchema;

      const mockElement = {
        element: mockElementSchema,
        parentElementId: undefined,
      };

      mockGetLibraryElementByType
        .mockResolvedValueOnce({
          ordered: false,
          container: true,
        } as LibraryElement)
        .mockResolvedValueOnce({
          ordered: false,
          container: true,
        } as LibraryElement)
        .mockResolvedValueOnce({ ordered: true } as LibraryElement);

      service = new OrderedElementService(
        mockChainFileUri,
        mockChainId,
        mockChainElements,
      );

      const calculatePrioritySpy = jest.spyOn(
        service as any,
        "calculatePriority",
      );

      await service.updatePriority(mockElement);

      expect(calculatePrioritySpy).toHaveBeenCalled();
    });

    it("should do nothing when element is not ordered and has no container", async () => {
      const mockElementSchema: ElementSchema = {
        id: "element-1",
        type: { name: "non-ordered-type" } as any,
        parentElementId: undefined,
        properties: {},
      } as unknown as ElementSchema;

      const mockElement = {
        element: mockElementSchema,
        parentElementId: undefined,
      };

      mockGetLibraryElementByType.mockResolvedValue({
        ordered: false,
      } as LibraryElement);

      service = new OrderedElementService(
        mockChainFileUri,
        mockChainId,
        mockChainElements,
      );

      const calculatePrioritySpy = jest.spyOn(
        service as any,
        "calculatePriority",
      );

      await service.updatePriority(mockElement);

      expect(calculatePrioritySpy).not.toHaveBeenCalled();
    });
  });

  describe("calculatePriority", () => {
    it("should correctly count ordered elements and assign order number", async () => {
      const mockElementSchema: ElementSchema = {
        id: "element-1",
        type: { name: "ordered-type" } as any,
        properties: { priority: 0 },
      } as unknown as ElementSchema;

      const mockElement = {
        element: mockElementSchema,
        parentElementId: "parent-id",
      };

      const parentElement: ElementSchema = {
        id: "parent-id",
        children: [
          {
            id: "child-1",
            type: { name: "ordered-type" },
            properties: { priority: 0 },
          },
          {
            id: "child-2",
            type: { name: "ordered-type" },
            properties: { priority: 1 },
          },
        ] as any,
      } as unknown as ElementSchema;

      const orderedElements = [
        {
          id: "child-1",
          type: { name: "ordered-type" },
          properties: { priority: 0 },
        },
        {
          id: "child-2",
          type: { name: "ordered-type" },
          properties: { priority: 1 },
        },
      ];

      mockGetLibraryElementByType.mockResolvedValue({
        ordered: true,
      } as LibraryElement);

      const mockUtils = createMockUtils();
      mockUtils.element = mockElementSchema;
      mockUtils.extractOtherOrderedElements.mockReturnValue(orderedElements);
      mockUtils.getPriority
        .mockReturnValueOnce(0) // for child-1
        .mockReturnValueOnce(1); // for child-2

      (OrderedElementUtils.create as jest.Mock).mockResolvedValue(mockUtils);
      mockFindElementById.mockReturnValue({
        element: parentElement,
        parentId: undefined,
      });

      service = new OrderedElementService(
        mockChainFileUri,
        mockChainId,
        mockChainElements,
      );

      await (service as any).calculatePriority(mockElement);

      // Both elements have valid priorities (0, 1) in range [0, 2)
      // So orderNumber should be 2
      expect(mockUtils.updatePriority).toHaveBeenCalledWith(2);
    });

    it("should handle elements with out-of-range priorities", async () => {
      const mockElementSchema: ElementSchema = {
        id: "element-1",
        type: { name: "ordered-type" } as any,
        properties: { priority: 0 },
      } as unknown as ElementSchema;

      const mockElement = {
        element: mockElementSchema,
        parentElementId: "parent-id",
      };

      const parentElement: ElementSchema = {
        id: "parent-id",
        children: [
          {
            id: "child-1",
            type: { name: "ordered-type" },
            properties: { priority: 5 },
          }, // out of range
          {
            id: "child-2",
            type: { name: "ordered-type" },
            properties: { priority: 1 },
          },
        ] as any,
      } as unknown as ElementSchema;

      const orderedElements = [
        {
          id: "child-1",
          type: { name: "ordered-type" },
          properties: { priority: 5 },
        },
        {
          id: "child-2",
          type: { name: "ordered-type" },
          properties: { priority: 1 },
        },
      ];

      mockGetLibraryElementByType.mockResolvedValue({
        ordered: true,
      } as LibraryElement);

      const mockUtils = createMockUtils();
      mockUtils.element = mockElementSchema;
      mockUtils.extractOtherOrderedElements.mockReturnValue(orderedElements);
      mockUtils.getPriority
        .mockReturnValueOnce(5) // out of range
        .mockReturnValueOnce(1); // valid

      (OrderedElementUtils.create as jest.Mock).mockResolvedValue(mockUtils);
      mockFindElementById.mockReturnValue({
        element: parentElement,
        parentId: undefined,
      });

      service = new OrderedElementService(
        mockChainFileUri,
        mockChainId,
        mockChainElements,
      );

      await (service as any).calculatePriority(mockElement);

      // Only 1 element has valid priority in range
      expect(mockUtils.updatePriority).toHaveBeenCalledWith(1);
    });
  });

  describe("isOrdered", () => {
    it("should return true when library element is ordered and parentElementId is not null", async () => {
      const mockElementSchema: ElementSchema = {
        type: { name: "ordered-type" } as any,
      } as unknown as ElementSchema;

      const mockElement = {
        element: mockElementSchema,
        parentElementId: "parent-id",
      };

      mockGetLibraryElementByType.mockResolvedValue({
        ordered: true,
      } as LibraryElement);

      const result = await OrderedElementService.isOrdered(mockElement);

      expect(result).toBe(true);
    });

    it("should return false when library element is not ordered", async () => {
      const mockElementSchema: ElementSchema = {
        type: { name: "non-ordered-type" } as any,
      } as unknown as ElementSchema;

      const mockElement = {
        element: mockElementSchema,
        parentElementId: "parent-id",
      };

      mockGetLibraryElementByType.mockResolvedValue({
        ordered: false,
      } as LibraryElement);

      const result = await OrderedElementService.isOrdered(mockElement);

      expect(result).toBe(false);
    });

    it("should return false when parentElementId is null", async () => {
      const mockElementSchema: ElementSchema = {
        type: { name: "ordered-type" } as any,
        parentElementId: undefined,
      } as unknown as ElementSchema;

      const mockElement = {
        element: mockElementSchema,
        parentElementId: undefined,
      };

      mockGetLibraryElementByType.mockResolvedValue({
        ordered: true,
      } as LibraryElement);

      const result = await OrderedElementService.isOrdered(mockElement);

      expect(result).toBe(false);
    });
  });

  describe("removeElementIfOrderedAndMergeDiff", () => {
    it("should merge diff without duplicates", async () => {
      const elementToRemoveSchema: ElementSchema = {
        id: "element-1",
        type: { name: "ordered-type" } as any,
        properties: { priority: 1 },
      } as unknown as ElementSchema;

      const elementToRemove = {
        element: elementToRemoveSchema,
        parentElementId: "parent-id",
      };

      const parentElement: ElementSchema = {
        id: "parent-id",
        children: [
          {
            id: "elem-0",
            type: { name: "ordered-type" },
            properties: { priority: 0 },
          },
          {
            id: "element-1",
            type: { name: "ordered-type" },
            properties: { priority: 1 },
          },
          {
            id: "elem-2",
            type: { name: "ordered-type" },
            properties: { priority: 2 },
          },
        ] as any,
      } as unknown as ElementSchema;

      mockGetLibraryElementByType.mockResolvedValue({
        ordered: true,
      } as LibraryElement);

      const mockUtils = createMockUtils();
      mockUtils.element = elementToRemoveSchema;
      mockUtils.extractSortedOrderedElements.mockReturnValue([
        {
          id: "elem-0",
          type: { name: "ordered-type" },
          properties: { priority: 0 },
        },
        {
          id: "element-1",
          type: { name: "ordered-type" },
          properties: { priority: 1 },
        },
        {
          id: "elem-2",
          type: { name: "ordered-type" },
          properties: { priority: 2 },
        },
      ]);
      mockUtils.getPriority
        .mockReturnValueOnce(1) // For utils.getPriority() - the element's own priority
        .mockReturnValueOnce(2); // For the priority of elem-2 in the loop

      mockUtils.getIndex.mockReturnValue(1); // Finds element-1 at index 1

      mockParseElement.mockImplementation((uri, element) =>
        Promise.resolve({ id: element.id, type: element.type?.name } as any),
      );

      mockFindElementById.mockReturnValue({
        element: parentElement,
        parentId: undefined,
      });

      (OrderedElementUtils.create as jest.Mock).mockResolvedValue(mockUtils);

      service = new OrderedElementService(
        mockChainFileUri,
        mockChainId,
        mockChainElements,
      );

      const chainDiff: ActionDifference = { updatedElements: [] } as any;

      await service.removeElementIfOrderedAndMergeDiff(
        elementToRemove,
        chainDiff,
      );

      expect(chainDiff.updatedElements).toHaveLength(1);
    });

    it("should not add duplicates to chainDiff", async () => {
      const elementToRemoveSchema: ElementSchema = {
        id: "element-1",
        type: { name: "ordered-type" } as any,
        properties: { priority: 1 },
      } as unknown as ElementSchema;

      const elementToRemove = {
        element: elementToRemoveSchema,
        parentElementId: "parent-id",
      };

      const parentElement: ElementSchema = {
        id: "parent-id",
        children: [
          {
            id: "element-1",
            type: { name: "ordered-type" } as any,
            properties: { priority: 1 },
          },
          {
            id: "elem-2",
            type: { name: "ordered-type" },
            properties: { priority: 2 },
          },
        ] as any,
      } as unknown as ElementSchema;

      mockGetLibraryElementByType.mockResolvedValue({
        ordered: true,
      } as LibraryElement);

      const mockUtils = createMockUtils();
      mockUtils.element = elementToRemoveSchema;
      // Include the element being removed in the sorted list
      mockUtils.extractSortedOrderedElements.mockReturnValue([
        {
          id: "element-1",
          type: { name: "ordered-type" } as any,
          properties: { priority: 1 },
        },
        {
          id: "elem-2",
          type: { name: "ordered-type" },
          properties: { priority: 2 },
        },
      ]);
      mockUtils.getPriority.mockReturnValue(1);
      // Element-1 (being removed) is at index 0
      mockUtils.getIndex.mockReturnValue(0);

      mockParseElement.mockImplementation((uri, element) =>
        Promise.resolve({ id: element.id, type: element.type?.name } as any),
      );

      mockFindElementById.mockReturnValue({
        element: parentElement,
        parentId: undefined,
      });

      // Mock OrderedElementUtils.create to return our mock
      (OrderedElementUtils.create as jest.Mock).mockResolvedValue(mockUtils);

      service = new OrderedElementService(
        mockChainFileUri,
        mockChainId,
        mockChainElements,
      );

      const existingElement = { id: "element-1", type: "ordered-type" };
      const chainDiff: ActionDifference = {
        updatedElements: [existingElement],
      } as any;

      await service.removeElementIfOrderedAndMergeDiff(
        elementToRemove,
        chainDiff,
      );

      // After removing element-1 at index 0, elem-2 gets priority decremented
      // But elem-2's id ("elem-2") is NOT "element-1", so no duplicate
      expect(chainDiff.updatedElements).toHaveLength(2);
    });

    it("should handle element with priority at the end", async () => {
      const elementToRemoveSchema: ElementSchema = {
        id: "element-1",
        type: { name: "ordered-type" } as any,
        parentElementId: "parent-id",
        properties: { priority: 2 },
      } as unknown as ElementSchema;

      const elementToRemove = {
        element: elementToRemoveSchema,
        parentElementId: "parent-id",
      };

      const parentElement: ElementSchema = {
        id: "parent-id",
        children: [
          {
            id: "elem-1",
            type: { name: "ordered-type" },
            properties: { priority: 0 },
          },
          {
            id: "elem-2",
            type: { name: "ordered-type" },
            properties: { priority: 1 },
          },
        ] as any,
      } as unknown as ElementSchema;

      mockGetLibraryElementByType.mockResolvedValue({
        ordered: true,
      } as LibraryElement);

      const mockUtils = createMockUtils();
      mockUtils.element = elementToRemoveSchema;
      mockUtils.extractSortedOrderedElements.mockReturnValue([
        {
          id: "elem-1",
          type: { name: "ordered-type" },
          properties: { priority: 0 },
        },
        {
          id: "elem-2",
          type: { name: "ordered-type" },
          properties: { priority: 1 },
        },
      ]);
      mockUtils.getPriority.mockReturnValue(2);
      mockUtils.getIndex.mockReturnValue(-1); // element is at end, not found in sorted

      (OrderedElementUtils.create as jest.Mock).mockResolvedValue(mockUtils);

      service = new OrderedElementService(
        mockChainFileUri,
        mockChainId,
        mockChainElements,
      );

      const chainDiff: ActionDifference = { updatedElements: [] } as any;

      await service.removeElementIfOrderedAndMergeDiff(
        elementToRemove,
        chainDiff,
      );

      // Should not update anything when element is at the end
      expect(chainDiff.updatedElements).toHaveLength(0);
    });

    it("should do nothing when element is not ordered", async () => {
      const mockElementSchema = {
        id: "element-1",
        type: { name: "non-ordered-type" } as any,
        properties: {},
      } as unknown as ElementSchema;

      const mockElement = {
        element: mockElementSchema,
        parentElementId: "parent-id",
      };

      mockGetLibraryElementByType.mockResolvedValue({
        ordered: false,
      } as LibraryElement);

      service = new OrderedElementService(
        mockChainFileUri,
        mockChainId,
        mockChainElements,
      );

      const chainDiff: ActionDifference = { updatedElements: [] } as any;

      await service.removeElementIfOrderedAndMergeDiff(mockElement, chainDiff);

      expect(chainDiff.updatedElements).toHaveLength(0);
    });
  });

  describe("updateProperties", () => {
    it("should return undefined when parentElementId has not changed", async () => {
      const mockElementSchema = {
        id: "element-1",
        type: { name: "ordered-type" } as any,
        properties: {},
      } as unknown as ElementSchema;

      const mockElement = {
        element: mockElementSchema,
        parentElementId: "parent-id",
      };

      const elementRequest = {
        parentElementId: "parent-id",
        properties: {},
      };

      service = new OrderedElementService(
        mockChainFileUri,
        mockChainId,
        mockChainElements,
      );

      const result = await service.updateProperties(
        mockElement,
        elementRequest as any,
      );

      expect(result).toBeUndefined();
    });

    it("should return undefined when element is not ordered", async () => {
      const mockElementSchema = {
        id: "element-1",
        type: { name: "non-ordered-type" } as any,
        properties: {},
      } as unknown as ElementSchema;

      const mockElement = {
        element: mockElementSchema,
        parentElementId: "parent-id",
      };

      const elementRequest = {
        parentElementId: "parent-id",
        properties: { priority: 2 },
      };

      mockGetLibraryElementByType.mockResolvedValue({
        ordered: false,
      } as LibraryElement);

      service = new OrderedElementService(
        mockChainFileUri,
        mockChainId,
        mockChainElements,
      );

      const result = await service.updateProperties(
        mockElement,
        elementRequest as any,
      );

      expect(result).toBeUndefined();
    });

    it("should call changePriority when priority changes on ordered element", async () => {
      const mockElementSchema = {
        id: "element-1",
        type: { name: "ordered-type" } as any,
        properties: {},
      } as unknown as ElementSchema;

      const mockElement = {
        element: mockElementSchema,
        parentElementId: "parent-id",
      };

      const elementRequest = {
        parentElementId: "parent-id",
        properties: { priority: 2 },
      };

      mockGetLibraryElementByType.mockResolvedValue({
        ordered: true,
      } as LibraryElement);

      const mockUtils = createMockUtils();
      mockUtils.element = mockElementSchema;
      mockUtils.getPriorityOrUndefined.mockReturnValue(2);

      // Mock for OrderedElementUtils.create call inside updateProperties
      (OrderedElementUtils.create as jest.Mock).mockResolvedValue(mockUtils);
      // Spy on static isOrdered method
      const isOrderedSpy = jest
        .spyOn(OrderedElementService, "isOrdered")
        .mockResolvedValue(true);

      service = new OrderedElementService(
        mockChainFileUri,
        mockChainId,
        mockChainElements,
      );

      const changePrioritySpy = jest.spyOn(service as any, "changePriority");

      await service.updateProperties(mockElement, elementRequest as any);

      expect(isOrderedSpy).toHaveBeenCalledWith(mockElement);
      expect(changePrioritySpy).toHaveBeenCalledWith(mockUtils, 2);
    });

    describe("updateProperties cases", () => {
      // Parameterized tests for priority change scenarios
      const priorityChangeCases: Array<
        [
          string,
          {
            existingElements: { id: string; priority: number }[];
            targetElementId: string;
            newPriority: number;
            expectedUpdates: { id: string; priority: number }[];
          },
        ]
      > = [
        [
          "should not change priority of other elements when moving to non-existent priority",
          {
            existingElements: [
              { id: "elem-1", priority: 0 },
              { id: "elem-2", priority: 1 },
            ],
            targetElementId: "elem-1",
            newPriority: 5,
            expectedUpdates: [],
          },
        ],
        [
          "should not change priorities when moving to higher non-overlapping priority",
          {
            existingElements: [
              { id: "elem-1", priority: 0 },
              { id: "elem-2", priority: 1 },
              { id: "elem-3", priority: 40 },
            ],
            targetElementId: "elem-2",
            newPriority: 3,
            expectedUpdates: [],
          },
        ],
        [
          "should not change priorities when moving to lower non-overlapping priority",
          {
            existingElements: [
              { id: "elem-1", priority: 0 },
              { id: "elem-2", priority: 2 },
              { id: "elem-3", priority: 40 },
            ],
            targetElementId: "elem-2",
            newPriority: 1,
            expectedUpdates: [],
          },
        ],
        [
          "should not change priorities with unordered initial priorities",
          {
            existingElements: [
              { id: "elem-1", priority: 1 },
              { id: "elem-2", priority: 0 },
              { id: "elem-3", priority: 40 },
            ],
            targetElementId: "elem-2",
            newPriority: 2,
            expectedUpdates: [],
          },
        ],
        [
          "should shift priorities when moving to existing priority",
          {
            existingElements: [
              { id: "elem-1", priority: 1 },
              { id: "elem-2", priority: 39 },
              { id: "elem-3", priority: 40 },
              { id: "elem-4", priority: 38 },
            ],
            targetElementId: "elem-4",
            newPriority: 40,
            expectedUpdates: [
              { id: "elem-3", priority: 39 },
              { id: "elem-2", priority: 38 },
            ],
          },
        ],
      ];

      test.each(priorityChangeCases)("%s", async (description, testData) => {
        const {
          existingElements,
          targetElementId,
          newPriority,
          expectedUpdates,
        } = testData;

        const orderedElements: ElementSchema[] = existingElements.map(
          (elem) =>
            ({
              id: elem.id,
              type: "ordered-type" as unknown as DataType,
              properties: { priority: elem.priority },
            }) as unknown as ElementSchema,
        );

        const mockElementSchema = orderedElements.find(
          (elem) => elem.id === targetElementId,
        )!;

        const mockElement = {
          element: mockElementSchema,
          parentElementId: "parent-id",
        };

        const elementRequest = {
          parentElementId: "parent-id",
          properties: { priority: newPriority },
        };

        mockGetLibraryElementByType.mockResolvedValue({
          ordered: true,
        } as LibraryElement);

        const { OrderedElementUtils: ActualUtils } = jest.requireActual(
          "../../../src/web/api-services/OrderedElementUtils",
        );

        const utils = await ActualUtils.create(mockElement);

        (OrderedElementUtils.create as jest.Mock).mockResolvedValue(utils);
        const updatePrioritySpy = jest.spyOn(utils, "updatePriority");

        mockFindElementById.mockImplementation(
          (elements, elementId: string) => {
            if (elementId === "parent-id") {
              const parentElement: ElementSchema = {
                id: "parent-id",
                children: orderedElements,
              } as unknown as ElementSchema;

              return { element: parentElement, parentId: undefined };
            }
            return {
              element: { id: elementId } as unknown as ElementSchema,
              parentId: undefined,
            };
          },
        );

        const isOrderedSpy = jest
          .spyOn(OrderedElementService, "isOrdered")
          .mockResolvedValue(true);

        service = new OrderedElementService(
          mockChainFileUri,
          mockChainId,
          mockChainElements,
        );

        const result = await service.updateProperties(
          mockElement,
          elementRequest as any,
        );

        // Verify updatePriority calls for expected updates
        const updatePriorityCalls = updatePrioritySpy.mock.calls;

        // First call should be for the target element's new priority
        expect(updatePriorityCalls[0]).toEqual([newPriority]);

        // Check other priority updates
        if (expectedUpdates.length > 0) {
          const otherCalls = updatePriorityCalls.slice(1);
          expectedUpdates.forEach((expected, index) => {
            // Find the call that corresponds to this expected update
            const call = otherCalls.find(
              (c: any[]) =>
                expected.priority === c[0] &&
                orderedElements.some((e: any) => e.id === expected.id),
            );
            expect(call).toBeTruthy();
          });
        } else {
          // No other updates expected
          expect(updatePrioritySpy).toHaveBeenCalledTimes(1);
        }

        expect(isOrderedSpy).toHaveBeenCalledWith(mockElement);
      });
    });
  });
});
