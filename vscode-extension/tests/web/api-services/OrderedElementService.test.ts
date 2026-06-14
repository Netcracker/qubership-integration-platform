import { Uri } from "vscode";
import { Element as ElementSchema } from "@netcracker/qip-schemas";
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
    extractOtherOrderedElements: jest.fn().mockReturnValue([]),
    extractSortedOrderedElements: jest.fn().mockReturnValue([]),
    getPriority: jest.fn().mockReturnValue(0),
    updatePriority: jest.fn(),
    getIndex: jest.fn().mockReturnValue(0),
    getPriorityOrUndefined: jest.fn().mockReturnValue(undefined),
    getIndexForNewPriority: jest.fn().mockReturnValue(-1),
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
      const mockElement: ElementSchema = {
        id: "element-1",
        type: { name: "ordered-type" } as any,
        parentElementId: "parent-id",
        properties: { priority: 0 },
      } as unknown as ElementSchema;

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
        parentElementId: "parent-id",
        properties: { priority: 0 },
      } as unknown as ElementSchema;

      const mockElement: ElementSchema = {
        id: "element-1",
        type: { name: "container-type" } as any,
        parentElementId: "parent-id",
        children: [orderedChild] as any,
        properties: {},
      } as unknown as ElementSchema;

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

      expect(calculatePrioritySpy).toHaveBeenCalledWith(orderedChild);
    });

    it("should do nothing when element is not ordered and has no container", async () => {
      const mockElement: ElementSchema = {
        id: "element-1",
        type: { name: "non-ordered-type" } as any,
        parentElementId: null,
        properties: {},
      } as unknown as ElementSchema;

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
      const mockElement: ElementSchema = {
        id: "element-1",
        type: { name: "ordered-type" } as any,
        parentElementId: "parent-id",
        properties: { priority: 0 },
      } as unknown as ElementSchema;

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
      const mockElement: ElementSchema = {
        id: "element-1",
        type: { name: "ordered-type" } as any,
        parentElementId: "parent-id",
        properties: { priority: 0 },
      } as unknown as ElementSchema;

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
      const mockElement: ElementSchema = {
        type: { name: "ordered-type" } as any,
        parentElementId: "parent-id",
      } as unknown as ElementSchema;

      mockGetLibraryElementByType.mockResolvedValue({
        ordered: true,
      } as LibraryElement);

      const result = await OrderedElementService.isOrdered(mockElement);

      expect(result).toBe(true);
    });

    it("should return false when library element is not ordered", async () => {
      const mockElement: ElementSchema = {
        type: { name: "non-ordered-type" } as any,
        parentElementId: "parent-id",
      } as unknown as ElementSchema;

      mockGetLibraryElementByType.mockResolvedValue({
        ordered: false,
      } as LibraryElement);

      const result = await OrderedElementService.isOrdered(mockElement);

      expect(result).toBe(false);
    });

    it("should return false when parentElementId is null", async () => {
      const mockElement: ElementSchema = {
        type: { name: "ordered-type" } as any,
        parentElementId: null,
      } as unknown as ElementSchema;

      mockGetLibraryElementByType.mockResolvedValue({
        ordered: true,
      } as LibraryElement);

      const result = await OrderedElementService.isOrdered(mockElement);

      expect(result).toBe(false);
    });
  });

  describe("changePriority", () => {
    it("should throw error when newPriority is negative", async () => {
      service = new OrderedElementService(
        mockChainFileUri,
        mockChainId,
        mockChainElements,
      );

      await expect(
        service.changePriority(createMockUtils() as any, -1),
      ).rejects.toThrow("Priority cannot be a negative number");
    });

    it("should return empty diff when priorities are equal", async () => {
      const mockUtils = createMockUtils();
      mockUtils.getPriority.mockReturnValue(1);
      mockUtils.extractSortedOrderedElements.mockReturnValue([]);

      (OrderedElementUtils.create as jest.Mock).mockResolvedValue(mockUtils);

      service = new OrderedElementService(
        mockChainFileUri,
        mockChainId,
        mockChainElements,
      );

      const result = await service.changePriority(mockUtils as any, 1);

      expect(result).toEqual({ updatedElements: [] });
    });

    it("should correctly reorder elements when moving priority up", async () => {
      const mockUtils = createMockUtils();
      const sortedElements = [
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
        {
          id: "elem-3",
          type: { name: "ordered-type" },
          properties: { priority: 2 },
        },
      ] as unknown as ElementSchema[];

      mockUtils.element = sortedElements[1];
      mockUtils.getPriority.mockReturnValue(1);
      mockUtils.extractSortedOrderedElements.mockReturnValue(sortedElements);
      mockUtils.getIndex
        .mockReturnValueOnce(1) // current index (priority 1)
        .mockReturnValueOnce(0); // new priority index (priority 0)

      mockParseElement.mockImplementation(() =>
        Promise.resolve({ id: "updated-elem", type: "ordered-type" } as any),
      );

      service = new OrderedElementService(
        mockChainFileUri,
        mockChainId,
        mockChainElements,
      );

      const result = await service.changePriority(mockUtils as any, 0);

      expect(mockUtils.updatePriority).toHaveBeenCalled();
      expect(result.updatedElements).toBeDefined();
    });

    it("should correctly reorder elements when moving priority down", async () => {
      const mockUtils = createMockUtils();
      const sortedElements = [
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
        {
          id: "elem-3",
          type: { name: "ordered-type" },
          properties: { priority: 2 },
        },
      ] as unknown as ElementSchema[];

      mockUtils.element = sortedElements[0];
      mockUtils.getPriority.mockReturnValue(0);
      mockUtils.extractSortedOrderedElements.mockReturnValue(sortedElements);
      mockUtils.getIndex.mockReturnValue(0); // current index

      mockParseElement.mockImplementation(() =>
        Promise.resolve({ id: "updated-elem", type: "ordered-type" } as any),
      );

      service = new OrderedElementService(
        mockChainFileUri,
        mockChainId,
        mockChainElements,
      );

      const result = await service.changePriority(mockUtils as any, 2);

      expect(mockUtils.updatePriority).toHaveBeenCalled();
      expect(result.updatedElements).toBeDefined();
    });

    it("should handle edge case when newPriorityIndex is -1 when moving down", async () => {
      const mockUtils = createMockUtils();
      const sortedElements = [
        {
          id: "elem-1",
          type: { name: "ordered-type" },
          properties: { priority: 0 },
        },
      ] as unknown as ElementSchema[];

      mockUtils.element = sortedElements[0];
      mockUtils.getPriority.mockReturnValue(0);
      mockUtils.extractSortedOrderedElements.mockReturnValue(sortedElements);
      mockUtils.getIndex
        .mockReturnValueOnce(0) // current index
        .mockReturnValueOnce(-1); // new priority index (not found)

      (OrderedElementUtils.create as jest.Mock).mockResolvedValue(mockUtils);

      service = new OrderedElementService(
        mockChainFileUri,
        mockChainId,
        mockChainElements,
      );

      const result = await service.changePriority(mockUtils as any, 0);

      expect(result).toEqual({ updatedElements: [] });
    });

    describe("changePriority, cases with priority gap", () => {
      it("[0, 1], should not change priority of second element when changing priority of first from 0 to 5", async () => {
        const mockUtils = createMockUtils();

        const sortedElements: ElementSchema[] = [
          {
            id: "elem-1",
            type: { name: "ordered-type" },
            parentElementId: "parent-id",
            properties: { priority: 0 },
          },
          {
            id: "elem-2",
            type: { name: "ordered-type" },
            parentElementId: "parent-id",
            properties: { priority: 1 },
          },
        ] as unknown as ElementSchema[];

        const parentElement: ElementSchema = {
          id: "parent-id",
          type: { name: "parent-type" } as any,
          parentElementId: null,
          properties: {},
          children: sortedElements as any,
        } as unknown as ElementSchema;

        mockFindElementById.mockReturnValue({
          element: parentElement,
          parentId: undefined,
        });

        mockUtils.element = sortedElements[0];
        mockUtils.getPriority.mockReturnValue(0);
        mockUtils.extractSortedOrderedElements.mockReturnValue(sortedElements);
        mockUtils.getIndex.mockReturnValue(0); // current index
        mockUtils.getIndexForNewPriority = jest.fn().mockReturnValue(1);

        mockParseElement.mockImplementation(() =>
          Promise.resolve({ id: "elem-1", type: "ordered-type" } as any),
        );

        service = new OrderedElementService(
          mockChainFileUri,
          mockChainId,
          mockChainElements,
        );

        const result = await service.changePriority(mockUtils as any, 5);

        // Only the first element should be updated, second element should not be changed
        expect(mockUtils.updatePriority).toHaveBeenCalled();
        expect(result.updatedElements).toHaveLength(1);
        expect(result.updatedElements?.[0].id).toBe("elem-1");
      });

      it("[0, 1, 40], should not change priorities of others when change priority of the second from 1 to 3", async () => {
        const mockUtils = createMockUtils();

        const sortedElements: ElementSchema[] = [
          {
            id: "elem-1",
            type: { name: "ordered-type" },
            parentElementId: "parent-id",
            properties: { priority: 0 },
          },
          {
            id: "elem-2",
            type: { name: "ordered-type" },
            parentElementId: "parent-id",
            properties: { priority: 1 },
          },
          {
            id: "elem-3",
            type: { name: "ordered-type" },
            parentElementId: "parent-id",
            properties: { priority: 40 },
          },
        ] as unknown as ElementSchema[];

        const parentElement: ElementSchema = {
          id: "parent-id",
          type: { name: "parent-type" } as any,
          parentElementId: null,
          properties: {},
          children: sortedElements as any,
        } as unknown as ElementSchema;

        mockFindElementById.mockReturnValue({
          element: parentElement,
          parentId: undefined,
        });

        mockUtils.element = sortedElements[1];
        mockUtils.getPriority.mockReturnValue(1);
        mockUtils.extractSortedOrderedElements.mockReturnValue(sortedElements);
        mockUtils.getIndex
          .mockReturnValueOnce(1) // current index (priority 1)
          .mockReturnValueOnce(3); // new priority index (priority 3)
        mockUtils.getIndexForNewPriority = jest.fn().mockReturnValue(3);

        mockParseElement.mockImplementation(() =>
          Promise.resolve({ id: "elem-2", type: "ordered-type" } as any),
        );

        service = new OrderedElementService(
          mockChainFileUri,
          mockChainId,
          mockChainElements,
        );

        const result = await service.changePriority(mockUtils as any, 3);

        // Only the second element should be updated, first and third elements should not be changed
        expect(mockUtils.updatePriority).toHaveBeenCalled();
        expect(result.updatedElements).toHaveLength(1);
        expect(result.updatedElements?.[0].id).toBe("elem-2");
      });

      it("[0, 2, 40], should not change priorities of others when change priority of the second from 2 to 1", async () => {
        const mockUtils = createMockUtils();

        const sortedElements: ElementSchema[] = [
          {
            id: "elem-1",
            type: { name: "ordered-type" },
            parentElementId: "parent-id",
            properties: { priority: 0 },
          },
          {
            id: "elem-2",
            type: { name: "ordered-type" },
            parentElementId: "parent-id",
            properties: { priority: 2 },
          },
          {
            id: "elem-3",
            type: { name: "ordered-type" },
            parentElementId: "parent-id",
            properties: { priority: 40 },
          },
        ] as unknown as ElementSchema[];

        const parentElement: ElementSchema = {
          id: "parent-id",
          type: { name: "parent-type" } as any,
          parentElementId: null,
          properties: {},
          children: sortedElements as any,
        } as unknown as ElementSchema;

        mockFindElementById.mockReturnValue({
          element: parentElement,
          parentId: undefined,
        });

        mockUtils.element = sortedElements[1];
        mockUtils.getPriority.mockReturnValue(2);
        mockUtils.extractSortedOrderedElements.mockReturnValue(sortedElements);
        mockUtils.getIndex
          .mockReturnValueOnce(2) // current index (priority 2)
          .mockReturnValueOnce(1); // new priority index (priority 1)
        mockUtils.getIndexForNewPriority = jest.fn().mockReturnValue(1);

        mockParseElement.mockImplementation(() =>
          Promise.resolve({ id: "elem-2", type: "ordered-type" } as any),
        );

        service = new OrderedElementService(
          mockChainFileUri,
          mockChainId,
          mockChainElements,
        );

        const result = await service.changePriority(mockUtils as any, 1);

        // Only the second element should be updated, first and third elements should not be changed
        expect(mockUtils.updatePriority).toHaveBeenCalled();
        expect(result.updatedElements).toHaveLength(1);
        expect(result.updatedElements?.[0].id).toBe("elem-2");
      });

      it("[1, 0, 40], should not change priorities of others when change priority of the second from 0 to 2", async () => {
        const mockUtils = createMockUtils();

        const sortedElements: ElementSchema[] = [
          {
            id: "elem-1",
            type: { name: "ordered-type" },
            parentElementId: "parent-id",
            properties: { priority: 1 },
          },
          {
            id: "elem-2",
            type: { name: "ordered-type" },
            parentElementId: "parent-id",
            properties: { priority: 0 },
          },
          {
            id: "elem-3",
            type: { name: "ordered-type" },
            parentElementId: "parent-id",
            properties: { priority: 40 },
          },
        ] as unknown as ElementSchema[];

        const parentElement: ElementSchema = {
          id: "parent-id",
          type: { name: "parent-type" } as any,
          parentElementId: null,
          properties: {},
          children: sortedElements as any,
        } as unknown as ElementSchema;

        mockFindElementById.mockReturnValue({
          element: parentElement,
          parentId: undefined,
        });

        mockUtils.element = sortedElements[1];
        mockUtils.getPriority.mockReturnValue(0);
        mockUtils.extractSortedOrderedElements.mockReturnValue(sortedElements);
        mockUtils.getIndex
          .mockReturnValueOnce(0) // current index (priority 0)
          .mockReturnValueOnce(2); // new priority index (priority 2)
        mockUtils.getIndexForNewPriority = jest.fn().mockReturnValue(2);

        mockParseElement.mockImplementation(() =>
          Promise.resolve({ id: "elem-2", type: "ordered-type" } as any),
        );

        service = new OrderedElementService(
          mockChainFileUri,
          mockChainId,
          mockChainElements,
        );

        const result = await service.changePriority(mockUtils as any, 2);

        // Only the second element should be updated, first and third elements should not be changed
        expect(mockUtils.updatePriority).toHaveBeenCalled();
        expect(result.updatedElements).toHaveLength(1);
        expect(result.updatedElements?.[0].id).toBe("elem-2");
      });
    });
  });

  describe("removeElementIfOrderedAndMergeDiff", () => {
    it("should merge diff without duplicates", async () => {
      const elementToRemove: ElementSchema = {
        id: "element-1",
        type: { name: "ordered-type" } as any,
        parentElementId: "parent-id",
        properties: { priority: 1 },
      } as unknown as ElementSchema;

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
      mockUtils.element = elementToRemove;
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
      const mockElement: ElementSchema = {
        id: "element-1", // This is the element being removed
        type: { name: "ordered-type" } as any,
        parentElementId: "parent-id",
        properties: { priority: 1 },
      } as unknown as ElementSchema;

      const parentElement: ElementSchema = {
        id: "parent-id",
        children: [
          {
            id: "element-1", // The element being removed should be here
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
      mockUtils.element = mockElement;
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

      await service.removeElementIfOrderedAndMergeDiff(mockElement, chainDiff);

      // After removing element-1 at index 0, elem-2 gets priority decremented
      // But elem-2's id ("elem-2") is NOT "element-1", so no duplicate
      expect(chainDiff.updatedElements).toHaveLength(2);
    });

    it("should handle element with priority at the end", async () => {
      const mockElement: ElementSchema = {
        id: "element-1",
        type: { name: "ordered-type" } as any,
        parentElementId: "parent-id",
        properties: { priority: 2 },
      } as unknown as ElementSchema;

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
      mockUtils.element = mockElement;
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

      await service.removeElementIfOrderedAndMergeDiff(mockElement, chainDiff);

      // Should not update anything when element is at the end
      expect(chainDiff.updatedElements).toHaveLength(0);
    });

    it("should do nothing when element is not ordered", async () => {
      const mockElement = {
        id: "element-1",
        type: { name: "non-ordered-type" } as any,
        parentElementId: "parent-id",
        properties: {},
      } as unknown as ElementSchema;

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
      const mockElement = {
        id: "element-1",
        type: { name: "ordered-type" } as any,
        parentElementId: "parent-id",
        properties: {},
      } as unknown as ElementSchema;

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
      const mockElement = {
        id: "element-1",
        type: { name: "non-ordered-type" } as any,
        parentElementId: "parent-id",
        properties: {},
      } as unknown as ElementSchema;

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
      const mockElement = {
        id: "element-1",
        type: { name: "ordered-type" } as any,
        parentElementId: "parent-id",
        properties: {},
      } as unknown as ElementSchema;

      const elementRequest = {
        parentElementId: "parent-id",
        properties: { priority: 2 },
      };

      mockGetLibraryElementByType.mockResolvedValue({
        ordered: true,
      } as LibraryElement);

      const mockUtils = createMockUtils();
      mockUtils.element = mockElement;
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
  });

  describe("getParentElement", () => {
    it("should return parent element by ID lookup", async () => {
      const parentElement = {
        id: "parent-id",
        type: { name: "parent-type" } as any,
        children: [],
        properties: {},
      } as unknown as ElementSchema;

      const mockElement = {
        id: "element-1",
        type: { name: "child-type" } as any,
        parentElementId: "parent-id",
        properties: {},
      };

      mockChainElements = [parentElement];
      mockFindElementById.mockReturnValue({
        element: parentElement,
        parentId: undefined,
      });

      service = new OrderedElementService(
        mockChainFileUri,
        mockChainId,
        mockChainElements,
      );

      // Access private method for testing
      const result = (service as any).getParentElement(mockElement);

      expect(mockFindElementById).toHaveBeenCalledWith(
        mockChainElements,
        "parent-id",
      );
      expect(result).toEqual(parentElement);
    });
  });
});
