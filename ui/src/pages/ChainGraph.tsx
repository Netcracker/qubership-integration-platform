import {
  Background,
  BackgroundVariant,
  MiniMap,
  ReactFlow,
  ReactFlowProvider,
} from "@xyflow/react";

import "@xyflow/react/dist/style.css";
import { useCallback, useRef, MouseEvent } from "react";
import { ElementsLibrarySidebar } from "../components/elements_library/ElementsLibrarySidebar.tsx";
import { DnDProvider } from "../components/DndContext.tsx";
import { Flex, FloatButton } from "antd";
import { SettingOutlined } from "@ant-design/icons";
import { Node } from "@xyflow/react";
import { useParams } from "react-router";
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
  const { showModal } = useModalsContext();
  const reactFlowWrapper = useRef(null);

  const {
    nodes,
    edges,
    onConnect,
    onDrop,
    onEdgesChange,
    onNodesChange,
    elkDirectionControl,
  } = useChainGraph(chainId);

  const onDragOver = useCallback((event: any) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = "move";
  }, []);

  const onNodeDoubleClick = (_event: MouseEvent, node: Node) => {
    if (!node.type) return;
    showModal({ component: <ChainElementModification nodeType={node.type} /> });
  };

  return (
      <Flex className={styles["graph-wrapper"]}>
          <ElementsLibrarySidebar />
          <div className="react-flow-container" ref={reactFlowWrapper}>
            <ElkDirectionContextProvider
              elkDirectionControl={elkDirectionControl}
            >
              <ReactFlow
                nodes={nodes}
                edges={edges}
                onNodesChange={onNodesChange}
                onEdgesChange={onEdgesChange}
                onConnect={onConnect}
                onDrop={onDrop}
                onDragOver={onDragOver}
                onNodeDoubleClick={onNodeDoubleClick}
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
