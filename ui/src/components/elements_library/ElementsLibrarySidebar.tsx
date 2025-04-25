import React, { useEffect, useState } from "react";
import { Menu, notification, Spin } from "antd";
import { Element, LibraryData } from "../../api/apiTypes.ts";
import { api } from "../../api/api.ts";
import DraggableElement from "./DraggableElement.tsx";
import Sider from "antd/lib/layout/Sider";

import styles from "./ElementsLibrarySidebar.module.css";

export const ElementsLibrarySidebar = () => {
  const [_elementsList, setElementsList] = useState<LibraryData | null>(null);
  const [items, setItems] = useState<MenuItem[]>([]);
  const [loading, setLoading] = useState(true);

  type MenuItem = {
    key: string;
    label: React.ReactNode;
    children?: MenuItem[];
  };

  useEffect(() => {
    const loadLibrary = async () => {
      try {
        const response = await api.getLibrary();
        setElementsList(response);

        const folderMap = new Map<string, MenuItem>();

        response.groups.forEach((group) => {
          group.elements.forEach((element: Element) => {
            if (!folderMap.has(element.folder)) {
              folderMap.set(element.folder, {
                key: element.folder,
                label: element.folder,
                children: [],
              });
            }
            folderMap.get(element.folder)!.children!.push({
              key: element.name,
              label: <DraggableElement element={element} />,
            });
          });
        });

        setItems(Array.from(folderMap.values()));
      } catch (error) {
        notification.error({
          message: "Request failed",
          description: "Failed to load library elements",
        });
      } finally {
        setLoading(false);
      }
    };
    loadLibrary();
  }, []);

  return (
    <Sider width={200} theme="light" className={styles.sideMenu}>
      {loading ? (
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
