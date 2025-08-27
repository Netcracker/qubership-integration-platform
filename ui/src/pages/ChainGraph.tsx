import {
  Background,
  BackgroundVariant,
  MiniMap,
  Node,
  ReactFlow,
  ReactFlowProvider,
} from "@xyflow/react";

import "@xyflow/react/dist/style.css";
import React, {
  MouseEvent,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
} from "react";
import { ElementsLibrarySidebar } from "../components/elements_library/ElementsLibrarySidebar.tsx";
import { DnDProvider } from "../components/DndContext.tsx";
import { Flex, FloatButton } from "antd";
import { MoreOutlined, SendOutlined } from "@ant-design/icons";
import { useNavigate } from "react-router";
import { useParams } from "react-router-dom";
import { CustomControls } from "../components/graph/CustomControls.tsx";
import FloatButtonGroup from "antd/lib/float-button/FloatButtonGroup";
import { useModalsContext } from "../Modals.tsx";
import { ChainElementModification } from "../components/modal/chain_element/ChainElementModification.tsx";
import styles from "./ChainGraph.module.css";
import { LibraryProvider } from "../components/LibraryContext.tsx";

import { useChainGraph } from "../hooks/graph/useChainGraph.tsx";
import { ElkDirectionContextProvider } from "./ElkDirectionContext.tsx";
import { SaveAndDeploy } from "../components/modal/SaveAndDeploy.tsx";
import { CreateDeploymentRequest, DiagramMode } from "../api/apiTypes.ts";
import { api } from "../api/api.ts";
import { useNotificationService } from "../hooks/useNotificationService.tsx";
import { SequenceDiagram } from "../components/modal/SequenceDiagram.tsx";
import { useLibraryContext } from "../components/LibraryContext.tsx";
import { ChainContext } from "./ChainPage.tsx";
import {
  ChainGraphNodeData,
  nodeTypes,
} from "../components/graph/nodes/ChainGraphNodeTypes.ts";

const ChainGraphInner: React.FC = () => {
  const { chainId, elementId } = useParams<string>();
  const chainContext = useContext(ChainContext);
  const { showModal } = useModalsContext();
  const reactFlowWrapper = useRef(null);
  const [isPageLoaded, setIsPageLoaded] = useState(false);
  const navigate = useNavigate();
  const notificationService = useNotificationService();
  const { isLibraryLoading } = useLibraryContext();

  const {
    nodes,
    edges,
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
  } = useChainGraph(chainId);

  const onNodeDoubleClick = (
    _event: MouseEvent,
    node: Node<ChainGraphNodeData>,
  ) => {
    openElementModal(node);
  };

  const setElementPath = useCallback(
    (newElementId: string) => {
      void navigate(`/chains/${chainId}/graph/${newElementId}`);
    },
    [chainId, navigate],
  );

  const clearElementPath = useCallback(() => {
    void navigate(`/chains/${chainId}/graph`);
  }, [chainId, navigate]);

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
              onSubmit={updateNodeData}
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
      updateNodeData,
    ],
  );

  const saveAndDeploy = async (domain: string) => {
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
  };

  const openSaveAndDeployDialog = () => {
    showModal({
      component: <SaveAndDeploy chainId={chainId} onSubmit={saveAndDeploy} />,
    });
  };

  const openSequenceDiagram = () => {
    showModal({
      component: (
        <SequenceDiagram
          title="Chain Sequence Diagram"
          fileNamePrefix={"chain"}
          entityId={chainId}
          diagramProvider={async () => {
            if (!chainId) {
              return Promise.reject(new Error("Chain is not specified"));
            }
            return api.getChainSequenceDiagram(chainId, [
              DiagramMode.FULL,
              DiagramMode.SIMPLE,
            ]);
          }}
        />
      ),
    });
  };

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

  return (
    <Flex className={styles["graph-wrapper"]}>
      <ElementsLibrarySidebar />
      <div className="react-flow-container" ref={reactFlowWrapper}>
        <ElkDirectionContextProvider
          elkDirectionControl={{ direction, toggleDirection }}
        >
          <ReactFlow
            nodes={nodes}
            nodeTypes={nodeTypes}
            defaultEdgeOptions={{ zIndex: 1001 }}
            edges={edges}
            onNodeDragStart={onNodeDragStart}
            onNodeDrag={onNodeDrag}
            onNodeDragStop={(event, draggedNode) =>
              void onNodeDragStop(event, draggedNode)
            }
            onNodesChange={(changes) => void onNodesChange(changes)}
            onEdgesChange={(changes) => void onEdgesChange(changes)}
            onConnect={(connection) => void onConnect(connection)}
            onDelete={(changes) => void onDelete(changes)}
            onDrop={(event) => void onDrop(event)}
            onDragOver={onDragOver}
            onNodeDoubleClick={onNodeDoubleClick}
            zoomOnDoubleClick={false}
            deleteKeyCode={["Backspace", "Delete"]}
            fitView
          >
            <Background variant={BackgroundVariant.Dots} />
            <MiniMap zoomable pannable position="top-right" />
            <CustomControls />
          </ReactFlow>
        </ElkDirectionContextProvider>
      </div>
      <FloatButtonGroup trigger="hover" icon={<MoreOutlined />}>
        <FloatButton
          icon={<>⭾</>}
          tooltip={{
            title: "Show sequence diagram",
            placement: "left",
          }}
          onClick={openSequenceDiagram}
        />
        <FloatButton
          icon={<SendOutlined />}
          tooltip={{
            title: "Save and deploy",
            placement: "left",
          }}
          onClick={openSaveAndDeployDialog}
        />
      </FloatButtonGroup>
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
