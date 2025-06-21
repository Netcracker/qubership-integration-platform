import {
  Background,
  BackgroundVariant,
  MiniMap,
  Node,
  ReactFlow,
  ReactFlowProvider,
} from "@xyflow/react";

import "@xyflow/react/dist/style.css";
import React, { DragEvent, MouseEvent, useCallback, useEffect, useRef, useState } from "react";
import { ElementsLibrarySidebar } from "../components/elements_library/ElementsLibrarySidebar.tsx";
import { DnDProvider } from "../components/DndContext.tsx";
import { Flex, FloatButton } from "antd";
import { MoreOutlined, SendOutlined } from "@ant-design/icons";
import { useNavigate } from "react-router";
import { useParams } from "react-router-dom";
import { CustomControls } from "../components/graph/CustomControls.tsx";
import FloatButtonGroup from "antd/lib/float-button/FloatButtonGroup";
import { useModalsContext } from "../Modals.tsx";
import { ChainElementModification } from "../components/modal/ChainElementModification.tsx";
import styles from "./ChainGraph.module.css";

import { ChainGraphNodeData, useChainGraph } from "../hooks/graph/useChainGraph.tsx";
import { ElkDirectionContextProvider } from "./ElkDirectionContext.tsx";
import { SaveAndDeploy } from "../components/modal/SaveAndDeploy.tsx";
import { CreateDeploymentRequest } from "../api/apiTypes.ts";
import { api } from "../api/api.ts";
import { useNotificationService } from "../hooks/useNotificationService.tsx";

const ChainGraphInner: React.FC = () => {
  const { chainId } = useParams<string>();
  let { elementId } = useParams<string>();
  const { showModal } = useModalsContext();
  const reactFlowWrapper = useRef(null);
  const [isPageLoaded, setIsPageLoaded] = useState(false);
  const navigate = useNavigate();
  const notificationService = useNotificationService();

  const onDragOver = useCallback((event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = "move";
  }, []);

  const {
    nodes,
    edges,
    onConnect,
    onDrop,
    onEdgesChange,
    onNodesChange,
    elkDirectionControl,
    updateNodeData,
    isLoading,
  } = useChainGraph(chainId);

  useEffect(() => {
    if (!isPageLoaded && !isLoading && nodes?.length) {
      setIsPageLoaded(true);
      if (elementId) {
        openElementModal(nodes.find((node) => node.id === elementId));
      }
    }
  }, [nodes]);

  const onNodeDoubleClick = (_event: MouseEvent, node: Node<ChainGraphNodeData>) => {
    openElementModal(node);
  };

  const openElementModal = (node?: Node<ChainGraphNodeData>) => {
    if (!node?.type) return;
    setElementPath(node.id);
    showModal({
      component: (
        <ChainElementModification
          node={node}
          chainId={chainId!}
          elementId={elementId!}
          onSubmit={updateNodeData}
          onClose={clearElementPath}
        />
      ),
    });
  };

  const setElementPath = (newElementId: string) => {
    navigate(`/chains/${chainId}/graph/${newElementId}`);
    elementId = newElementId;
  };

  const clearElementPath = () => {
    navigate(`/chains/${chainId}/graph`);
    elementId = undefined;
  };

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

  const openSaveAndDeployDialog = async () => {
    showModal({
      component: <SaveAndDeploy chainId={chainId} onSubmit={saveAndDeploy} />,
    });
  };

  return (
    <Flex className={styles["graph-wrapper"]}>
      <ElementsLibrarySidebar />
      <div className="react-flow-container" ref={reactFlowWrapper}>
        <ElkDirectionContextProvider elkDirectionControl={elkDirectionControl}>
          <ReactFlow
            nodes={nodes}
            edges={edges}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onDrop={onDrop}
            onDragOver={onDragOver}
            onNodeDoubleClick={onNodeDoubleClick}
            deleteKeyCode={["Backspace", "Delete"]}
            fitView
          >
            <Background variant={BackgroundVariant.Dots} />
            <MiniMap zoomable pannable position="top-right"/>
            <CustomControls />
          </ReactFlow>
        </ElkDirectionContextProvider>
      </div>
      <FloatButtonGroup trigger="hover" icon={<MoreOutlined />}>
        <FloatButton
          icon={<SendOutlined />}
          tooltip={{
            title: "Save and deploy",
            placement: "left",
          }}
          onClick={async () => openSaveAndDeployDialog()}
        />
      </FloatButtonGroup>
    </Flex>
  );
};

export const ChainGraph: React.FC = () => (
  <ReactFlowProvider>
    <DnDProvider>
      <ChainGraphInner />
    </DnDProvider>
  </ReactFlowProvider>
);
