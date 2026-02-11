import Sider from "antd/lib/layout/Sider";
import styles from "../components/elements_library/ElementsLibrarySidebar.module.css";
import { SidebarSearch } from "../components/elements_library/SidebarSearch.tsx";
import { useCallback, useRef, useState, useMemo, useEffect } from "react";
import { MenuItem } from "../components/elements_library/ElementsLibrarySidebar.tsx";
import { Flex, Menu, Tabs } from "antd";
import { OverridableIcon, IconName } from "../icons/IconProvider.tsx";
import { FilterButton } from "../components/table/filter/FilterButton.tsx";
import { useChainFilters } from "../hooks/useChainFilter.ts";
import { Filter } from "../components/table/filter/Filter.tsx";
import { FilterItemState } from "../components/table/filter/FilterItem.tsx";
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
import { useContext } from "react";
import { ChainElementModification } from "../components/modal/chain_element/ChainElementModification.tsx";
import { ChainGraphNode } from "../components/graph/nodes/ChainGraphNodeTypes.ts";
import { useElkDirectionContext } from "./ElkDirectionContext.tsx";
import { UsedPropertiesList } from "../components/UsedPropertiesList.tsx";
import { isVsCode } from "../api/rest/vscodeExtensionApi.ts";
import { EntityFilterModel } from "../components/table/filter/filter.ts";

export const PageWithRightPanel = () => {
  const allItems = useRef<MenuItem[]>([]);
  const [isSearch, setIsSearch] = useState(false);
  const { filterColumns, filterItemStates, setFilterItemStates } =
    useChainFilters();
  const [openKeysState, setOpenKeysState] = useState<string[]>();
  const openKeysBeforeSearch = useRef<string[]>();
  const [items, setItems] = useState<MenuItem[]>([]);
  const { showModal } = useModalsContext();
  const [filters, setFilters] = useState<EntityFilterModel[]>([]);
  const [activeTab, setActiveTab] = useState<string>("listElements");

  const { chainId } = useParams<string>();
  const { libraryElements } = useLibraryContext();
  const notificationService = useNotificationService();
  const navigate = useNavigate();
  const chainContext = useContext(ChainContext);
  const [elements, setElements] = useState<Element[]>([]);

  let direction: "RIGHT" | "DOWN" = "RIGHT";
  try {
    const elkContext = useElkDirectionContext();
    direction = elkContext.direction;
  } catch {}

  useEffect(() => {
    if (!chainId) {
      setElements([]);
      return;
    }

    const fetchElements = async () => {
      try {
        const fetchedElements = await api.getElements(chainId);
        setElements(fetchedElements);
      } catch (error) {
        notificationService.requestFailed("Failed to load elements", error);
      }
    };

    void fetchElements();

    const handleFocus = () => {
      void fetchElements();
    };
    window.addEventListener("focus", handleFocus);

    const intervalId = setInterval(() => {
      void fetchElements();
    }, 3000);

    return () => {
      clearInterval(intervalId);
      window.removeEventListener("focus", handleFocus);
    };
  }, [chainId, notificationService]);

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
                // Element was updated - refresh the list
                void api.getElements(chainId).then(setElements);
              }}
              onClose={() => {
                if (chainId) {
                  navigate(`/chains/${chainId}/graph`);
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

  const handleElementSingleClick = useCallback((elementId: string) => {}, []);

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
          <div
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
          </div>
        ),
        title: `${elementName} (${elementTypeLabel})`,
      };
    });
  }, [elements, libraryElements, handleElementDoubleClick]);

  const handleSearch = useCallback(
    (filtered: MenuItem[], openKeys: string[]) => {
      if (!isSearch) {
        setIsSearch(true);
        openKeysBeforeSearch.current = openKeysState;
      }
      setOpenKeysState(openKeys);
      setItems(filtered);
    },
    [isSearch, openKeysState],
  );

  const applyFilters = (filterItems: FilterItemState[]) => {
    setFilterItemStates?.(filterItems);

    const f = (filterItems ?? []).map(
      (filterItem): EntityFilterModel => ({
        column: filterItem.columnValue!,
        condition: filterItem.conditionValue!,
        value: filterItem.value,
      }),
    );
    setFilters(f);
  };

  const addFilter = () => {
    showModal({
      component: (
        <Filter
          filterColumns={filterColumns ?? []}
          filterItemStates={filterItemStates ?? []}
          onApplyFilters={applyFilters}
        />
      ),
    });
  };

  return (
    <Sider width={240} className={styles.sideMenu}>
      <Flex vertical={false} justify="left" style={{ width: "100%" }}>
        <Tabs
          className={styles["spacedTabs"]}
          activeKey={activeTab}
          onChange={setActiveTab}
          items={[
            {
              key: "listElements",
              label: <OverridableIcon name="unorderedList" />,
            },
            ...(!isVsCode
              ? [
                  {
                    key: "elementProperties",
                    label: <OverridableIcon name="menuUnfold" />,
                  },
                ]
              : []),
          ]}
        ></Tabs>
      </Flex>
      <Flex vertical gap={8} style={{ paddingLeft: "12px" }}>
        <Flex gap={8} align="center">
          <SidebarSearch
            items={allItems.current}
            onSearch={handleSearch}
            onClear={() => {
              setItems(allItems.current);
              setIsSearch(false);
              setOpenKeysState(openKeysBeforeSearch.current);
            }}
          />
          <FilterButton
            count={filterItemStates?.length ?? 0}
            onClick={addFilter}
          />
        </Flex>
        {activeTab === "listElements" && (
          <Menu
            className={styles.libraryElements}
            mode="vertical"
            items={elementMenuItems}
            selectable={false}
            selectedKeys={[]}
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
      </Flex>
    </Sider>
  );
};
