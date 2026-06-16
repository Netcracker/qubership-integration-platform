/**
 * @jest-environment jsdom
 */
import { renderHook, waitFor } from "@testing-library/react";
import { useGroupUngroupContextMenuItems } from "../../../../src/hooks/graph/context_menu/useGroupUngroupContextMenuItems";
import { api } from "../../../../src/api/api";
import { useNotificationService } from "../../../../src/hooks/useNotificationService";
import { useAutoLayout } from "../../../../src/hooks/graph/useAutoLayout";
import { useExpandCollapse } from "../../../../src/hooks/graph/useExpandCollapse";
import { Element } from "../../../../src/api/apiTypes";
import { Node } from "@xyflow/react";
import { ChainGraphNodeData } from "../../../../src/components/graph/nodes/ChainGraphNodeTypes";
import { ContextMenuItemsHookProps } from "../../../../src/hooks/graph/context_menu/useContextMenu";

// Mock all dependencies
jest.mock("../../../../src/api/api");
jest.mock("../../../../src/hooks/useNotificationService");
jest.mock("../../../../src/hooks/graph/useAutoLayout");
jest.mock("../../../../src/hooks/graph/useExpandCollapse");
jest.mock("../../../../src/misc/chain-graph-utils", () => ({
  getNodeFromElement: jest.fn(),
  sortParentsBeforeChildren: jest.fn(),
}));
jest.mock("uuid", () => ({
  v4: jest.fn(() => "mock-uuid"),
}));

const mockApi = api as jest.Mocked<typeof api>;
const mockNotificationService = {
  requestFailed: jest.fn(),
  errorWithDetails: jest.fn(),
  info: jest.fn(),
  warning: jest.fn(),
};
const mockUseNotificationService =
  useNotificationService as jest.MockedFunction<typeof useNotificationService>;
const mockUseAutoLayout = useAutoLayout as jest.MockedFunction<
  typeof useAutoLayout
>;
const mockUseExpandCollapse = useExpandCollapse as jest.MockedFunction<
  typeof useExpandCollapse
>;

// Get mocked functions
const mockGetNodeFromElement = jest.requireMock(
  "../../../../src/misc/chain-graph-utils",
).getNodeFromElement;
const mockSortParentsBeforeChildren = jest.requireMock(
  "../../../../src/misc/chain-graph-utils",
).sortParentsBeforeChildren;

describe("useGroupUngroupContextMenuItems", () => {
  const mockSetNodes = jest.fn();
  const mockSetEdges = jest.fn();
  const mockStructureChanged = jest.fn();
  const chainId = "test-chain-id";

  const mockContainerNode: Node<ChainGraphNodeData> = {
    id: "container-1",
    type: "chainGraphNode",
    position: { x: 0, y: 0 },
    data: {
      label: "Container",
      elementType: "container",
      // Add other required ChainGraphNodeData properties based on actual type
    } as ChainGraphNodeData,
  };

  const mockHttpNode: Node<ChainGraphNodeData> = {
    id: "http-1",
    type: "chainGraphNode",
    position: { x: 100, y: 100 },
    data: {
      label: "HTTP Trigger",
      elementType: "http-trigger",
    } as ChainGraphNodeData,
    parentId: undefined,
  };

  const mockKafkaNode: Node<ChainGraphNodeData> = {
    id: "kafka-1",
    type: "chainGraphNode",
    position: { x: 200, y: 200 },
    data: {
      label: "Kafka Trigger",
      elementType: "kafka-trigger",
    } as ChainGraphNodeData,
    parentId: undefined,
  };

  const mockChildNode: Node<ChainGraphNodeData> = {
    id: "child-1",
    type: "chainGraphNode",
    position: { x: 150, y: 150 },
    data: {
      label: "Child",
      elementType: "http-trigger",
    } as ChainGraphNodeData,
    parentId: "container-1",
  };

  beforeEach(() => {
    jest.clearAllMocks();
    mockUseNotificationService.mockReturnValue(mockNotificationService);
    mockUseAutoLayout.mockReturnValue({
      arrangeNodes: jest.fn().mockResolvedValue([]),
      direction: "LR",
    } as unknown as ReturnType<typeof useAutoLayout>);
    mockUseExpandCollapse.mockReturnValue({
      attachToggle: jest.fn().mockReturnValue([]),
      setNestedUnitCounts: jest.fn().mockReturnValue([]),
    } as unknown as ReturnType<typeof useExpandCollapse>);
  });

  describe("buildItems", () => {
    it("should return Ungroup item when single container element is selected", () => {
      const { result } = renderHook(() =>
        useGroupUngroupContextMenuItems({
          nodes: [mockContainerNode],
          setNodes: mockSetNodes,
          edges: [],
          setEdges: mockSetEdges,
          chainId,
          structureChanged: mockStructureChanged,
        } as unknown as ContextMenuItemsHookProps),
      );

      const items = result.current.buildItems([mockContainerNode]);

      expect(items).toHaveLength(1);
      expect(items[0].text).toBe("Ungroup");
    });

    it("should return Group item when multiple non-parented elements are selected", () => {
      const { result } = renderHook(() =>
        useGroupUngroupContextMenuItems({
          nodes: [mockHttpNode, mockKafkaNode],
          setNodes: mockSetNodes,
          edges: [],
          setEdges: mockSetEdges,
          chainId,
          structureChanged: mockStructureChanged,
        } as unknown as ContextMenuItemsHookProps),
      );

      const items = result.current.buildItems([mockHttpNode, mockKafkaNode]);

      expect(items).toHaveLength(1);
      expect(items[0].text).toBe("Group");
    });

    it("should return empty array when no elements are selected", () => {
      const { result } = renderHook(() =>
        useGroupUngroupContextMenuItems({
          nodes: [mockHttpNode],
          setNodes: mockSetNodes,
          edges: [],
          setEdges: mockSetEdges,
          chainId,
          structureChanged: mockStructureChanged,
        } as unknown as ContextMenuItemsHookProps),
      );

      const items = result.current.buildItems([]);

      expect(items).toHaveLength(0);
    });

    it("should return empty array when single non-container element is selected", () => {
      const { result } = renderHook(() =>
        useGroupUngroupContextMenuItems({
          nodes: [mockHttpNode],
          setNodes: mockSetNodes,
          edges: [],
          setEdges: mockSetEdges,
          chainId,
          structureChanged: mockStructureChanged,
        } as unknown as ContextMenuItemsHookProps),
      );

      const items = result.current.buildItems([mockHttpNode]);

      expect(items).toHaveLength(0);
    });

    it("should return empty array when selected elements have parent", () => {
      const { result } = renderHook(() =>
        useGroupUngroupContextMenuItems({
          nodes: [mockChildNode, mockHttpNode],
          setNodes: mockSetNodes,
          edges: [],
          setEdges: mockSetEdges,
          chainId,
          structureChanged: mockStructureChanged,
        } as unknown as ContextMenuItemsHookProps),
      );

      const items = result.current.buildItems([mockChildNode, mockHttpNode]);

      expect(items).toHaveLength(0);
    });
  });

  describe("groupElements", () => {
    it("should call api.groupElements and update nodes when elements are grouped successfully", async () => {
      const childElement1: Element = {
        id: "http-1",
        type: "http-trigger",
        name: "HTTP",
      } as Element;
      const childElement2: Element = {
        id: "kafka-1",
        type: "kafka-trigger",
        name: "Kafka",
      } as Element;
      const groupedContainer = {
        id: "container-1",
        type: "container",
        name: "Group",
        description: "",
        children: [childElement1, childElement2],
      };

      mockApi.groupElements.mockResolvedValue(groupedContainer as any);
      const mockArrangeNodes = jest
        .fn()
        .mockResolvedValue([mockContainerNode, mockHttpNode, mockKafkaNode]);
      mockUseAutoLayout.mockReturnValue({
        arrangeNodes: mockArrangeNodes,
        direction: "LR",
      } as unknown as ReturnType<typeof useAutoLayout>);

      mockGetNodeFromElement.mockReturnValue(mockContainerNode);

      const { result } = renderHook(() =>
        useGroupUngroupContextMenuItems({
          nodes: [mockHttpNode, mockKafkaNode],
          setNodes: mockSetNodes,
          edges: [],
          setEdges: mockSetEdges,
          chainId,
          structureChanged: mockStructureChanged,
        } as unknown as ContextMenuItemsHookProps),
      );

      const groupHandler = result.current.buildItems([
        mockHttpNode,
        mockKafkaNode,
      ])[0].handler;
      await groupHandler();

      await waitFor(() => {
        expect(mockApi.groupElements).toHaveBeenCalledWith(chainId, [
          "http-1",
          "kafka-1",
        ]);
        expect(mockSetNodes).toHaveBeenCalled();
        expect(mockStructureChanged).toHaveBeenCalled();
      });
    });

    it("should show notification when groupElements fails", async () => {
      mockApi.groupElements.mockRejectedValue(new Error("API Error"));

      const { result } = renderHook(() =>
        useGroupUngroupContextMenuItems({
          nodes: [mockHttpNode, mockKafkaNode],
          setNodes: mockSetNodes,
          edges: [],
          setEdges: mockSetEdges,
          chainId,
          structureChanged: mockStructureChanged,
        } as unknown as ContextMenuItemsHookProps),
      );

      const groupHandler = result.current.buildItems([
        mockHttpNode,
        mockKafkaNode,
      ])[0].handler;
      await groupHandler();

      await waitFor(() => {
        expect(mockNotificationService.requestFailed).toHaveBeenCalledWith(
          "Failed to group elements",
          expect.any(Error),
        );
      });
    });

    it("should not call API when chainId is undefined", async () => {
      const { result } = renderHook(() =>
        useGroupUngroupContextMenuItems({
          nodes: [mockHttpNode, mockKafkaNode],
          setNodes: mockSetNodes,
          edges: [],
          setEdges: mockSetEdges,
          chainId: undefined,
          structureChanged: mockStructureChanged,
        } as unknown as ContextMenuItemsHookProps),
      );

      const groupHandler = result.current.buildItems([
        mockHttpNode,
        mockKafkaNode,
      ])[0].handler;
      await groupHandler();

      expect(mockApi.groupElements).not.toHaveBeenCalled();
    });

    it("should handle container without children", async () => {
      const emptyContainer = {
        id: "container-1",
        type: "container",
        name: "Group",
        description: "",
        children: [],
      };

      mockApi.groupElements.mockResolvedValue(emptyContainer as any);
      mockGetNodeFromElement.mockReturnValue(mockContainerNode);

      const { result } = renderHook(() =>
        useGroupUngroupContextMenuItems({
          nodes: [mockHttpNode, mockKafkaNode],
          setNodes: mockSetNodes,
          edges: [],
          setEdges: mockSetEdges,
          chainId,
          structureChanged: mockStructureChanged,
        } as unknown as ContextMenuItemsHookProps),
      );

      const groupHandler = result.current.buildItems([
        mockHttpNode,
        mockKafkaNode,
      ])[0].handler;
      await groupHandler();

      await waitFor(() => {
        expect(mockApi.groupElements).toHaveBeenCalledWith(chainId, [
          "http-1",
          "kafka-1",
        ]);
        expect(mockSetNodes).toHaveBeenCalled();
      });
    });
  });

  describe("ungroupElements", () => {
    it("should call api.ungroupElements and update nodes when group is ungrouped successfully", async () => {
      const childElement1: Element = {
        id: "child-1",
        type: "http-trigger",
        name: "Child",
      } as Element;
      mockApi.ungroupElements.mockResolvedValue([childElement1]);

      const { result } = renderHook(() =>
        useGroupUngroupContextMenuItems({
          nodes: [mockContainerNode, mockChildNode],
          setNodes: mockSetNodes,
          edges: [],
          setEdges: mockSetEdges,
          chainId,
          structureChanged: mockStructureChanged,
        } as unknown as ContextMenuItemsHookProps),
      );

      const ungroupHandler = result.current.buildItems([mockContainerNode])[0]
        .handler;
      await ungroupHandler();

      await waitFor(() => {
        expect(mockApi.ungroupElements).toHaveBeenCalledWith(
          chainId,
          "container-1",
        );
        expect(mockSetNodes).toHaveBeenCalled();
        expect(mockStructureChanged).toHaveBeenCalled();
      });
    });

    it("should show notification when ungroupElements fails", async () => {
      mockApi.ungroupElements.mockRejectedValue(new Error("API Error"));

      const { result } = renderHook(() =>
        useGroupUngroupContextMenuItems({
          nodes: [mockContainerNode],
          setNodes: mockSetNodes,
          edges: [],
          setEdges: mockSetEdges,
          chainId,
          structureChanged: mockStructureChanged,
        } as unknown as ContextMenuItemsHookProps),
      );

      const ungroupHandler = result.current.buildItems([mockContainerNode])[0]
        .handler;
      await ungroupHandler();

      await waitFor(() => {
        expect(mockNotificationService.requestFailed).toHaveBeenCalledWith(
          "Failed to ungroup elements",
          expect.any(Error),
        );
      });
    });

    it("should not call API when chainId is undefined", async () => {
      const { result } = renderHook(() =>
        useGroupUngroupContextMenuItems({
          nodes: [mockContainerNode],
          setNodes: mockSetNodes,
          edges: [],
          setEdges: mockSetEdges,
          chainId: undefined,
          structureChanged: mockStructureChanged,
        } as unknown as ContextMenuItemsHookProps),
      );

      const ungroupHandler = result.current.buildItems([mockContainerNode])[0]
        .handler;
      await ungroupHandler();

      expect(mockApi.ungroupElements).not.toHaveBeenCalled();
    });

    it("should handle empty elements array from API response", async () => {
      mockApi.ungroupElements.mockResolvedValue([]);

      const { result } = renderHook(() =>
        useGroupUngroupContextMenuItems({
          nodes: [mockContainerNode, mockChildNode],
          setNodes: mockSetNodes,
          edges: [],
          setEdges: mockSetEdges,
          chainId,
          structureChanged: mockStructureChanged,
        } as unknown as ContextMenuItemsHookProps),
      );

      const ungroupHandler = result.current.buildItems([mockContainerNode])[0]
        .handler;
      await ungroupHandler();

      await waitFor(() => {
        expect(mockApi.ungroupElements).toHaveBeenCalledWith(
          chainId,
          "container-1",
        );
        expect(mockSetNodes).toHaveBeenCalled();
      });
    });
  });

  describe("integration scenarios", () => {
    it("should correctly handle nested nodes when grouping", async () => {
      const childElement1: Element = {
        id: "child-1",
        type: "http-trigger",
        name: "Child",
      } as Element;
      const groupedContainer = {
        id: "container-1",
        type: "container",
        name: "Group",
        description: "",
        children: [childElement1],
      };

      mockApi.groupElements.mockResolvedValue(groupedContainer as any);
      mockGetNodeFromElement.mockReturnValue(mockContainerNode);

      const mockArrangeNodes = jest
        .fn()
        .mockResolvedValue([mockContainerNode, mockChildNode]);
      mockUseAutoLayout.mockReturnValue({
        arrangeNodes: mockArrangeNodes,
        direction: "LR",
      } as unknown as ReturnType<typeof useAutoLayout>);

      const mockAttachToggle = jest
        .fn()
        .mockReturnValue([mockContainerNode, mockChildNode]);
      const mockSetNestedUnitCounts = jest
        .fn()
        .mockReturnValue([mockContainerNode, mockChildNode]);
      mockUseExpandCollapse.mockReturnValue({
        attachToggle: mockAttachToggle,
        setNestedUnitCounts: mockSetNestedUnitCounts,
      } as unknown as ReturnType<typeof useExpandCollapse>);

      mockSortParentsBeforeChildren.mockReturnValue([
        mockContainerNode,
        mockChildNode,
      ]);

      const { result } = renderHook(() =>
        useGroupUngroupContextMenuItems({
          nodes: [mockHttpNode, mockKafkaNode],
          setNodes: mockSetNodes,
          edges: [],
          setEdges: mockSetEdges,
          chainId,
          structureChanged: mockStructureChanged,
        } as unknown as ContextMenuItemsHookProps),
      );

      // Use two non-parented nodes to trigger the Group action
      const groupHandler = result.current.buildItems([
        mockHttpNode,
        mockKafkaNode,
      ])[0].handler;
      await groupHandler();

      await waitFor(() => {
        expect(mockSortParentsBeforeChildren).toHaveBeenCalled();
      });
    });
  });
});
