import {
  Background,
  BackgroundVariant,
  MiniMap,
  Node,
  ReactFlow,
  ReactFlowProvider
} from "@xyflow/react";

import "@xyflow/react/dist/style.css";
import { MouseEvent, useCallback, useEffect, useRef, useState } from "react";
import { ElementsLibrarySidebar } from "../components/elements_library/ElementsLibrarySidebar.tsx";
import { DnDProvider } from "../components/DndContext.tsx";
import { Flex, FloatButton } from "antd";
import { SettingOutlined } from "@ant-design/icons";
import { useNavigate } from "react-router";
import { useParams } from "react-router-dom";
import { CustomControls } from "../components/graph/CustomControls.tsx";
import FloatButtonGroup from "antd/lib/float-button/FloatButtonGroup";
import { useModalsContext } from "../Modals.tsx";
import { ChainSettings } from "../components/modal/ChainSettings.tsx";
import { ChainElementModification } from "../components/modal/ChainElementModification.tsx";
import styles from "./ChainGraph.module.css";

import { useChainGraph } from "../hooks/graph/useChainGraph.tsx";
import { ElkDirectionContextProvider } from "./ElkDirectionContext.tsx";

const ChainGraph = () => {
  const { chainId } = useParams<string>();
  let { elementId } = useParams<string>();
  const { showModal } = useModalsContext();
  const reactFlowWrapper = useRef(null);
  const [isPageLoaded, setIsPageLoaded] = useState(false);
  const navigate = useNavigate();

  const onDragOver = useCallback((event: any) => {
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
    isLoading
  } = useChainGraph(chainId);

  useEffect(() => {
    if (!isPageLoaded && !isLoading && nodes?.length) {
      setIsPageLoaded(true);
      if (elementId) {
        openElementModal(nodes.find((node) => node.id === elementId));
      }
    }
  }, [nodes]);

  const onNodeDoubleClick = (_event: MouseEvent, node: Node) => {
    openElementModal(node);
  };

  const openElementModal = (node?: Node) => {
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
            deleteKeyCode={['Backspace', 'Delete']}
            fitView
          >
            <Background variant={BackgroundVariant.Dots} />
            <MiniMap zoomable pannable />
            <CustomControls />
          </ReactFlow>
        </ElkDirectionContextProvider>
      </div>
      <FloatButtonGroup trigger="hover" icon={<SettingOutlined />}>
        <FloatButton
          onClick={() => showModal({ component: <ChainSettings /> })}
          icon={<SettingOutlined />}
        />
      </FloatButtonGroup>
    </Flex>
  );
};

export default () => (
  <ReactFlowProvider>
    <DnDProvider>
      <ChainGraph />
    </DnDProvider>
  </ReactFlowProvider>
);
