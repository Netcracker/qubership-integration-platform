import React, {
  ReactElement,
  useCallback,
  useEffect,
  useRef,
  useState,
} from "react";
import { Flex, Layout, Menu, Spin } from "antd";
import { LibraryElement, LibraryData } from "../../api/apiTypes.ts";
import DraggableElement from "./DraggableElement.tsx";

import styles from "./ElementsLibrarySidebar.module.css";
import { useNotificationService } from "../../hooks/useNotificationService.tsx";
import { useLibraryContext } from "../LibraryContext.tsx";
import { OverridableIcon } from "../../icons/IconProvider.tsx";
import { getElementColor } from "../../misc/chain-graph-utils.ts";
import { SidebarSearch } from "./SidebarSearch.tsx";

const { Sider } = Layout;

export type MenuItem = {
  key: string;
  label: React.ReactNode;
  name: string;
  icon?: ReactElement;
  children?: MenuItem[];
};

const DEFAULT_WIDTH = 230;

// Fixed display order for container children; anything unlisted stays alphabetical.
const CHILD_ORDER = ["if", "else", "try-2", "catch-2", "finally-2"];
const childRank = (key: string): number => {
  const index = CHILD_ORDER.indexOf(key);
  return index === -1 ? Number.MAX_SAFE_INTEGER : index;
};
const compareChildren = (a: MenuItem, b: MenuItem): number =>
  childRank(a.key) - childRank(b.key) || a.name.localeCompare(b.name);

export type ElementsLibrarySidebarProps = {
  width?: number | string;
};

export const ElementsLibrarySidebar = ({
  width = DEFAULT_WIDTH,
}: ElementsLibrarySidebarProps = {}) => {
  const [, setElementsList] = useState<LibraryData | null>(null);
  const allItems = useRef<MenuItem[]>([]);
  const [items, setItems] = useState<MenuItem[]>([]);
  const [loading, setLoading] = useState(true);
  const notificationService = useNotificationService();
  const { libraryData, isLibraryLoading } = useLibraryContext();

  const [openKeysState, setOpenKeysState] = useState<string[]>();
  const openKeysBeforeSearch = useRef<string[]>();
  const [isSearch, setIsSearch] = useState(false);

  useEffect(() => {
    if (libraryData) {
      setElementsList(libraryData);

      const folderMap = new Map<string, MenuItem>();

      libraryData.groups.forEach((group) => {
        group.elements.forEach((element: LibraryElement) => {
          if (element.deprecated || element.unsupported) return;
          if (!folderMap.has(element.folder)) {
            const name = prettifyName(element.folder);
            folderMap.set(element.folder, {
              key: element.folder,
              label: name,
              name: name,
              icon: (
                <OverridableIcon
                  name="folderOpenFilled"
                  style={{ fontSize: 18, color: getElementColor(element) }}
                />
              ),
              children: [],
            });
          }
          const childrenMenuItems: MenuItem[] = [];
          element.designContainerParameters?.children.map((child) => {
            const childMenuItem = {
              key: child.name,
              label: (
                <DraggableElement
                  element={libraryData.childElements[child.name]}
                />
              ),
              name: libraryData.childElements[child.name]?.title ?? child.name,
              icon: (
                <OverridableIcon name={child.name} style={{ fontSize: 18 }} />
              ),
            };
            childrenMenuItems.push(childMenuItem);
          });
          const elementMenuItem: MenuItem = {
            key: element.name,
            label: <DraggableElement element={element} />,
            name: element.title,
            icon: (
              <OverridableIcon name={element.name} style={{ fontSize: 18 }} />
            ),
          };
          if (childrenMenuItems.length !== 0) {
            childrenMenuItems.sort(compareChildren);
            elementMenuItem.children = childrenMenuItems;
          }
          folderMap.get(element.folder)!.children!.push(elementMenuItem);
        });
      });

      const sortedFolders: MenuItem[] = Array.from(folderMap.values()).sort(
        (a, b) => a.name.localeCompare(b.name),
      );

      sortedFolders.forEach((folder) => {
        if (folder.children) {
          folder.children.sort((a, b) => a.name.localeCompare(b.name));
        }
      });

      allItems.current = sortedFolders;
      setItems([...sortedFolders]);
      setLoading(false);
    }
  }, [libraryData, notificationService]);

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

  const prettifyName = (name: string): string => {
    const result = name.replace(/-/g, " ");
    return result
      .toLowerCase()
      .split(" ")
      .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
      .join(" ");
  };

  return (
    <Sider width={width} className={styles.sideMenu}>
      {isLibraryLoading && loading ? (
        <Spin />
      ) : (
        <Flex vertical style={{ flex: 1, minHeight: 0, width: "100%" }}>
          <div className={styles.sidebarSearchArea}>
            <SidebarSearch
              items={allItems.current}
              onSearch={handleSearch}
              onClear={() => {
                setItems(allItems.current);
                setIsSearch(false);
                setOpenKeysState(openKeysBeforeSearch.current);
              }}
            />
          </div>
          <div className={styles.sidebarMenuArea}>
            <Menu
              className={styles.libraryElements}
              mode="inline"
              items={items}
              selectable={false}
              inlineIndent={0}
              openKeys={openKeysState}
              onOpenChange={(keys) => setOpenKeysState(keys)}
            />
          </div>
        </Flex>
      )}
    </Sider>
  );
};
