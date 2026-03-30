import {
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import Sider from "antd/lib/layout/Sider";
import styles from "../components/elements_library/ElementsLibrarySidebar.module.css";
import { Flex, Menu, Tabs } from "antd";
import { Editor, Monaco } from "@monaco-editor/react";
import {
  useMonacoTheme,
  applyVSCodeThemeToMonaco,
} from "../hooks/useMonacoTheme.ts";
import { OverridableIcon, IconName } from "../icons/IconProvider.tsx";
import { Element } from "../api/apiTypes.ts";
import { useModalsContext } from "../Modals.tsx";
import { useParams, useNavigate } from "react-router-dom";
import { useLibraryContext } from "../components/LibraryContext.tsx";
import {
  getLibraryElement,
  getNodeFromElement,
} from "../misc/chain-graph-utils.ts";
import type { MenuProps } from "antd";
import { api } from "../api/api.ts";
import { useNotificationService } from "../hooks/useNotificationService.tsx";
import { ChainContext } from "./ChainPage.tsx";
import { ChainElementModification } from "../components/modal/chain_element/ChainElementModification.tsx";
import { ChainGraphNode } from "../components/graph/nodes/ChainGraphNodeTypes.ts";
import { useElkDirectionContext } from "./ElkDirectionContext.tsx";
import { useFocusToElementId } from "../components/graph/ElementFocus.tsx";
import { UsedPropertiesList } from "../components/UsedPropertiesList.tsx";
import { isVsCode } from "../api/rest/vscodeExtensionApi.ts";
import { useElementsAsCode } from "../hooks/useElementsAsCode.tsx";

const DEFAULT_WIDTH = 240;

export type PageWithRightPanelProps = {
  width?: number;
};

export const PageWithRightPanel = ({
  width = DEFAULT_WIDTH,
}: PageWithRightPanelProps = {}) => {
  const chainContext = useContext(ChainContext);

  const { showModal } = useModalsContext();
  const [activeTab, setActiveTab] = useState<string>("listElements");
  const [textViewContent, setTextViewContent] = useState<string>("");

  const params = useParams<{ chainId?: string }>();
  const chainId = params.chainId;
  const { elementAsCode } = useElementsAsCode(chainId ?? "");
  const { libraryElements } = useLibraryContext();
  const monacoTheme = useMonacoTheme();
  const monacoRef = useRef<Monaco | null>(null); // eslint-disable-line @typescript-eslint/no-redundant-type-constituents -- Monaco from @monaco-editor/react may include any in union
  const notificationService = useNotificationService();
  const navigate = useNavigate();
  const [elements, setElements] = useState<Element[]>(
      chainContext?.chain?.elements ?? [],
  );

  let direction: "RIGHT" | "DOWN" = "RIGHT";
  try {
    const elkContext = useElkDirectionContext();
    direction = elkContext.direction;
  } catch {
    // useElkDirectionContext throws when used outside ElkDirectionProvider
  }

  useEffect(() => {
    setElements(chainContext?.chain?.elements ?? []);
  }, [chainContext?.chain?.elements]);

  useEffect(() => {
    const chainId = chainContext?.chain?.id;
    if (!chainId) return;

    let cancelled = false;
    const load = async () => {
      try {
        const elementsResponse = await api.getElements(chainId);
        if (cancelled) return;
        setElements(elementsResponse);
      } catch (error) {
        console.error(
          "Failed to refresh chain structure before loading schema",
          error,
        );
        notificationService.requestFailed("Failed to load elements", error);
      }
    };

    void load();
    return () => {
      cancelled = true;
    };
  }, [chainContext?.chain?.id]);

  useEffect(() => {
    if (elementAsCode?.code != null && typeof elementAsCode.code === "string") {
      setTextViewContent(elementAsCode.code);
    }
  }, [elementAsCode]);

  useEffect(() => {
    if (monacoRef.current) {
      applyVSCodeThemeToMonaco(monacoRef.current);
    }
  }, [monacoTheme]);

  const handleElementDoubleClick = useCallback(
    (element: Element) => {
      if (!chainId || !chainContext) return;

      const libraryElement = getLibraryElement(element, libraryElements);
      const node: ChainGraphNode = getNodeFromElement(
        element,
        libraryElement,
        direction,
      );

      const modalId = `chain-element-${element.id}`;
      showModal({
        id: modalId,
        component: (
          <ChainContext.Provider value={chainContext}>
            <ChainElementModification
              node={node}
              chainId={chainId}
              elementId={element.id}
              onSubmit={() => {
                void api
                  .getElements(chainId)
                  .then(setElements)
                  .catch(() => {});
              }}
              onClose={() => {
                if (chainId) {
                  void navigate(`/chains/${chainId}/graph`);
                }
              }}
            />
          </ChainContext.Provider>
        ),
      });
    },
    [chainId, chainContext, libraryElements, direction, showModal, navigate],
  );

  const handleElementDoubleClickById = useCallback(
    (elementId: string) => {
      const element = elements.find((el) => el.id === elementId);
      if (element) {
        handleElementDoubleClick(element);
      }
    },
    [elements, handleElementDoubleClick],
  );

  const focusToElementId = useFocusToElementId();
  const handleElementSingleClick = useCallback(
    (elementId: string) => {
      focusToElementId(elementId);
    },
    [focusToElementId],
  );

  const elementMenuItems: MenuProps["items"] = useMemo(() => {
    if (!elements?.length || !libraryElements) {
      return [];
    }

    return elements.map((element: Element) => {
      const libraryElement = getLibraryElement(element, libraryElements);
      const elementName = element.name || libraryElement.title || element.type;
      const elementTypeLabel = libraryElement.title || element.type;
      return {
        key: element.id,
        label: (
          <button
            type="button"
            className={styles.elementListItemLabel}
            onDoubleClick={(e) => {
              e.stopPropagation();
              handleElementDoubleClick(element);
            }}
          >
            <span className={styles.elementListItemIcon}>
              <OverridableIcon name={element.type as IconName} />
            </span>
            <div className={styles.elementListItemContent}>
              <span>{elementName}</span>
              <span className={styles.elementTypeBadge}>
                {elementTypeLabel}
              </span>
            </div>
          </button>
        ),
        title: `${elementName} (${elementTypeLabel})`,
      };
    });
  }, [elements, libraryElements, handleElementDoubleClick]);

  return (
    <Sider
      width={width}
      className={`${styles.sideMenu} ${styles.rightPanelBorder}`}
    >
      <Flex vertical={false} justify="left" style={{ width: "100%" }}>
        <Tabs
          className={`${styles.spacedTabs} ${activeTab === "textView" ? styles.rightPanelTabs : ""}`}
          activeKey={activeTab}
          onChange={setActiveTab}
          items={[
            {
              key: "listElements",
              label: <OverridableIcon name="unorderedList" />,
            },
            ...(isVsCode
              ? []
              : [
                  {
                    key: "elementProperties",
                    label: <OverridableIcon name="menuUnfold" />,
                  },
                  {
                    key: "textView",
                    label: <OverridableIcon name="file" />,
                  },
                ]),
          ]}
        ></Tabs>
      </Flex>
      <Flex
        vertical
        gap={activeTab === "textView" ? 0 : 8}
        style={{
          flex: 1,
          minHeight: 0,
          overflow: activeTab === "textView" ? "hidden" : "auto",
          paddingLeft: activeTab === "textView" ? 0 : "12px",
        }}
      >
        {activeTab === "listElements" && (
          <Menu
            className={styles.libraryElements}
            mode="vertical"
            items={elementMenuItems}
            selectable={false}
            selectedKeys={[]}
            onClick={({ key }) => handleElementSingleClick(String(key))}
            style={{ borderRight: "none", width: "100%" }}
          />
        )}
        {activeTab === "elementProperties" && chainId && (
          <UsedPropertiesList
            chainId={chainId}
            onElementSingleClick={handleElementSingleClick}
            onElementDoubleClick={handleElementDoubleClickById}
          />
        )}
        {activeTab === "elementProperties" && !chainId && (
          <div style={{ padding: "16px", textAlign: "center", color: "#999" }}>
            No chain selected
          </div>
        )}
        {activeTab === "textView" && (
          <div className={`${styles.rightPanelCodeBlock} qip-editor`}>
            <Editor
              height="100%"
              language="yaml"
              value={textViewContent}
              theme={monacoTheme}
              options={{
                readOnly: true,
                folding: true,
                fixedOverflowWidgets: true,
                minimap: { enabled: false },
                scrollBeyondLastLine: false,
              }}
              onMount={(_, monaco) => {
                monacoRef.current = monaco ?? null;
                if (monaco) {
                  applyVSCodeThemeToMonaco(monaco);
                }
              }}
            />
          </div>
        )}
      </Flex>
    </Sider>
  );
};
