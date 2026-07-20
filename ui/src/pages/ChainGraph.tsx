import { Node, ReactFlowProvider } from "@xyflow/react";

import "@xyflow/react/dist/style.css";
import "../styles/reactflow-theme.css";
import React, {
  useCallback,
  useContext,
  useMemo,
  useRef,
  useState,
} from "react";
import { ElementsLibrarySidebar } from "../components/elements_library/ElementsLibrarySidebar.tsx";
import { DnDProvider } from "../components/DndContext.tsx";
import { Button, Flex, Splitter } from "antd";
import { useParams } from "react-router-dom";
import { useRegisterChainHeaderActions } from "./ChainHeaderActionsContext.tsx";
import {
  ElementFocusContext,
  type FitViewToElementIdFn,
} from "../components/graph/ElementFocus.tsx";
import { useModalsContext } from "../Modals.tsx";
import styles from "./ChainGraph.module.css";
import controlStyles from "../components/graph/ChainGraphViewControls.module.css";
import { LibraryProvider } from "../components/LibraryContext.tsx";
import { PageWithRightPanel } from "./PageWithRightPanel.tsx";
import { SaveAndDeploy } from "../components/modal/SaveAndDeploy.tsx";
import {
  CreateDeploymentRequest,
  DeployMode,
  DomainType,
  Element,
} from "../api/apiTypes.ts";
import { api } from "../api/api.ts";
import { useNotificationService } from "../hooks/useNotificationService.tsx";
import { SequenceDiagram } from "../components/modal/SequenceDiagram.tsx";
import { ChainContext } from "./ChainPage.tsx";
import { isVsCode } from "../api/rest/vscodeExtensionApi.ts";
import {
  ExportChainOptions,
  ExportChains,
} from "../components/modal/ExportChains.tsx";
import { downloadFile, mergeZipArchives } from "../misc/download-utils.ts";
import { exportAdditionsForChains } from "../misc/export-additions.ts";
import { generateSequenceDiagrams } from "../diagrams/main.ts";
import { Domain } from "../components/SelectDomains.tsx";
import { ProtectedButton } from "../permissions/ProtectedButton.tsx";
import { usePermissions } from "../permissions/usePermissions.tsx";
import { hasPermissions } from "../permissions/funcs.ts";
import { useGenerateDds } from "../hooks/useGenerateDds.tsx";
import { OverridableIcon } from "../icons/IconProvider.tsx";
import { ChainGraphView } from "./ChainGraphView.tsx";
import {
  ChainGraphNode,
  ChainGraphNodeData,
} from "../components/graph/nodes/ChainGraphNodeTypes.ts";
import { ChainElementModification } from "../components/modal/chain_element/ChainElementModification.tsx";
import { useNavigate } from "react-router";

const MIN_PANEL_WIDTH = 230;
const MAX_PANEL_WIDTH = 500;
const DEFAULT_LEFT_PANEL_WIDTH = 230;
const DEFAULT_RIGHT_PANEL_WIDTH = 240;

const ChainGraphInner: React.FC = () => {
  const { chainId, elementId } = useParams<string>();
  const chainContext = useContext(ChainContext);
  const { showModal } = useModalsContext();
  const [leftPanelWidth, setLeftPanelWidth] = useState(
    DEFAULT_LEFT_PANEL_WIDTH,
  );
  const [rightPanelWidth, setRightPanelWidth] = useState(
    DEFAULT_RIGHT_PANEL_WIDTH,
  );
  const notificationService = useNotificationService();
  const permissions = usePermissions();
  // Derived synchronously so a no-update user never sees the palette flash.
  const readOnly = !hasPermissions(permissions, { chain: ["update"] });
  const navigate = useNavigate();

  const [leftPanelVisible, setLeftPanelVisible] = useState<boolean>(true);
  const [rightPanelVisible, setRightPanelVisible] = useState<boolean>(false);

  const fitViewToElementIdRef = useRef<FitViewToElementIdFn | null>(null);
  const { showGenerateDdsModal } = useGenerateDds();

  const clearElementPath = useCallback(() => {
    void navigate(`/chains/${chainContext?.chain?.id}/graph`);
  }, [chainContext, navigate]);

  const setElementPath = useCallback(
    (newElementId: string) => {
      void navigate(`/chains/${chainContext?.chain?.id}/graph/${newElementId}`);
    },
    [chainContext, navigate],
  );

  const openElementModal = useCallback(
    (
      node: Node<ChainGraphNodeData> | undefined,
      updateCallback: (element: Element, node: ChainGraphNode) => void,
    ) => {
      if (!node || !node?.data.elementType) return;
      if (elementId !== node.id) {
        setElementPath(node.id);
      }
      const modalId = `chain-element-${node.id}`;
      showModal({
        id: modalId,
        component: (
          <ChainContext.Provider value={chainContext}>
            <ChainElementModification
              node={node}
              chainId={chainId!}
              elementId={node.id}
              onSubmit={(e, n) => {
                updateCallback(e, n);
                clearElementPath();
              }}
              onClose={clearElementPath}
            />
          </ChainContext.Provider>
        ),
      });
    },
    [chainContext, chainId, showModal],
  );

  const saveAndDeploy = useCallback(
    async (domains: Domain[]) => {
      if (!chainId || domains.length === 0) return;
      try {
        const snapshot = await api.createSnapshot(chainId);
        notificationService.info(
          "Created snapshot",
          `Created snapshot ${snapshot.name}`,
        );
        // Building a snapshot clears the chain's unsaved-changes flag; refresh so the banner hides.
        chainContext?.refresh?.()?.catch(() => {
          /* best-effort; refreshChain reports its own errors */
        });
        await Promise.all(
          domains.map(async (domain) => {
            if (domain.type === DomainType.MICRO) {
              await api.deploySnapshotsToMicroDomain({
                name: domain.name,
                snapshotIds: [snapshot.id],
                mode: DeployMode.APPEND,
              });
            } else {
              const request: CreateDeploymentRequest = {
                domain: domain.name,
                snapshotId: snapshot.id,
                suspended: false,
              };
              await api.createDeployment(chainId, request);
            }
            notificationService.info(
              "Deployed snapshot",
              `Deployed snapshot ${snapshot.name}`,
            );
          }),
        );
      } catch (error) {
        notificationService.requestFailed(
          "Failed to create snapshot and deploy it",
          error,
        );
      }
    },
    [chainId, chainContext, notificationService],
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
            iconName: "diagram",
            onClick: openSequenceDiagram,
          }}
        />
        {!isVsCode && (
          <>
            <ProtectedButton
              require={{ chain: ["read"] }}
              tooltipProps={{ title: "Generate DDS" }}
              buttonProps={{
                iconName: "file",
                onClick: () => showGenerateDdsModal(chainId!),
              }}
            />
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

  const toggleLeftPanelVisible = useCallback(() => {
    setLeftPanelVisible((prev) => !prev);
  }, []);

  const toggleRightPanelVisible = useCallback(() => {
    setRightPanelVisible((prev) => !prev);
  }, []);

  const graphControls = useMemo(
    () => ({
      before: (
        <>
          {!readOnly && (
            <Button
              className={controlStyles.button}
              type="text"
              title="Left Panel"
              data-active={leftPanelVisible}
              onClick={toggleLeftPanelVisible}
              icon={<OverridableIcon name="leftPanel" />}
            />
          )}

          <Button
            className={controlStyles.button}
            type="text"
            title="Right Panel"
            data-active={rightPanelVisible}
            onClick={toggleRightPanelVisible}
            icon={<OverridableIcon name="rightPanel" />}
          />
        </>
      ),
    }),
    [
      readOnly,
      leftPanelVisible,
      rightPanelVisible,
      toggleLeftPanelVisible,
      toggleRightPanelVisible,
    ],
  );

  const showLeftPanel = !readOnly && leftPanelVisible;

  return (
    <ElementFocusContext.Provider value={fitViewToElementIdRef}>
      <Splitter
        className={styles["graph-wrapper"]}
        // Sizes are controlled: antd's Splitter caches per-index pixel sizes on
        // drag and never reconciles a later-added panel, so an uncontrolled
        // (defaultSize) flex graph panel would leave 0 width for a right panel
        // opened after a resize. Controlled `size` keeps antd reading fresh props;
        // the graph stays flex via an undefined size. ChainGraphView is memoized
        // so per-tick resize updates don't re-render the graph.
        onResize={(sizes: number[]) => {
          if (showLeftPanel) {
            setLeftPanelWidth(sizes[0]);
          }
          if (rightPanelVisible) {
            setRightPanelWidth(sizes[sizes.length - 1]);
          }
        }}
      >
        {showLeftPanel && (
          <Splitter.Panel
            size={leftPanelWidth}
            min={MIN_PANEL_WIDTH}
            max={MAX_PANEL_WIDTH}
          >
            <ElementsLibrarySidebar width="100%" />
          </Splitter.Panel>
        )}
        <Splitter.Panel>
          <ChainGraphView
            readOnly={readOnly}
            submitOpenElement={openElementModal}
            controls={graphControls}
          />
        </Splitter.Panel>
        {rightPanelVisible && (
          <Splitter.Panel
            size={rightPanelWidth}
            min={MIN_PANEL_WIDTH}
            max={MAX_PANEL_WIDTH}
          >
            <PageWithRightPanel width="100%" />
          </Splitter.Panel>
        )}
      </Splitter>
    </ElementFocusContext.Provider>
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
