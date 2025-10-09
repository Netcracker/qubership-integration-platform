import React, { useEffect, useState } from "react";
import { Menu, Spin } from "antd";
import { LibraryElement, LibraryData } from "../../api/apiTypes.ts";
import DraggableElement from "./DraggableElement.tsx";
import Sider from "antd/lib/layout/Sider";

import styles from "./ElementsLibrarySidebar.module.css";
import { useNotificationService } from "../../hooks/useNotificationService.tsx";
import { useLibraryContext } from "../LibraryContext.tsx";

export const ElementsLibrarySidebar = () => {
  const [, setElementsList] = useState<LibraryData | null>(null);
  const [items, setItems] = useState<MenuItem[]>([]);
  const [loading, setLoading] = useState(true);
  const notificationService = useNotificationService();
  const { libraryData, isLibraryLoading } = useLibraryContext();

  type MenuItem = {
    key: string;
    label: React.ReactNode;
    children?: MenuItem[];
  };

  useEffect(() => {
    if (libraryData) {
      setElementsList(libraryData);

      const folderMap = new Map<string, MenuItem>();

      libraryData.groups.forEach((group) => {
        group.elements.forEach((element: LibraryElement) => {
          if (element.deprecated || element.unsupported) return;
          if (!folderMap.has(element.folder)) {
            folderMap.set(element.folder, {
              key: element.folder,
              label: prettifyName(element.folder),
              children: [],
            });
          }
          const childrenMenuItems: MenuItem[] = [];
          element.designContainerParameters?.children.map((child) => {
            const childMenuItem = {
              key: child.name,
              label: <DraggableElement element={child} />,
            };
            childrenMenuItems.push(childMenuItem);
          });
          const elementMenuItem: MenuItem = {
            key: element.name,
            label: <DraggableElement element={element} />,
          };
          if (childrenMenuItems.length !== 0) {
            elementMenuItem.children = childrenMenuItems.sort((a, b) =>
              a.key.localeCompare(b.key)
            );
          }
          folderMap.get(element.folder)!.children!.push(elementMenuItem);
        });
      });

      const sortedFolders = Array.from(folderMap.values()).sort((a, b) =>
        a.key.localeCompare(b.key)
      );

      sortedFolders.forEach((folder) => {
        if (folder.children) {
          folder.children.sort((a, b) => a.key.localeCompare(b.key));
        }
      });

      setItems(sortedFolders);
      setLoading(false);
    }
  }, [libraryData, notificationService]);

  const prettifyName = (name: string): string => {
    const result = name.replace(/-/g, " ");
    return result
      .toLowerCase()
      .split(" ")
      .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
      .join(" ");
  };

  return (
    <Sider width={200} theme="light" className={styles.sideMenu}>
      {isLibraryLoading && loading ? (
        <Spin />
      ) : (
        <Menu
          className={styles.libraryElements}
          mode="inline"
          theme="light"
          items={items}
        />
      )}
    </Sider>
  );
};
