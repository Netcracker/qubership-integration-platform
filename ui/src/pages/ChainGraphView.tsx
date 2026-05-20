import React, {
  HTMLAttributes,
  MouseEvent,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { ElkDirectionContextProvider } from "./ElkDirectionContext.tsx";
import {
  Background,
  BackgroundVariant,
  Edge,
  type KeyCode,
  MiniMap,
  Node,
  OnSelectionChangeFunc,
  ReactFlow,
  useOnSelectionChange,
} from "@xyflow/react";
import {
  ChainGraphNode,
  ChainGraphNodeData,
  nodeTypes,
  OnDeleteEvent,
} from "../components/graph/nodes/ChainGraphNodeTypes.ts";
import { ElementFocus } from "../components/graph/ElementFocus.tsx";
import {
  ChainGraphViewControls,
  ChainGraphViewControlsProps,
} from "../components/graph/ChainGraphViewControls.tsx";
import { Modal } from "antd";
import ContextMenu from "../components/graph/ContextMenu.tsx";
import { useChainGraph } from "../hooks/graph/useChainGraph.tsx";
import {
  getElementColor,
  isSwimlanesOnly,
  nonEmptyContainerExists,
  sanitizeEdge,
} from "../misc/chain-graph-utils.ts";
import { useContextMenu } from "../hooks/graph/useContextMenu.tsx";
import { getSwimlaneBorderColor } from "../components/graph/nodes/SwimlaneNode.tsx";
import { ChainContext } from "./ChainPage.tsx";
import { useLibraryContext } from "../components/LibraryContext.tsx";
import { useParams } from "react-router-dom";
import { Element } from "../api/apiTypes";
import {
  ChainGraphChangeContext,
  NodeState,
} from "../components/chains/diff/ChainGraphChangeProvider.tsx";

const readTheme = () => {
  if (typeof document === "undefined") return "light";
  const candidates: (globalThis.Element | null)[] = [
    document.documentElement,
    document.body,
    document.querySelector(".vscode-webview"),
  ];

  for (const target of candidates) {
    if (!target) continue;
    const value = target.getAttribute("data-theme");
    if (value !== null && typeof value === "string") return value;
  }

  if (document.body?.classList.contains("vscode-dark")) {
    return "dark";
  }

  if (document.body?.classList.contains("vscode-high-contrast")) {
    return "high-contrast";
  }

  return "light";
};

export type ChainGraphViewProps = HTMLAttributes<HTMLDivElement> & {
  readOnly: boolean;
  controls?: Partial<Pick<ChainGraphViewControlsProps, "before" | "after">>;
  submitOpenElement?: (
    node: Node<ChainGraphNodeData> | undefined,
    submit: (element: Element, node: ChainGraphNode) => void,
  ) => void;
};

export const ChainGraphView: React.FC<ChainGraphViewProps> = ({
  readOnly,
  controls,
  submitOpenElement,
  className,
  ...rest
}) => {
  const { elementId } = useParams<string>();
  const reactFlowWrapper = useRef(null);
  const chainContext = useContext(ChainContext);
  const { isLibraryLoading, libraryElements } = useLibraryContext();
  const [currentTheme, setCurrentTheme] = useState<string>(() => readTheme());
  const currentThemeRef = useRef(currentTheme);
  const [selectedByRightClick, setSelectedByRightClick] =
    useState<boolean>(false);
  const [, setIsPageLoaded] = useState<boolean>(false);

  const deleteKeyCode = useMemo<KeyCode | null>(
    () => (readOnly ? null : ["Backspace", "Delete"]),
    [readOnly],
  );
  const changeContext = useContext(ChainGraphChangeContext);

  const {
    nodes,
    setNodes,
    edges,
    setEdges,
    decorativeEdges,
    onConnect,
    onDragOver,
    onDrop,
    onDelete,
    onEdgesChange,
    onNodesChange,
    onNodeDragStart,
    onNodeDrag,
    onNodeDragStop,
    direction,
    toggleDirection,
    updateNodeData,
    isLoading,
    expandAllContainers,
    collapseAllContainers,
    structureChanged,
  } = useChainGraph();

  useEffect(() => {
    currentThemeRef.current = currentTheme;
  }, [currentTheme]);

  useEffect(() => {
    if (typeof document === "undefined") {
      return;
    }

    const updateTheme = () => {
      const theme = readTheme();
      if (!theme) return;
      if (currentThemeRef.current === theme) {
        if (document.body.dataset.theme !== theme) {
          document.body.dataset.theme = theme;
        }
        return;
      }
      currentThemeRef.current = theme;
      setCurrentTheme(theme);
      if (document.body.dataset.theme !== theme) {
        document.body.dataset.theme = theme;
      }
    };

    updateTheme();

    const observer = new MutationObserver(updateTheme);
    const targets: (globalThis.Element | null)[] = [
      document.documentElement,
      document.body,
      document.querySelector(".vscode-webview"),
    ];

    targets
      .filter((target): target is globalThis.Element => !!target)
      .forEach((target) =>
        observer.observe(target, {
          attributes: true,
          attributeFilter: ["data-theme"],
        }),
      );

    return () => {
      observer.disconnect();
    };
  }, []);

  const renderEdges = useMemo(
    () => [...edges, ...decorativeEdges].map(sanitizeEdge),
    [edges, decorativeEdges],
  );

  const handleDelete = useCallback(
    async (changes: OnDeleteEvent) => {
      if (
        changes.nodes.length > 0 &&
        (await nonEmptyContainerExists(changes.nodes)) &&
        !isSwimlanesOnly(changes.nodes)
      ) {
        Modal.confirm({
          title: "Delete Container",
          content:
            "This container element is not empty. Are you sure you want to delete it? All its content will be also deleted.",
          onOk: () => void onDelete(changes),
        });
      } else {
        void onDelete(changes);
      }
    },
    [onDelete],
  );

  const onBeforeDelete = useCallback(async () => {
    if (typeof document === "undefined") return true;
    return Promise.resolve(!document.querySelector(".ant-modal-wrap"));
  }, []);

  const { menu, closeMenu, onContextMenuCall } = useContextMenu(
    handleDelete,
    (node) => submitOpenElement?.(node, updateNodeData),
    nodes,
    setNodes,
    edges,
    setEdges,
    structureChanged,
    chainContext?.chain?.id,
    chainContext?.refresh,
  );

  const handleSelectionChange = useCallback<
    OnSelectionChangeFunc<Node<ChainGraphNodeData>, Edge>
  >(() => {
    if (selectedByRightClick) {
      setSelectedByRightClick(false);
    } else {
      closeMenu();
    }
  }, [closeMenu, selectedByRightClick]);

  useOnSelectionChange<Node<ChainGraphNodeData>, Edge>({
    onChange: handleSelectionChange,
  });

  const onNodeDoubleClick = (
    _event: MouseEvent,
    node: Node<ChainGraphNodeData>,
  ) => {
    submitOpenElement?.(node, updateNodeData);
  };

  const getCssVariableValue = useCallback(
    (variableName: string, fallback: string) => {
      if (typeof window === "undefined") return fallback;
      const rootElement =
        document.querySelector(".vscode-webview") || document.documentElement;
      const value =
        getComputedStyle(rootElement).getPropertyValue(variableName);
      return value?.trim() || fallback;
    },
    [],
  );

  const getMinimapNodeColor = useCallback(
    (node: Node<ChainGraphNodeData>) => {
      if (changeContext) {
        const state = changeContext?.nodeState?.get(node.id);
        switch (state) {
          case NodeState.NOT_CHANGED:
            return getCssVariableValue("--node-not-changed-color", "#727272");
          case NodeState.CHANGED:
            return getCssVariableValue("--node-changed-color", "#ffcc02");
          case NodeState.REMOVED:
            return getCssVariableValue("--node-removed-color", "#f48771");
          case NodeState.CREATED:
            return getCssVariableValue("--node-created-color", "#4ec9b0");
        }
      }
      if (node.type === "swimlane") {
        return getSwimlaneBorderColor(
          (node.data.properties as Record<string, unknown>)["color"] as string,
        );
      }

      if (node.type === "container") {
        return getCssVariableValue("--container-header-background", "#fff9e6");
      }

      if (node.style?.backgroundColor) {
        return node.style.backgroundColor;
      }

      if (node.data?.elementType && libraryElements) {
        const libraryElement = libraryElements.find(
          (el) => el.name === node.data.elementType,
        );
        return getElementColor(libraryElement);
      }

      return "#fdf39d";
    },
    [libraryElements, getCssVariableValue],
  );

  const getMinimapNodeStrokeColor = useCallback(
    (node?: Node<ChainGraphNodeData>) => {
      if (node?.type === "container") {
        return getCssVariableValue(
          "--vscode-foreground",
          currentTheme === "dark"
            ? "rgba(255, 255, 255, 0.85)"
            : "rgba(0, 0, 0, 0.88)",
        );
      }
      return getCssVariableValue(
        "--vscode-border",
        currentTheme === "dark" ? "#303030" : "#d9d9d9",
      );
    },
    [currentTheme, getCssVariableValue],
  );

  const onContextMenu = (event: MouseEvent) => {
    const elements: Node<ChainGraphNodeData>[] = [];

    setSelectedByRightClick(true);
    setNodes((prev) =>
      prev.map((prevNode) => ({
        ...prevNode,
        selected: false,
      })),
    );

    onContextMenuCall(event, elements);
  };

  const onNodeContextMenu = (
    event: MouseEvent,
    node: Node<ChainGraphNodeData>,
  ) => {
    event.stopPropagation();
    const elements: Node<ChainGraphNodeData>[] = [];

    const selectedBeforeRightClick = nodes.filter((n) => n.selected);

    if (
      selectedBeforeRightClick.some(
        (selectedNode) => selectedNode.id === node.id,
      )
    ) {
      elements.push(...selectedBeforeRightClick);
    } else {
      setSelectedByRightClick(true);
      setNodes((prev) =>
        prev.map((prevNode) => ({
          ...prevNode,
          selected: prevNode.id === node.id,
        })),
      );
      elements.push(node);
    }
    (
      onContextMenuCall as (
        event: MouseEvent,
        elements: Node<ChainGraphNodeData>[],
      ) => void
    )(event, elements);
  };

  useEffect(() => {
    setIsPageLoaded((state) => {
      if (!state && !isLoading && !isLibraryLoading && nodes?.length) {
        if (elementId) {
          const targetNode = nodes.find((node) => node.id === elementId);
          if (targetNode) {
            submitOpenElement?.(targetNode, updateNodeData);
          }
        }
        return true;
      }
      return state;
    });
  }, [
    elementId,
    isLoading,
    isLibraryLoading,
    nodes,
    submitOpenElement,
    updateNodeData,
  ]);

  return (
    <div
      className={["react-flow-container", className]
        .filter((n) => !!n)
        .join(" ")}
      ref={reactFlowWrapper}
      {...rest}
    >
      <ElkDirectionContextProvider
        elkDirectionControl={{
          direction,
          toggleDirection,
        }}
      >
        <ReactFlow
          nodes={
            readOnly
              ? nodes.map((node) => ({
                  ...node,
                  draggable: false,
                  connectable: false,
                }))
              : nodes
          }
          nodeTypes={nodeTypes}
          defaultEdgeOptions={{ zIndex: 1001 }}
          edges={renderEdges}
          onNodeDragStart={readOnly ? undefined : onNodeDragStart}
          onNodeDrag={readOnly ? undefined : onNodeDrag}
          onNodeDragStop={
            readOnly
              ? undefined
              : (event, draggedNode) => void onNodeDragStop(event, draggedNode)
          }
          onNodesChange={readOnly ? undefined : onNodesChange}
          onEdgesChange={
            readOnly ? undefined : (changes) => onEdgesChange(changes)
          }
          onConnect={
            readOnly ? undefined : (connection) => void onConnect(connection)
          }
          onDelete={
            readOnly
              ? undefined
              : (changes) => {
                  void handleDelete(changes);
                }
          }
          onBeforeDelete={readOnly ? undefined : onBeforeDelete}
          onDrop={readOnly ? undefined : (event) => void onDrop(event)}
          onDragOver={readOnly ? undefined : onDragOver}
          onNodeDoubleClick={readOnly ? undefined : onNodeDoubleClick}
          zoomOnDoubleClick={false}
          deleteKeyCode={deleteKeyCode}
          proOptions={{ hideAttribution: true }}
          onContextMenu={readOnly ? undefined : onContextMenu}
          onNodeContextMenu={readOnly ? undefined : onNodeContextMenu}
          onPaneClick={closeMenu}
          fitView
        >
          <ElementFocus />
          <Background variant={BackgroundVariant.Dots} />
          <MiniMap
            zoomable
            pannable
            position="top-right"
            nodeColor={getMinimapNodeColor}
            nodeStrokeColor={getMinimapNodeStrokeColor}
            nodeStrokeWidth={2}
          />
          <ChainGraphViewControls
            {...controls}
            onExpandAllContainers={expandAllContainers}
            onCollapseAllContainers={collapseAllContainers}
          />
          {menu && <ContextMenu menu={menu} closeMenu={closeMenu} />}
        </ReactFlow>
      </ElkDirectionContextProvider>
    </div>
  );
};
