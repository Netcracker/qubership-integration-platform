import React, { ReactElement, useCallback, useEffect, useRef, useState } from "react";
import { Menu, Spin } from "antd";
import { LibraryElement, LibraryData } from "../../api/apiTypes.ts";
import DraggableElement from "./DraggableElement.tsx";
import Sider from "antd/lib/layout/Sider";

import styles from "./ElementsLibrarySidebar.module.css";
import { useNotificationService } from "../../hooks/useNotificationService.tsx";
import { useLibraryContext } from "../LibraryContext.tsx";
import { IconName, OverridableIcon } from "../../icons/IconProvider.tsx";
import { getElementColor } from "../../misc/chain-graph-utils.ts";
import { Flex } from "antd/lib/index";
import { SidebarSearch } from "./SidebarSearch.tsx";

export type MenuItem = {
  key: string;
  label: React.ReactNode;
  name: string;
  icon?: ReactElement;
  children?: MenuItem[];
};

export const ElementsLibrarySidebar = () => {
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
              icon: <OverridableIcon name="folderOpenFilled" style={{ fontSize: 18, color: getElementColor(element) }} />,
              children: [],
            });
          }
          const childrenMenuItems: MenuItem[] = [];
          element.designContainerParameters?.children.map((child) => {
            const childMenuItem = {
              key: child.name,
              label: <DraggableElement element={libraryData.childElements[child.name]} />,
              name: child.name,
              icon: <OverridableIcon name={child.name as IconName} style={{ fontSize: 18 }} />,
            };
            childrenMenuItems.push(childMenuItem);
          });
          const elementMenuItem: MenuItem = {
            key: element.name,
            label: <DraggableElement element={element} />,
            name: element.title,
            icon: <OverridableIcon name={element.name as IconName} style={{ fontSize: 18 }} />,
          };
          if (childrenMenuItems.length !== 0) {
            elementMenuItem.children = childrenMenuItems.sort((a, b) =>
              a.key.localeCompare(b.key)
            );
          }
          folderMap.get(element.folder)!.children!.push(elementMenuItem);
        });
      });

      const sortedFolders: MenuItem[] = Array.from(folderMap.values()).sort((a, b) =>
        a.key.localeCompare(b.key)
      );

      sortedFolders.forEach((folder) => {
        if (folder.children) {
          folder.children.sort((a, b) => a.key.localeCompare(b.key));
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
    <Sider width={230} theme="light" className={styles.sideMenu}>
      {isLibraryLoading && loading ? (
        <Spin />
      ) : (
        <Flex vertical >
          <SidebarSearch
            items={allItems.current}
            onSearch={handleSearch}
            onClear={() => {
              setItems(allItems.current);
              setIsSearch(false);
              setOpenKeysState(openKeysBeforeSearch.current);
            }}
          />
          <Menu
            className={styles.libraryElements}
            mode="inline"
            theme="light"
            items={items}
            inlineIndent={8}
            openKeys={openKeysState}
            onOpenChange={(keys) => setOpenKeysState(keys)}
          />
        </Flex>
      )}
    </Sider>
  );
};
