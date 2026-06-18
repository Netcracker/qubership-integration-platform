/**
 * @jest-environment jsdom
 */

import { renderHook } from "@testing-library/react";
import { usePriorityContextMenuItems } from "../../../../src/hooks/graph/context_menu/usePriorityContextMenuItems";
import { api } from "../../../../src/api/api";
import { LibraryElement } from "../../../../src/api/apiTypes";
import { Node } from "@xyflow/react";
import { ChainGraphNodeData } from "../../../../src/components/graph/nodes/ChainGraphNodeTypes";
import {
  LibraryContextData,
  useLibraryContext,
} from "../../../../src/components/LibraryContext";
import { findLibraryElement } from "../../../../src/misc/chain-graph-utils";
import { ContextMenuItemsHookProps } from "../../../../src/hooks/graph/context_menu/useContextMenu";

jest.mock("../../../../src/api/api");
jest.mock("../../../../src/components/LibraryContext");
jest.mock("../../../../src/misc/chain-graph-utils");

const mockApi = api as jest.Mocked<typeof api>;
const mockUseLibraryContext = useLibraryContext as jest.MockedFunction<
  typeof useLibraryContext
>;
const mockFindLibraryElement = findLibraryElement as jest.MockedFunction<
  typeof findLibraryElement
>;

const createMockNode = (
  id: string,
  elementType: string,
  parentId: string | undefined,
  priorityProperty: string,
  priority: number,
): Node<ChainGraphNodeData> => ({
  id,
  type: "chainNode",
  data: {
    elementType,
    label: `Node ${id}`,
    description: `Description ${id}`,
    properties: {
      [priorityProperty]: priority,
    } as unknown as LibraryElement["properties"],
  },
  parentId,
  position: { x: 0, y: 0 },
});

const createMockLibraryElement = (
  type: string,
  ordered: boolean,
  priorityProperty?: string,
): LibraryElement =>
  ({
    type,
    ordered,
    priorityProperty,
  }) as unknown as LibraryElement;

describe("usePriorityContextMenuItems", () => {
  const mockUpdateNodeData = jest.fn();
  const chainId = "test-chain-id";
  const priorityProperty = "priority";

  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe("buildItems", () => {
    it("should return empty array when multiple elements are selected", () => {
      const nodes = [
        createMockNode("1", "http-trigger", "parent-1", priorityProperty, 0),
      ];
      const mockLibraryElement = createMockLibraryElement(
        "http-trigger",
        true,
        priorityProperty,
      );

      mockUseLibraryContext.mockReturnValue({
        libraryElements: [mockLibraryElement],
        isLibraryLoading: false,
      } as LibraryContextData);

      mockFindLibraryElement.mockReturnValue(mockLibraryElement);

      const { result } = renderHook(() =>
        usePriorityContextMenuItems({
          nodes,
          chainId,
          updateNodeData: mockUpdateNodeData,
        } as unknown as ContextMenuItemsHookProps),
      );

      const contextMenuItems = result.current.buildItems(nodes);

      expect(contextMenuItems).toEqual([]);
    });

    it("should return empty array when node has no parent", () => {
      const nodes = [
        createMockNode("1", "http-trigger", undefined, priorityProperty, 0),
      ];
      const mockLibraryElement = createMockLibraryElement(
        "http-trigger",
        true,
        priorityProperty,
      );

      mockUseLibraryContext.mockReturnValue({
        libraryElements: [mockLibraryElement],
        isLibraryLoading: false,
      } as LibraryContextData);

      mockFindLibraryElement.mockReturnValue(mockLibraryElement);

      const { result } = renderHook(() =>
        usePriorityContextMenuItems({
          nodes,
          chainId,
          updateNodeData: mockUpdateNodeData,
        } as unknown as ContextMenuItemsHookProps),
      );

      const contextMenuItems = result.current.buildItems([nodes[0]]);

      expect(contextMenuItems).toEqual([]);
    });

    it("should return empty array when library element is not ordered", () => {
      const nodes = [
        createMockNode("1", "http-trigger", "parent-1", priorityProperty, 0),
      ];
      const mockLibraryElement = createMockLibraryElement(
        "http-trigger",
        false,
        priorityProperty,
      );

      mockUseLibraryContext.mockReturnValue({
        libraryElements: [mockLibraryElement],
        isLibraryLoading: false,
      } as LibraryContextData);

      mockFindLibraryElement.mockReturnValue(mockLibraryElement);

      const { result } = renderHook(() =>
        usePriorityContextMenuItems({
          nodes,
          chainId,
          updateNodeData: mockUpdateNodeData,
        } as unknown as ContextMenuItemsHookProps),
      );

      const contextMenuItems = result.current.buildItems([nodes[0]]);

      expect(contextMenuItems).toEqual([]);
    });

    it("should return move up item when element can be moved up", () => {
      const nodes = [
        createMockNode("1", "http-trigger", "parent-1", priorityProperty, 1),
        createMockNode("2", "http-trigger", "parent-1", priorityProperty, 0),
      ];
      const mockLibraryElement = createMockLibraryElement(
        "http-trigger",
        true,
        priorityProperty,
      );

      mockUseLibraryContext.mockReturnValue({
        libraryElements: [mockLibraryElement],
        isLibraryLoading: false,
      } as LibraryContextData);

      mockFindLibraryElement.mockReturnValue(mockLibraryElement);
      mockApi.updateElement.mockResolvedValue({ updatedElements: [] });

      const { result } = renderHook(() =>
        usePriorityContextMenuItems({
          nodes,
          chainId,
          updateNodeData: mockUpdateNodeData,
        } as unknown as ContextMenuItemsHookProps),
      );

      const contextMenuItems = result.current.buildItems([nodes[0]]);

      expect(contextMenuItems).toHaveLength(1);
      expect(contextMenuItems[0].text).toBe("Move up (New priority: 0)");
    });

    it("should return move down item when element can be moved down", () => {
      const nodes = [
        createMockNode("1", "http-trigger", "parent-1", priorityProperty, 0),
        createMockNode("2", "http-trigger", "parent-1", priorityProperty, 1),
      ];
      const mockLibraryElement = createMockLibraryElement(
        "http-trigger",
        true,
        priorityProperty,
      );

      mockUseLibraryContext.mockReturnValue({
        libraryElements: [mockLibraryElement],
        isLibraryLoading: false,
      } as LibraryContextData);

      mockFindLibraryElement.mockReturnValue(mockLibraryElement);
      mockApi.updateElement.mockResolvedValue({ updatedElements: [] });

      const { result } = renderHook(() =>
        usePriorityContextMenuItems({
          nodes,
          chainId,
          updateNodeData: mockUpdateNodeData,
        } as unknown as ContextMenuItemsHookProps),
      );

      const contextMenuItems = result.current.buildItems([nodes[0]]);

      expect(contextMenuItems).toHaveLength(1);
      expect(contextMenuItems[0].text).toBe("Move down (New priority: 1)");
    });

    it("should return both move up and move down items when element is in the middle", () => {
      const nodes = [
        createMockNode("1", "http-trigger", "parent-1", priorityProperty, 0),
        createMockNode("2", "http-trigger", "parent-1", priorityProperty, 1),
        createMockNode("3", "http-trigger", "parent-1", priorityProperty, 2),
      ];
      const mockLibraryElement = createMockLibraryElement(
        "http-trigger",
        true,
        priorityProperty,
      );

      mockUseLibraryContext.mockReturnValue({
        libraryElements: [mockLibraryElement],
        isLibraryLoading: false,
      } as LibraryContextData);

      mockFindLibraryElement.mockReturnValue(mockLibraryElement);
      mockApi.updateElement.mockResolvedValue({ updatedElements: [] });

      const { result } = renderHook(() =>
        usePriorityContextMenuItems({
          nodes,
          chainId,
          updateNodeData: mockUpdateNodeData,
        } as unknown as ContextMenuItemsHookProps),
      );

      const contextMenuItems = result.current.buildItems([nodes[1]]);

      expect(contextMenuItems).toHaveLength(2);
      expect(contextMenuItems[0].text).toBe("Move up (New priority: 0)");
      expect(contextMenuItems[1].text).toBe("Move down (New priority: 2)");
    });

    it("should not show move up item when element is at the top", () => {
      const nodes = [
        createMockNode("1", "http-trigger", "parent-1", priorityProperty, 0),
        createMockNode("2", "http-trigger", "parent-1", priorityProperty, 1),
      ];
      const mockLibraryElement = createMockLibraryElement(
        "http-trigger",
        true,
        priorityProperty,
      );

      mockUseLibraryContext.mockReturnValue({
        libraryElements: [mockLibraryElement],
        isLibraryLoading: false,
      } as LibraryContextData);

      mockFindLibraryElement.mockReturnValue(mockLibraryElement);
      mockApi.updateElement.mockResolvedValue({ updatedElements: [] });

      const { result } = renderHook(() =>
        usePriorityContextMenuItems({
          nodes,
          chainId,
          updateNodeData: mockUpdateNodeData,
        } as unknown as ContextMenuItemsHookProps),
      );

      const contextMenuItems = result.current.buildItems([nodes[0]]);

      expect(contextMenuItems).toHaveLength(1);
      expect(contextMenuItems[0].text).toBe("Move down (New priority: 1)");
    });

    it("should not show move down item when element is at the bottom", () => {
      const nodes = [
        createMockNode("1", "http-trigger", "parent-1", priorityProperty, 0),
        createMockNode("2", "http-trigger", "parent-1", priorityProperty, 1),
      ];
      const mockLibraryElement = createMockLibraryElement(
        "http-trigger",
        true,
        priorityProperty,
      );

      mockUseLibraryContext.mockReturnValue({
        libraryElements: [mockLibraryElement],
        isLibraryLoading: false,
      } as LibraryContextData);

      mockFindLibraryElement.mockReturnValue(mockLibraryElement);
      mockApi.updateElement.mockResolvedValue({ updatedElements: [] });

      const { result } = renderHook(() =>
        usePriorityContextMenuItems({
          nodes,
          chainId,
          updateNodeData: mockUpdateNodeData,
        } as unknown as ContextMenuItemsHookProps),
      );

      const contextMenuItems = result.current.buildItems([nodes[1]]);

      expect(contextMenuItems).toHaveLength(1);
      expect(contextMenuItems[0].text).toBe("Move up (New priority: 0)");
    });

    it("should use default priority property name when library element does not specify one", () => {
      const nodes = [
        createMockNode("1", "http-trigger", "parent-1", "priority", 0),
        createMockNode("2", "http-trigger", "parent-1", "priority", 1),
      ];
      const mockLibraryElement = createMockLibraryElement("http-trigger", true);

      mockUseLibraryContext.mockReturnValue({
        libraryElements: [mockLibraryElement],
        isLibraryLoading: false,
      } as LibraryContextData);

      mockFindLibraryElement.mockReturnValue(mockLibraryElement);
      mockApi.updateElement.mockResolvedValue({ updatedElements: [] });

      const { result } = renderHook(() =>
        usePriorityContextMenuItems({
          nodes,
          chainId,
          updateNodeData: mockUpdateNodeData,
        } as unknown as ContextMenuItemsHookProps),
      );

      const contextMenuItems = result.current.buildItems([nodes[0]]);

      expect(contextMenuItems).toHaveLength(1);
      expect(contextMenuItems[0].text).toBe("Move down (New priority: 1)");
    });

    it("should only consider children of the same element type", () => {
      const nodes = [
        createMockNode("1", "http-trigger", "parent-1", priorityProperty, 0),
        createMockNode("2", "http-trigger", "parent-1", priorityProperty, 1),
        createMockNode(
          "3",
          "different-trigger",
          "parent-1",
          priorityProperty,
          2,
        ),
      ];
      const mockLibraryElement = createMockLibraryElement(
        "http-trigger",
        true,
        priorityProperty,
      );

      mockUseLibraryContext.mockReturnValue({
        libraryElements: [mockLibraryElement],
        isLibraryLoading: false,
      } as LibraryContextData);

      mockFindLibraryElement.mockReturnValue(mockLibraryElement);
      mockApi.updateElement.mockResolvedValue({ updatedElements: [] });

      const { result } = renderHook(() =>
        usePriorityContextMenuItems({
          nodes,
          chainId,
          updateNodeData: mockUpdateNodeData,
        } as unknown as ContextMenuItemsHookProps),
      );

      const contextMenuItems = result.current.buildItems([nodes[0]]);

      expect(contextMenuItems).toHaveLength(1);
      expect(contextMenuItems[0].text).toBe("Move down (New priority: 1)");
    });

    it("should only consider children with the same parent", () => {
      const nodes = [
        createMockNode("1", "http-trigger", "parent-1", priorityProperty, 0),
        createMockNode("2", "http-trigger", "parent-1", priorityProperty, 1),
        createMockNode("3", "http-trigger", "parent-2", priorityProperty, 2),
      ];
      const mockLibraryElement = createMockLibraryElement(
        "http-trigger",
        true,
        priorityProperty,
      );

      mockUseLibraryContext.mockReturnValue({
        libraryElements: [mockLibraryElement],
        isLibraryLoading: false,
      } as LibraryContextData);

      mockFindLibraryElement.mockReturnValue(mockLibraryElement);
      mockApi.updateElement.mockResolvedValue({ updatedElements: [] });

      const { result } = renderHook(() =>
        usePriorityContextMenuItems({
          nodes,
          chainId,
          updateNodeData: mockUpdateNodeData,
        } as unknown as ContextMenuItemsHookProps),
      );

      const contextMenuItems = result.current.buildItems([nodes[0]]);

      expect(contextMenuItems).toHaveLength(1);
      expect(contextMenuItems[0].text).toBe("Move down (New priority: 1)");
    });
  });
});
