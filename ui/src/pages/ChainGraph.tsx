import {
  Background,
  BackgroundVariant,
  Edge,
  type KeyCode,
  MiniMap,
  Node,
  OnSelectionChangeFunc,
  ReactFlow,
  ReactFlowProvider,
  useOnSelectionChange,
} from "@xyflow/react";

import "@xyflow/react/dist/style.css";
import "../styles/reactflow-theme.css";
import React, {
  MouseEvent,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { ElementsLibrarySidebar } from "../components/elements_library/ElementsLibrarySidebar.tsx";
import { DnDProvider } from "../components/DndContext.tsx";
import { Flex, Modal } from "antd";
import { useNavigate } from "react-router";
import { useParams } from "react-router-dom";
import { useRegisterChainHeaderActions } from "./ChainHeaderActionsContext.tsx";
import { CustomControls } from "../components/graph/CustomControls.tsx";
import {
  ElementFocus,
  ElementFocusContext,
  type FitViewToElementIdFn,
} from "../components/graph/ElementFocus.tsx";
import { useModalsContext } from "../Modals.tsx";
import { ChainElementModification } from "../components/modal/chain_element/ChainElementModification.tsx";
import styles from "./ChainGraph.module.css";
import {
  LibraryProvider,
  useLibraryContext,
} from "../components/LibraryContext.tsx";

import { useChainGraph } from "../hooks/graph/useChainGraph.tsx";
import { ElkDirectionContextProvider } from "./ElkDirectionContext.tsx";
import { useElkDirection } from "../hooks/graph/useElkDirection.tsx";
import { PageWithRightPanel } from "./PageWithRightPanel.tsx";
import { SaveAndDeploy } from "../components/modal/SaveAndDeploy.tsx";
import { CreateDeploymentRequest, Element } from "../api/apiTypes.ts";
import { api } from "../api/api.ts";
import { useNotificationService } from "../hooks/useNotificationService.tsx";
import { SequenceDiagram } from "../components/modal/SequenceDiagram.tsx";
import { ChainContext } from "./ChainPage.tsx";
import {
  ChainGraphNode,
  ChainGraphNodeData,
  nodeTypes,
  OnDeleteEvent,
} from "../components/graph/nodes/ChainGraphNodeTypes.ts";
import { isVsCode } from "../api/rest/vscodeExtensionApi.ts";
import ContextMenu from "../components/graph/ContextMenu.tsx";
import { PanelResizeHandle } from "../components/PanelResizeHandle.tsx";
import {
  getElementColor,
  isSwimlanesOnly,
  nonEmptyContainerExists,
  sanitizeEdge,
} from "../misc/chain-graph-utils.ts";
import {
  ExportChainOptions,
  ExportChains,
} from "../components/modal/ExportChains.tsx";
import { downloadFile, mergeZipArchives } from "../misc/download-utils.ts";
import { exportAdditionsForChains } from "../misc/export-additions.ts";
import { generateSequenceDiagrams } from "../diagrams/main.ts";
import { Require } from "../permissions/Require.tsx";
import { ProtectedButton } from "../permissions/ProtectedButton.tsx";
import { usePermissions } from "../permissions/usePermissions.tsx";
import { hasPermissions } from "../permissions/funcs.ts";
import { getSwimlaneBorderColor } from "../components/graph/nodes/SwimlaneNode.tsx";
import { useContextMenu } from "../hooks/graph/useContextMenu.tsx";

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

const MIN_PANEL_WIDTH = 230;
const MAX_PANEL_WIDTH = 500;
const DEFAULT_LEFT_PANEL_WIDTH = 230;
const DEFAULT_RIGHT_PANEL_WIDTH = 240;

const clamp = (value: number, min: number, max: number) =>
  Math.min(max, Math.max(min, value));

const ChainGraphInner: React.FC = () => {
  const { chainId, elementId } = useParams<string>();
  const chainContext = useContext(ChainContext);
  const { showModal } = useModalsContext();
  const reactFlowWrapper = useRef(null);
  const [isPageLoaded, setIsPageLoaded] = useState(false);
  const [currentTheme, setCurrentTheme] = useState<string>(() => readTheme());
  const [leftPanelWidth, setLeftPanelWidth] = useState(
    DEFAULT_LEFT_PANEL_WIDTH,
  );
  const [rightPanelWidth, setRightPanelWidth] = useState(
    DEFAULT_RIGHT_PANEL_WIDTH,
  );
  const currentThemeRef = useRef(currentTheme);
  const navigate = useNavigate();
  const notificationService = useNotificationService();
  const { isLibraryLoading, libraryElements } = useLibraryContext();
  const permissions = usePermissions();
  const [readOnly, setReadOnly] = useState<boolean>(false);
  const deleteKeyCode = useMemo<KeyCode | null>(
    () => (readOnly || elementId ? null : ["Backspace", "Delete"]),
    [elementId, readOnly],
  );
  const [selectedByRightClick, setSelectedByRightClick] = useState<boolean>(false);

  useEffect(() => {
    setReadOnly(!hasPermissions(permissions, { chain: ["update"] }));
  }, [permissions]);

  const refreshChain = useCallback(async () => {
    if (!chainContext?.refresh) return;
    try {
      await chainContext.refresh();
    } catch (err) {
      notificationService.requestFailed("Failed to refresh chain", err);
    }
  }, [chainContext, notificationService]);

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
  } = useChainGraph(chainId, refreshChain);

  const renderEdges = useMemo(
    () => [...edges, ...decorativeEdges].map(sanitizeEdge),
    [edges, decorativeEdges],
  );

  const { leftPanel, toggleLeftPanel, rightPanel, toggleRightPanel } =
    useElkDirection();
  const fitViewToElementIdRef = useRef<FitViewToElementIdFn | null>(null);

  const handleElementUpdated = useCallback(
    (element: Element, node: ChainGraphNode) => {
      updateNodeData(element, node);
      void refreshChain();
    },
    [updateNodeData, refreshChain],
  );

  const clearElementPath = useCallback(() => {
    void navigate(`/chains/${chainId}/graph`);
  }, [chainId, navigate]);

  const setElementPath = useCallback(
    (newElementId: string) => {
      void navigate(`/chains/${chainId}/graph/${newElementId}`);
    },
    [chainId, navigate],
  );

  const openElementModal = useCallback(
    (node?: Node<ChainGraphNodeData>) => {
      if (!node?.data.elementType) return;
      const modalId = `chain-element-${node.id}`;
      if (elementId !== node.id) {
        setElementPath(node.id);
      }
      showModal({
        id: modalId,
        component: (
          <ChainContext.Provider value={chainContext}>
            <ChainElementModification
              node={node}
              chainId={chainId!}
              elementId={node.id}
              onSubmit={handleElementUpdated}
              onClose={clearElementPath}
            />
          </ChainContext.Provider>
        ),
      });
    },
    [
      chainContext,
      chainId,
      elementId,
      clearElementPath,
      setElementPath,
      showModal,
      handleElementUpdated,
    ],
  );

  const handleDelete = useCallback(
    async (changes: OnDeleteEvent) => {
      if (
        changes.nodes.length > 0 &&
        (await nonEmptyContainerExists(changes.nodes as ChainGraphNode[])) &&
        !isSwimlanesOnly(changes.nodes as ChainGraphNode[])
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

  const { menu, closeMenu, onContextMenuCall} = useContextMenu(
    handleDelete,
    openElementModal,
    nodes,
    setNodes,
    edges,
    setEdges,
    structureChanged,
    chainId,
    refreshChain,
  );

  const onNodeDoubleClick = (
    _event: MouseEvent,
    node: Node<ChainGraphNodeData>,
  ) => {
    openElementModal(node);
  };

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

  const onContextMenu = (event: MouseEvent) => {
    const elements: Node<ChainGraphNodeData>[] = [];

    setSelectedByRightClick(true);
    setNodes((prev) =>
      prev.map((prevNode) => ({
        ...prevNode,
        selected: false,
      })),
    );

    (
      onContextMenuCall as (
        event: MouseEvent,
        elements: Node<ChainGraphNodeData>[],
      ) => void
    )(event, elements);
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

  const saveAndDeploy = useCallback(
    async (domain: string) => {
      if (!chainId) return;
      try {
        await api.createSnapshot(chainId).then(async (snapshot) => {
          notificationService.info(
            "Created snapshot",
            `Created snapshot ${snapshot.name}`,
          );
          const request: CreateDeploymentRequest = {
            domain,
            snapshotId: snapshot.id,
            suspended: false,
          };
          await api.createDeployment(chainId, request);
          notificationService.info(
            "Deployed snapshot",
            `Deployed snapshot ${snapshot.name}`,
          );
        });
      } catch (error) {
        notificationService.requestFailed(
          "Failed to create snapshot and deploy it",
          error,
        );
      }
    },
    [chainId, notificationService],
  );

  const openSaveAndDeployDialog = useCallback(() => {
    showModal({
      component: <SaveAndDeploy chainId={chainId} onSubmit={saveAndDeploy} />,
    });
  }, [showModal, chainId, saveAndDeploy]);

  const exportChain = useCallback(
    async (chainIdParam: string | undefined, options: ExportChainOptions) => {
      try {
        if (!chainIdParam) {
          return;
        }
        const chainsFile = await api.exportChains(
          [chainIdParam],
          options.exportSubchains,
        );
        const data = [chainsFile];

        data.push(
          ...(await exportAdditionsForChains({
            api,
            chainIdsForUsedSystems: [chainIdParam],
            options: {
              exportServices: options.exportServices,
              exportVariables: options.exportVariables,
            },
          })),
        );

        const nonEmptyData = data.filter((d) => d.size !== 0);
        const archiveData = await mergeZipArchives(nonEmptyData);
        const file = new File([archiveData], chainsFile.name, {
          type: "application/zip",
        });
        downloadFile(file);
      } catch (error) {
        notificationService.requestFailed("Failed to export chains", error);
      }
    },
    [notificationService],
  );

  const openExportDialog = useCallback(() => {
    showModal({
      component: (
        <ExportChains
          multiple={false}
          onSubmit={(options) => {
            void exportChain(chainId, options);
          }}
        />
      ),
    });
  }, [showModal, chainId, exportChain]);

  const openSequenceDiagram = useCallback(() => {
    showModal({
      component: (
        <SequenceDiagram
          title="Chain Sequence Diagram"
          fileNamePrefix={"chain"}
          entityId={chainId}
          diagramProvider={async () => {
            if (chainContext?.chain) {
              return generateSequenceDiagrams(chainContext?.chain);
            } else {
              return Promise.reject(new Error("Chain is not specified"));
            }
          }}
        />
      ),
    });
  }, [showModal, chainId, chainContext?.chain]);

  const headerActions = useMemo(
    () => (
      <Flex align="center" gap={4}>
        <ProtectedButton
          require={{ chain: ["read"] }}
          tooltipProps={{ title: "Show sequence diagram" }}
          buttonProps={{
            icon: (
              <span
                className={String(styles.sequenceDiagramIcon ?? "")}
                data-icon="sequence-diagram"
                role="img"
              >
                ⇄
              </span>
            ),
            onClick: openSequenceDiagram,
          }}
        />
        {!isVsCode && (
          <>
            <ProtectedButton
              require={{ chain: ["export"] }}
              tooltipProps={{ title: "Export chain" }}
              buttonProps={{
                iconName: "cloudDownload",
                onClick: openExportDialog,
              }}
            />
            <ProtectedButton
              require={{ snapshot: ["create"], deployment: ["create"] }}
              tooltipProps={{}}
              buttonProps={{
                type: "primary",
                iconName: "send",
                onClick: openSaveAndDeployDialog,
                children: "Save and Deploy",
              }}
            />
          </>
        )}
      </Flex>
    ),
    [openSequenceDiagram, openExportDialog, openSaveAndDeployDialog],
  );
  useRegisterChainHeaderActions(headerActions, [chainId]);

  useEffect(() => {
    if (!isPageLoaded && !isLoading && !isLibraryLoading && nodes?.length) {
      setIsPageLoaded(true);
      if (elementId) {
        const targetNode = nodes.find((node) => node.id === elementId);
        if (targetNode) openElementModal(targetNode);
      }
    }
  }, [
    elementId,
    isLoading,
    isLibraryLoading,
    isPageLoaded,
    nodes,
    openElementModal,
  ]);

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

  return (
    <Flex className={styles["graph-wrapper"]}>
      <Require permissions={{ chain: ["update"] }}>
        {leftPanel && (
          <>
            <ElementsLibrarySidebar width={leftPanelWidth} />
            <PanelResizeHandle
              direction="left"
              onResize={(delta) =>
                setLeftPanelWidth((w) =>
                  clamp(w + delta, MIN_PANEL_WIDTH, MAX_PANEL_WIDTH),
                )
              }
            />
          </>
        )}
      </Require>
      <div className="react-flow-container" ref={reactFlowWrapper}>
        <ElkDirectionContextProvider
          elkDirectionControl={{
            direction,
            toggleDirection,
            leftPanel,
            toggleLeftPanel,
            rightPanel,
            toggleRightPanel,
          }}
        >
          <ElementFocusContext.Provider value={fitViewToElementIdRef}>
            <div style={{ flex: 1, minWidth: 0, display: "flex" }}>
              <div style={{ flex: 1, minWidth: 0 }}>
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
                      : (event, draggedNode) =>
                          void onNodeDragStop(event, draggedNode)
                  }
                  onNodesChange={
                    readOnly
                      ? undefined
                      : (changes) => void onNodesChange(changes)
                  }
                  onEdgesChange={
                    readOnly ? undefined : (changes) => onEdgesChange(changes)
                  }
                  onConnect={
                    readOnly
                      ? undefined
                      : (connection) => void onConnect(connection)
                  }
                  onDelete={
                    readOnly
                      ? undefined
                      : (changes) => {
                          void handleDelete(changes);
                        }
                  }
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
                  <CustomControls
                    showLeftPanelToggle={!readOnly}
                    onExpandAllContainers={expandAllContainers}
                    onCollapseAllContainers={collapseAllContainers}
                  />
                  {menu && <ContextMenu menu={menu} closeMenu={closeMenu} />}
                </ReactFlow>
              </div>
              {rightPanel && (
                <>
                  <PanelResizeHandle
                    direction="right"
                    onResize={(delta) =>
                      setRightPanelWidth((w) =>
                        clamp(w + delta, MIN_PANEL_WIDTH, MAX_PANEL_WIDTH),
                      )
                    }
                  />
                  <div
                    style={{
                      width: rightPanelWidth,
                      minWidth: MIN_PANEL_WIDTH,
                      flexShrink: 0,
                      height: "100%",
                      display: "flex",
                      flexDirection: "column",
                    }}
                  >
                    <PageWithRightPanel width={rightPanelWidth} />
                  </div>
                </>
              )}
            </div>
          </ElementFocusContext.Provider>
        </ElkDirectionContextProvider>
      </div>
    </Flex>
  );
};

export const ChainGraph: React.FC = () => (
  <LibraryProvider>
    <ReactFlowProvider>
      <DnDProvider>
        <ChainGraphInner />
      </DnDProvider>
    </ReactFlowProvider>
  </LibraryProvider>
);
