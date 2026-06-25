import {
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  ReactElement,
  ReactNode,
} from "react";
import styles from "../components/elements_library/ElementsLibrarySidebar.module.css";
import { Flex, Layout, Menu, Tabs } from "antd";
import { OverridableIcon } from "../icons/IconProvider.tsx";
import { Element, LibraryElement } from "../api/apiTypes.ts";
import { useModalsContext } from "../Modals.tsx";
import { useParams, useNavigate } from "react-router-dom";
import { useLibraryContext } from "../components/LibraryContext.tsx";
import {
  getLibraryElement,
  getNodeFromElement,
} from "../misc/chain-graph-utils.ts";
import { api } from "../api/api.ts";
import { useNotificationService } from "../hooks/useNotificationService.tsx";
import { ChainContext } from "./ChainPage.tsx";
import { ChainElementModification } from "../components/modal/chain_element/ChainElementModification.tsx";
import { ChainGraphNode } from "../components/graph/nodes/ChainGraphNodeTypes.ts";
import { useElkDirectionContext } from "./ElkDirectionContext.tsx";
import { useFocusToElementId } from "../components/graph/ElementFocus.tsx";
import { UsedPropertiesList } from "../components/UsedPropertiesList.tsx";
import { isVsCode } from "../api/rest/vscodeExtensionApi.ts";
import { ChainTextViewPanel } from "../components/chains/ChainTextViewPanel.tsx";
import { CompactSearch } from "../components/table/CompactSearch.tsx";
import { useChainElementPanelFilters } from "../hooks/useChainElementPanelFilters.ts";
import { applyEntityFiltersToElements } from "../misc/entity-filter-utils.ts";
import { matchesByFields } from "../components/table/tableSearch.ts";

const { Sider } = Layout;

const DEFAULT_WIDTH = 240;

export type PageWithRightPanelProps = {
  width?: number;
};

export type MenuItem = {
  key: string;
  label: ReactNode;
  name: string;
  icon?: ReactElement;
  children?: MenuItem[];
};

function elementMatchesSearch(
  element: Element,
  searchString: string,
  libraryElements: LibraryElement[] | null,
): boolean {
  const libraryElement = getLibraryElement(element, libraryElements);
  const elementName = element.name || libraryElement.title || element.type;
  const elementTypeLabel = libraryElement.title || element.type;
  return matchesByFields(searchString, [
    elementName,
    elementTypeLabel,
    element.type,
  ]);
}

export const PageWithRightPanel = ({
  width = DEFAULT_WIDTH,
}: PageWithRightPanelProps = {}) => {
  const chainContext = useContext(ChainContext);

  const { showModal } = useModalsContext();
  const [activeTab, setActiveTab] = useState<string>("listElements");
  const [searchString, setSearchString] = useState<string>("");

  const params = useParams<{ chainId?: string }>();
  const chainId = params.chainId;
  const { libraryElements } = useLibraryContext();
  const notificationService = useNotificationService();
  const navigate = useNavigate();
  const [elements, setElements] = useState<Element[]>(
    chainContext?.chain?.elements ?? [],
  );

  const elementTypeValues = useMemo(
    () =>
      [...new Set(elements.map((element) => element.type))].map((type) => ({
        value: type,
        label:
          libraryElements?.find(
            (libraryElement) => libraryElement.name === type,
          )?.title ?? type,
      })),
    [elements, libraryElements],
  );

  const { filters, filterButton } =
    useChainElementPanelFilters(elementTypeValues);

  let direction: "RIGHT" | "DOWN" = "RIGHT";
  try {
    const elkContext = useElkDirectionContext();
    direction = elkContext.direction;
  } catch {
    direction = "RIGHT";
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
        notificationService.requestFailed("Failed to load elements", error);
      }
    };

    void load();
    return () => {
      cancelled = true;
    };
  }, [chainContext?.chain?.id]);

  const filteredElements = useMemo(() => {
    const filteredByRules = applyEntityFiltersToElements(
      elements,
      filters,
      libraryElements,
    );
    if (!searchString.trim()) {
      return filteredByRules;
    }
    return filteredByRules.filter((element) =>
      elementMatchesSearch(element, searchString, libraryElements),
    );
  }, [elements, filters, libraryElements, searchString]);

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

  const elementMenuItems: MenuItem[] = useMemo(() => {
    if (!filteredElements.length || !libraryElements) {
      return [];
    }

    return filteredElements.map((element: Element) => {
      const libraryElement = getLibraryElement(element, libraryElements);
      const elementName = element.name || libraryElement.title || element.type;
      const elementTypeLabel = libraryElement.title || element.type;
      return {
        key: element.id,
        name: elementName,
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
              <OverridableIcon name={element.type} />
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
  }, [filteredElements, libraryElements, handleElementDoubleClick]);

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
            {
              key: "elementProperties",
              label: <OverridableIcon name="menuUnfold" />,
            },
            ...(isVsCode
              ? []
              : [
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
          paddingLeft: activeTab === "textView" ? 0 : "8px",
          paddingRight: activeTab === "textView" ? 0 : "8px",
          paddingBottom: activeTab === "textView" ? 0 : "8px",
        }}
      >
        {activeTab === "listElements" && (
          <Flex
            vertical
            style={{ flex: 1, minHeight: 0, overflow: "auto", width: "100%" }}
            gap={4}
          >
            <div className={styles.sidebarSearchArea}>
              <Flex align="center" gap={8} style={{ width: "100%" }}>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <CompactSearch
                    value={searchString}
                    onChange={setSearchString}
                    allowClear
                    onClear={() => setSearchString("")}
                    style={{ width: "100%" }}
                  />
                </div>
                <span style={{ flexShrink: 0 }}>{filterButton}</span>
              </Flex>
            </div>
            <Menu
              className={styles.libraryElements}
              mode="vertical"
              items={elementMenuItems}
              selectable={false}
              selectedKeys={[]}
              onClick={({ key }) => handleElementSingleClick(String(key))}
              style={{ borderRight: "none", width: "100%" }}
            />
          </Flex>
        )}
        {activeTab === "elementProperties" && chainId && (
          <UsedPropertiesList
            elements={elements}
            onElementSingleClick={handleElementSingleClick}
            onElementDoubleClick={handleElementDoubleClickById}
          />
        )}
        {activeTab === "elementProperties" && !chainId && (
          <div
            style={{
              padding: "16px",
              textAlign: "center",
              color: "var(--vscode-descriptionForeground)",
            }}
          >
            No chain selected
          </div>
        )}
        {activeTab === "textView" && (
          <ChainTextViewPanel chainId={chainId ?? ""} elements={elements} />
        )}
      </Flex>
    </Sider>
  );
};
