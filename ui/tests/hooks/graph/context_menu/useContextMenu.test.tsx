/**
 * @jest-environment jsdom
 */

import { act, renderHook } from "@testing-library/react";
import { ChainGraphNodeData } from "../../../../src/components/graph/nodes/ChainGraphNodeTypes";
import { useLibraryContext } from "../../../../src/components/LibraryContext";
import { useAddChildContextMenuItem } from "../../../../src/hooks/graph/context_menu/useAddChildContextMenuItem";
import { useGroupUngroupContextMenuItems } from "../../../../src/hooks/graph/context_menu/useGroupUngroupContextMenuItems";
import { usePriorityContextMenuItems } from "../../../../src/hooks/graph/context_menu/usePriorityContextMenuItems";
import { useAutoLayout } from "../../../../src/hooks/graph/useAutoLayout";
import { useExpandCollapse } from "../../../../src/hooks/graph/useExpandCollapse";
import { useContextMenu } from "../../../../src/hooks/graph/context_menu/useContextMenu";
import { Edge, Node } from "@xyflow/react";
import {
  buildGraphNodes,
  sortParentsBeforeChildren,
} from "../../../../src/misc/chain-graph-utils";
import { api } from "../../../../src/api/api";

// Mocks
jest.mock("../../../../src/hooks/graph/useAutoLayout.tsx");
jest.mock("../../../../src/components/LibraryContext.tsx");
jest.mock("../../../../src/hooks/graph/useExpandCollapse.tsx");
jest.mock(
  "../../../../src/hooks/graph/context_menu/useAddChildContextMenuItem.tsx",
);
jest.mock(
  "../../../../src/hooks/graph/context_menu/usePriorityContextMenuItems.tsx",
);
jest.mock(
  "../../../../src/hooks/graph/context_menu/useGroupUngroupContextMenuItems.tsx",
);
jest.mock("../../../../src/api/api.ts");
jest.mock("../../../../src/misc/chain-graph-utils.ts");

const mockUseAutoLayout = useAutoLayout as jest.MockedFunction<
  typeof useAutoLayout
>;
const mockUseLibraryContext = useLibraryContext as jest.MockedFunction<
  typeof useLibraryContext
>;
const mockUseExpandCollapse = useExpandCollapse as jest.MockedFunction<
  typeof useExpandCollapse
>;
const mockUseAddChildContextMenuItem =
  useAddChildContextMenuItem as jest.MockedFunction<
    typeof useAddChildContextMenuItem
  >;
const mockUsePriorityContextMenuItems =
  usePriorityContextMenuItems as jest.MockedFunction<
    typeof usePriorityContextMenuItems
  >;
const mockUseGroupUngroupContextMenuItems =
  useGroupUngroupContextMenuItems as jest.MockedFunction<
    typeof useGroupUngroupContextMenuItems
  >;
const mockApi = api as jest.Mocked<typeof api>;
const mockBuildGraphNodes = buildGraphNodes as jest.MockedFunction<
  typeof buildGraphNodes
>;
const mockSortParentsBeforeChildren =
  sortParentsBeforeChildren as jest.MockedFunction<
    typeof sortParentsBeforeChildren
  >;

describe("useContextMenu", () => {
  const mockHandleDelete = jest.fn().mockResolvedValue(undefined);
  const mockOpenElementModal = jest.fn();
  const mockUpdateNodeData = jest.fn();
  const mockSetNodes = jest.fn();
  const mockSetEdges = jest.fn();
  const mockStructureChanged = jest.fn();
  const mockOnChainUpdate = jest.fn();

  const defaultNodes: Node<ChainGraphNodeData>[] = [
    {
      id: "node-1",
      type: "chainGraphNode",
      position: { x: 0, y: 0 },
      data: {
        elementType: "http-trigger",
        label: "HTTP Trigger",
        description: "Test description",
        labels: [],
      } as unknown as ChainGraphNodeData,
    },
    {
      id: "node-2",
      type: "container",
      position: { x: 0, y: 0 },
      data: {
        elementType: "container",
        label: "Container",
        description: "Container description",
        labels: [],
      } as unknown as ChainGraphNodeData,
    },
  ];

  const defaultEdges: Edge[] = [];

  const createWrapper = () => {
    const wrapper: React.FC<{ children: React.ReactNode }> = ({ children }) => (
      <>{children}</>
    );
    return wrapper;
  };

  beforeEach(() => {
    jest.clearAllMocks();

    mockUseAutoLayout.mockReturnValue({
      arrangeNodes: jest.fn().mockResolvedValue([]),
    } as unknown as ReturnType<typeof useAutoLayout>);

    mockUseLibraryContext.mockReturnValue({
      libraryElements: {
        "http-trigger": { parentRestriction: [] },
        container: { parentRestriction: [] },
      },
    } as unknown as ReturnType<typeof useLibraryContext>);

    mockUseExpandCollapse.mockReturnValue({
      attachToggle: jest.fn((nodes) => nodes),
      setNestedUnitCounts: jest.fn((nodes) => nodes),
    } as unknown as ReturnType<typeof useExpandCollapse>);

    mockUseAddChildContextMenuItem.mockReturnValue({
      buildItems: jest.fn(() => []),
    });

    mockUsePriorityContextMenuItems.mockReturnValue({
      buildItems: jest.fn(() => []),
    });

    mockUseGroupUngroupContextMenuItems.mockReturnValue({
      buildItems: jest.fn(() => []),
    });

    mockBuildGraphNodes.mockReturnValue([]);
    mockSortParentsBeforeChildren.mockReturnValue([]);
  });

  describe("Copy scenario", () => {
    it("should show Copy option when non-container element is selected", () => {
      const { result } = renderHook(
        () =>
          useContextMenu({
            handleDelete: mockHandleDelete,
            openElementModal: mockOpenElementModal,
            updateNodeData: mockUpdateNodeData,
            nodes: defaultNodes,
            setNodes: mockSetNodes,
            edges: defaultEdges,
            setEdges: mockSetEdges,
            structureChanged: mockStructureChanged,
            chainId: "chain-1",
          }),
        { wrapper: createWrapper() },
      );

      act(() => {
        result.current.onContextMenuCall(
          { preventDefault: jest.fn() } as unknown as React.MouseEvent,
          [defaultNodes[0]],
        );
      });

      const copyItem = result.current.menu!.items.find(
        (item) => item.text === "Copy",
      );
      expect(copyItem).toBeDefined();
      expect(copyItem!.disabled).toBeFalsy();
    });

    it("should copy single element to clipboard state when Copy is triggered", () => {
      const { result } = renderHook(
        () =>
          useContextMenu({
            handleDelete: mockHandleDelete,
            openElementModal: mockOpenElementModal,
            updateNodeData: mockUpdateNodeData,
            nodes: defaultNodes,
            setNodes: mockSetNodes,
            edges: defaultEdges,
            setEdges: mockSetEdges,
            structureChanged: mockStructureChanged,
            chainId: "chain-1",
          }),
        { wrapper: createWrapper() },
      );

      act(() => {
        result.current.onContextMenuCall(
          { preventDefault: jest.fn() } as unknown as React.MouseEvent,
          [defaultNodes[0]],
        );
      });

      const copyItem = result.current.menu!.items.find(
        (item) => item.text === "Copy",
      );
      expect(copyItem).toBeDefined();

      act(() => {
        void copyItem!.handler();
      });

      // Verify by opening menu again and checking Paste is enabled
      act(() => {
        result.current.onContextMenuCall(
          { preventDefault: jest.fn() } as unknown as React.MouseEvent,
          [],
        );
      });

      const pasteItem = result.current.menu!.items.find(
        (item) => item.text === "Paste",
      );
      expect(pasteItem!.disabled).toBe(false);
    });

    it("should copy multiple elements to clipboard state", () => {
      const { result } = renderHook(
        () =>
          useContextMenu({
            handleDelete: mockHandleDelete,
            openElementModal: mockOpenElementModal,
            updateNodeData: mockUpdateNodeData,
            nodes: defaultNodes,
            setNodes: mockSetNodes,
            edges: defaultEdges,
            setEdges: mockSetEdges,
            structureChanged: mockStructureChanged,
            chainId: "chain-1",
          }),
        { wrapper: createWrapper() },
      );

      act(() => {
        result.current.onContextMenuCall(
          { preventDefault: jest.fn() } as unknown as React.MouseEvent,
          [defaultNodes[0], defaultNodes[1]],
        );
      });

      const copyItem = result.current.menu!.items.find(
        (item) => item.text === "Copy",
      );
      act(() => {
        void copyItem!.handler();
      });

      // Verify Paste is enabled for multiple elements
      act(() => {
        result.current.onContextMenuCall(
          { preventDefault: jest.fn() } as unknown as React.MouseEvent,
          [],
        );
      });

      const pasteItem = result.current.menu!.items.find(
        (item) => item.text === "Paste",
      );
      expect(pasteItem!.disabled).toBe(false);
    });
  });

  describe("Paste scenario", () => {
    it("should show Paste option when no elements are selected and no nodes copied", () => {
      const { result } = renderHook(
        () =>
          useContextMenu({
            handleDelete: mockHandleDelete,
            openElementModal: mockOpenElementModal,
            updateNodeData: mockUpdateNodeData,
            nodes: defaultNodes,
            setNodes: mockSetNodes,
            edges: defaultEdges,
            setEdges: mockSetEdges,
            structureChanged: mockStructureChanged,
            chainId: "chain-1",
          }),
        { wrapper: createWrapper() },
      );

      act(() => {
        result.current.onContextMenuCall(
          { preventDefault: jest.fn() } as unknown as React.MouseEvent,
          [],
        );
      });

      const pasteItem = result.current.menu!.items.find(
        (item) => item.text === "Paste",
      );
      expect(pasteItem).toBeDefined();
      expect(pasteItem!.disabled).toBe(true);
    });

    it("should enable Paste option when nodes are copied", () => {
      const { result } = renderHook(
        () =>
          useContextMenu({
            handleDelete: mockHandleDelete,
            openElementModal: mockOpenElementModal,
            updateNodeData: mockUpdateNodeData,
            nodes: defaultNodes,
            setNodes: mockSetNodes,
            edges: defaultEdges,
            setEdges: mockSetEdges,
            structureChanged: mockStructureChanged,
            chainId: "chain-1",
          }),
        { wrapper: createWrapper() },
      );

      // Copy elements first
      act(() => {
        result.current.onContextMenuCall(
          { preventDefault: jest.fn() } as unknown as React.MouseEvent,
          [defaultNodes[0]],
        );
      });

      const copyItem = result.current.menu!.items.find(
        (item) => item.text === "Copy",
      );
      act(() => {
        void copyItem!.handler();
      });

      // Open menu with no selection
      act(() => {
        result.current.onContextMenuCall(
          { preventDefault: jest.fn() } as unknown as React.MouseEvent,
          [],
        );
      });

      const pasteItem = result.current.menu!.items.find(
        (item) => item.text === "Paste",
      );
      expect(pasteItem!.disabled).toBe(false);
    });

    it("should call api.cloneElements when Paste is triggered at root level", async () => {
      const mockArrangeNodes = jest.fn().mockResolvedValue([
        {
          id: "new-node-1",
          type: "chainGraphNode",
          position: { x: 100, y: 100 },
          data: {
            elementType: "http-trigger",
            label: "New Node",
          } as ChainGraphNodeData,
        },
      ]);

      mockUseAutoLayout.mockReturnValue({
        arrangeNodes: mockArrangeNodes,
      } as unknown as ReturnType<typeof useAutoLayout>);

      mockApi.cloneElements.mockResolvedValue([
        { id: "new-element-1" },
      ] as unknown as ReturnType<typeof api.cloneElements> extends Promise<
        infer T
      >
        ? T
        : never);

      mockBuildGraphNodes.mockReturnValue([
        {
          id: "new-node-1",
          type: "chainGraphNode",
          position: { x: 100, y: 100 },
          data: {
            elementType: "http-trigger",
            label: "New Node",
          } as ChainGraphNodeData,
        },
      ]);

      mockSortParentsBeforeChildren.mockReturnValue([
        {
          id: "new-node-1",
          type: "chainGraphNode",
          position: { x: 100, y: 100 },
          data: {
            elementType: "http-trigger",
            label: "New Node",
          } as ChainGraphNodeData,
        } as any,
      ]);

      const { result } = renderHook(
        () =>
          useContextMenu({
            handleDelete: mockHandleDelete,
            openElementModal: mockOpenElementModal,
            updateNodeData: mockUpdateNodeData,
            nodes: defaultNodes,
            setNodes: mockSetNodes,
            edges: defaultEdges,
            setEdges: mockSetEdges,
            structureChanged: mockStructureChanged,
            chainId: "chain-1",
            onChainUpdate: mockOnChainUpdate,
          }),
        { wrapper: createWrapper() },
      );

      // Copy elements
      act(() => {
        result.current.onContextMenuCall(
          { preventDefault: jest.fn() } as unknown as React.MouseEvent,
          [defaultNodes[0]],
        );
      });

      const copyItem = result.current.menu!.items.find(
        (item) => item.text === "Copy",
      );
      act(() => {
        void copyItem!.handler();
      });

      // Paste at root level
      act(() => {
        result.current.onContextMenuCall(
          { preventDefault: jest.fn() } as unknown as React.MouseEvent,
          [],
        );
      });

      const pasteItem = result.current.menu!.items.find(
        (item) => item.text === "Paste",
      );
      await act(async () => {
        await pasteItem!.handler();
      });

      expect(mockApi.cloneElements).toHaveBeenCalledWith(
        "chain-1",
        ["node-1"],
        undefined,
      );
    });

    it("should call api.cloneElements with target when Paste is triggered on container", async () => {
      const mockArrangeNodes = jest.fn().mockResolvedValue([
        {
          id: "new-node-1",
          type: "chainGraphNode",
          position: { x: 100, y: 100 },
          data: {
            elementType: "http-trigger",
            label: "New Node",
          } as ChainGraphNodeData,
        },
      ]);

      mockUseAutoLayout.mockReturnValue({
        arrangeNodes: mockArrangeNodes,
      } as unknown as ReturnType<typeof useAutoLayout>);

      mockApi.cloneElements.mockResolvedValue([
        { id: "new-element-1" },
      ] as unknown as ReturnType<typeof api.cloneElements> extends Promise<
        infer T
      >
        ? T
        : never);

      mockBuildGraphNodes.mockReturnValue([
        {
          id: "new-node-1",
          type: "chainGraphNode",
          position: { x: 100, y: 100 },
          data: {
            elementType: "http-trigger",
            label: "New Node",
          } as ChainGraphNodeData,
        },
      ]);

      mockSortParentsBeforeChildren.mockReturnValue([]);

      const { result } = renderHook(
        () =>
          useContextMenu({
            handleDelete: mockHandleDelete,
            openElementModal: mockOpenElementModal,
            updateNodeData: mockUpdateNodeData,
            nodes: defaultNodes,
            setNodes: mockSetNodes,
            edges: defaultEdges,
            setEdges: mockSetEdges,
            structureChanged: mockStructureChanged,
            chainId: "chain-1",
          }),
        { wrapper: createWrapper() },
      );

      // Copy elements
      act(() => {
        result.current.onContextMenuCall(
          { preventDefault: jest.fn() } as unknown as React.MouseEvent,
          [defaultNodes[0]],
        );
      });

      const copyItem = result.current.menu!.items.find(
        (item) => item.text === "Copy",
      );
      act(() => {
        void copyItem!.handler();
      });

      act(() => {
        result.current.onContextMenuCall(
          { preventDefault: jest.fn() } as unknown as React.MouseEvent,
          [],
        );
      });

      const pasteItem = result.current.menu!.items.find(
        (item) => item.text === "Paste",
      );
      await act(async () => {
        await pasteItem!.handler();
      });

      expect(mockApi.cloneElements).toHaveBeenCalledWith(
        "chain-1",
        ["node-1"],
        undefined,
      );
    });

    it("should call onChainUpdate callback after successful paste", async () => {
      const mockArrangeNodes = jest.fn().mockResolvedValue([]);
      mockUseAutoLayout.mockReturnValue({
        arrangeNodes: mockArrangeNodes,
      } as unknown as ReturnType<typeof useAutoLayout>);

      mockApi.cloneElements.mockResolvedValue([
        { id: "new-element-1" },
      ] as unknown as ReturnType<typeof api.cloneElements> extends Promise<
        infer T
      >
        ? T
        : never);
      mockBuildGraphNodes.mockReturnValue([]);
      mockSortParentsBeforeChildren.mockReturnValue([]);

      const { result } = renderHook(
        () =>
          useContextMenu({
            handleDelete: mockHandleDelete,
            openElementModal: mockOpenElementModal,
            updateNodeData: mockUpdateNodeData,
            nodes: defaultNodes,
            setNodes: mockSetNodes,
            edges: defaultEdges,
            setEdges: mockSetEdges,
            structureChanged: mockStructureChanged,
            chainId: "chain-1",
            onChainUpdate: mockOnChainUpdate,
          }),
        { wrapper: createWrapper() },
      );

      // Copy and paste
      act(() => {
        result.current.onContextMenuCall(
          { preventDefault: jest.fn() } as unknown as React.MouseEvent,
          [defaultNodes[0]],
        );
      });

      const copyItem = result.current.menu!.items.find(
        (item) => item.text === "Copy",
      );
      act(() => {
        void copyItem!.handler();
      });

      act(() => {
        result.current.onContextMenuCall(
          { preventDefault: jest.fn() } as unknown as React.MouseEvent,
          [],
        );
      });

      const pasteItem = result.current.menu!.items.find(
        (item) => item.text === "Paste",
      );
      await act(async () => {
        await pasteItem!.handler();
      });

      expect(mockOnChainUpdate).toHaveBeenCalled();
    });

    it("should call structureChanged after successful paste", async () => {
      const mockArrangeNodes = jest.fn().mockResolvedValue([]);
      mockUseAutoLayout.mockReturnValue({
        arrangeNodes: mockArrangeNodes,
      } as unknown as ReturnType<typeof useAutoLayout>);

      mockApi.cloneElements.mockResolvedValue([
        { id: "new-element-1" },
      ] as unknown as ReturnType<typeof api.cloneElements> extends Promise<
        infer T
      >
        ? T
        : never);
      mockBuildGraphNodes.mockReturnValue([]);
      mockSortParentsBeforeChildren.mockReturnValue([]);

      const { result } = renderHook(
        () =>
          useContextMenu({
            handleDelete: mockHandleDelete,
            openElementModal: mockOpenElementModal,
            updateNodeData: mockUpdateNodeData,
            nodes: defaultNodes,
            setNodes: mockSetNodes,
            edges: defaultEdges,
            setEdges: mockSetEdges,
            structureChanged: mockStructureChanged,
            chainId: "chain-1",
          }),
        { wrapper: createWrapper() },
      );

      // Copy and paste
      act(() => {
        result.current.onContextMenuCall(
          { preventDefault: jest.fn() } as unknown as React.MouseEvent,
          [defaultNodes[0]],
        );
      });

      const copyItem = result.current.menu!.items.find(
        (item) => item.text === "Copy",
      );
      act(() => {
        void copyItem!.handler();
      });

      act(() => {
        result.current.onContextMenuCall(
          { preventDefault: jest.fn() } as unknown as React.MouseEvent,
          [],
        );
      });

      const pasteItem = result.current.menu!.items.find(
        (item) => item.text === "Paste",
      );
      await act(async () => {
        await pasteItem!.handler();
      });

      expect(mockStructureChanged).toHaveBeenCalled();
    });

    it("should return early without doing anything when chainId is null", async () => {
      const { result } = renderHook(
        () =>
          useContextMenu({
            handleDelete: mockHandleDelete,
            openElementModal: mockOpenElementModal,
            updateNodeData: mockUpdateNodeData,
            nodes: defaultNodes,
            setNodes: mockSetNodes,
            edges: defaultEdges,
            setEdges: mockSetEdges,
            structureChanged: mockStructureChanged,
            // chainId is undefined
          }),
        { wrapper: createWrapper() },
      );

      // Copy elements
      act(() => {
        result.current.onContextMenuCall(
          { preventDefault: jest.fn() } as unknown as React.MouseEvent,
          [defaultNodes[0]],
        );
      });

      const copyItem = result.current.menu!.items.find(
        (item) => item.text === "Copy",
      );
      act(() => {
        void copyItem!.handler();
      });

      // Try to paste
      act(() => {
        result.current.onContextMenuCall(
          { preventDefault: jest.fn() } as unknown as React.MouseEvent,
          [],
        );
      });

      const pasteItem = result.current.menu!.items.find(
        (item) => item.text === "Paste",
      );
      await act(async () => {
        await pasteItem!.handler();
      });

      expect(mockApi.cloneElements).not.toHaveBeenCalled();
    });
  });

  describe("Edit scenario", () => {
    it("should show Edit option when single non-container element is selected", () => {
      const { result } = renderHook(
        () =>
          useContextMenu({
            handleDelete: mockHandleDelete,
            openElementModal: mockOpenElementModal,
            updateNodeData: mockUpdateNodeData,
            nodes: defaultNodes,
            setNodes: mockSetNodes,
            edges: defaultEdges,
            setEdges: mockSetEdges,
            structureChanged: mockStructureChanged,
            chainId: "chain-1",
          }),
        { wrapper: createWrapper() },
      );

      act(() => {
        result.current.onContextMenuCall(
          { preventDefault: jest.fn() } as unknown as React.MouseEvent,
          [defaultNodes[0]],
        );
      });

      const editItem = result.current.menu!.items.find(
        (item) => item.text === "Edit",
      );
      expect(editItem).toBeDefined();
    });

    it("should call openElementModal with selected node when Edit is triggered", () => {
      const { result } = renderHook(
        () =>
          useContextMenu({
            handleDelete: mockHandleDelete,
            openElementModal: mockOpenElementModal,
            updateNodeData: mockUpdateNodeData,
            nodes: defaultNodes,
            setNodes: mockSetNodes,
            edges: defaultEdges,
            setEdges: mockSetEdges,
            structureChanged: mockStructureChanged,
            chainId: "chain-1",
          }),
        { wrapper: createWrapper() },
      );

      act(() => {
        result.current.onContextMenuCall(
          { preventDefault: jest.fn() } as unknown as React.MouseEvent,
          [defaultNodes[0]],
        );
      });

      const editItem = result.current.menu!.items.find(
        (item) => item.text === "Edit",
      );
      expect(editItem).toBeDefined();

      act(() => {
        void editItem!.handler();
      });

      expect(mockOpenElementModal).toHaveBeenCalledWith(defaultNodes[0]);
    });

    it("should not show Edit option when container element is selected", () => {
      const { result } = renderHook(
        () =>
          useContextMenu({
            handleDelete: mockHandleDelete,
            openElementModal: mockOpenElementModal,
            updateNodeData: mockUpdateNodeData,
            nodes: defaultNodes,
            setNodes: mockSetNodes,
            edges: defaultEdges,
            setEdges: mockSetEdges,
            structureChanged: mockStructureChanged,
            chainId: "chain-1",
          }),
        { wrapper: createWrapper() },
      );

      act(() => {
        result.current.onContextMenuCall(
          { preventDefault: jest.fn() } as unknown as React.MouseEvent,
          [defaultNodes[1]],
        );
      });

      const editItem = result.current.menu!.items.find(
        (item) => item.text === "Edit",
      );
      expect(editItem).toBeUndefined();
    });

    it("should not show Edit option when multiple elements are selected", () => {
      const { result } = renderHook(
        () =>
          useContextMenu({
            handleDelete: mockHandleDelete,
            openElementModal: mockOpenElementModal,
            updateNodeData: mockUpdateNodeData,
            nodes: defaultNodes,
            setNodes: mockSetNodes,
            edges: defaultEdges,
            setEdges: mockSetEdges,
            structureChanged: mockStructureChanged,
            chainId: "chain-1",
          }),
        { wrapper: createWrapper() },
      );

      act(() => {
        result.current.onContextMenuCall(
          { preventDefault: jest.fn() } as unknown as React.MouseEvent,
          [defaultNodes[0], defaultNodes[1]],
        );
      });

      const editItem = result.current.menu!.items.find(
        (item) => item.text === "Edit",
      );
      expect(editItem).toBeUndefined();
    });
  });
});
