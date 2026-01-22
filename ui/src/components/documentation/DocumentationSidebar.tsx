import React, { useCallback, useEffect, useRef, useState } from "react";
import { Tree } from "antd";
import type { DataNode, TreeProps } from "antd/es/tree";
import { RightOutlined } from "@ant-design/icons";
import { useNavigate, useLocation } from "react-router-dom";
import type { TableOfContentNode } from "../../services/documentation/documentationTypes";
import { useDocumentation } from "../../hooks/useDocumentation";
import {
  DOCUMENTATION_ROUTE_BASE,
  toDocRoutePath,
} from "../../services/documentation/documentationUrlUtils";
import styles from "./DocumentationSidebar.module.css";

const convertTOCToTreeData = (
  node: TableOfContentNode,
  routeBase: string,
  paths: string[],
  indexPath: number[] = [],
): DataNode[] => {
  if (!node.title) {
    return (node.children ?? []).flatMap((child, idx) =>
      convertTOCToTreeData(child, routeBase, paths, [...indexPath, idx]),
    );
  }

  const hasDocument =
    node.documentId !== undefined && !!paths[node.documentId]?.length;

  const key = hasDocument
    ? toDocRoutePath(
        routeBase,
        paths[node.documentId as number].replace(/\.md$/, ""),
      )
    : `group/${indexPath.join(".") || "root"}`;

  const children = (node.children ?? []).flatMap((child, idx) =>
    convertTOCToTreeData(child, routeBase, paths, [...indexPath, idx]),
  );

  return [
    {
      title: node.title,
      key,
      children: children.length > 0 ? children : undefined,
      selectable: hasDocument,
    },
  ];
};

interface DocumentationSidebarProps {
  onSelect?: (path: string) => void;
  collapsed?: boolean;
}

const EXPANDED_KEYS_STORAGE_KEY = "documentation-sidebar-expanded-keys";

const DocumentationSidebarComponent: React.FC<DocumentationSidebarProps> = ({
  onSelect,
  collapsed = false,
}) => {
  const [treeData, setTreeData] = useState<DataNode[]>([]);
  const [selectedKeys, setSelectedKeys] = useState<React.Key[]>([]);
  const [expandedKeys, setExpandedKeys] = useState<React.Key[]>([]);
  const [expandableKeySet, setExpandableKeySet] = useState<Set<string>>(
    new Set(),
  );
  const [parentKeyByKey, setParentKeyByKey] = useState<
    Record<string, string | null>
  >({});
  const { loadTOC, loadPaths } = useDocumentation();
  const navigate = useNavigate();
  const location = useLocation();
  const ignoreSelectUntilRef = useRef<number>(0);

  const computeExpandedPath = (
    currentKey: string,
    parentByKey: Record<string, string | null>,
    expandable: Set<string>,
  ): string[] => {
    const result: string[] = [];
    const visited = new Set<string>();

    let key: string | null | undefined = currentKey;
    while (key && !visited.has(key)) {
      visited.add(key);
      if (expandable.has(key)) {
        result.push(key);
      }
      key = parentByKey[key];
    }

    return result.reverse();
  };

  useEffect(() => {
    Promise.all([loadTOC(), loadPaths()])
      .then(([toc, paths]) => {
        const routeBase = DOCUMENTATION_ROUTE_BASE;
        const fullData = convertTOCToTreeData(toc, routeBase, paths);
        const { expandable, parentByKey } = (() => {
          const expandable = new Set<string>();
          const parentByKey: Record<string, string | null> = {};
          const walk = (nodes: DataNode[], parentKey: string | null) => {
            for (const node of nodes) {
              const key = String(node.key);
              parentByKey[key] = parentKey;

              const children = node.children as DataNode[] | undefined;
              if (Array.isArray(children) && children.length > 0) {
                expandable.add(key);
                walk(children, key);
              }
            }
          };
          walk(fullData, null);
          return { expandable, parentByKey };
        })();

        setExpandableKeySet(expandable);
        setParentKeyByKey(parentByKey);

        // Always expand ONLY the path to current document (no merge with localStorage)
        const currentPath = location.pathname;
        const initialExpanded = computeExpandedPath(
          currentPath,
          parentByKey,
          expandable,
        );
        setExpandedKeys(initialExpanded);
        try {
          localStorage.setItem(
            EXPANDED_KEYS_STORAGE_KEY,
            JSON.stringify(initialExpanded),
          );
        } catch (error) {
          console.error("Failed to save expanded keys to localStorage:", error);
        }

        setTreeData(fullData);
      })
      .catch((error) => {
        console.error("Failed to load sidebar:", error);
      });
  }, [loadTOC, loadPaths, location.pathname]);

  useEffect(() => {
    const currentPath = location.pathname;
    setSelectedKeys([currentPath]);

    // When navigating to a new document, expand ONLY the path to that document
    if (currentPath && Object.keys(parentKeyByKey).length > 0) {
      const pathToExpand = computeExpandedPath(
        currentPath,
        parentKeyByKey,
        expandableKeySet,
      );
      setExpandedKeys(pathToExpand);
      try {
        localStorage.setItem(
          EXPANDED_KEYS_STORAGE_KEY,
          JSON.stringify(pathToExpand),
        );
      } catch (error) {
        console.error("Failed to save expanded keys to localStorage:", error);
      }
    }
  }, [location.pathname, parentKeyByKey, expandableKeySet]);

  const toggleExpanded = useCallback((key: string) => {
    setExpandedKeys((prev) => {
      const set = new Set(prev.map(String));
      if (set.has(key)) {
        set.delete(key);
      } else {
        set.add(key);
      }
      const newKeys = Array.from(set);
      try {
        localStorage.setItem(
          EXPANDED_KEYS_STORAGE_KEY,
          JSON.stringify(newKeys),
        );
      } catch (error) {
        console.error("Failed to save expanded keys to localStorage:", error);
      }
      return newKeys;
    });
  }, []);

  // Single click:
  // - If node has a document: navigate to it
  // - If node is expandable but has no document: toggle expand/collapse
  const handleSelect: TreeProps["onSelect"] = (nextSelectedKeys, info) => {
    if (Date.now() < ignoreSelectUntilRef.current) {
      return;
    }

    const key = String(info.node.key);
    const hasDocument = info.node.selectable;
    const isExpandable = expandableKeySet.has(key);

    setSelectedKeys(nextSelectedKeys);

    if (hasDocument) {
      if (onSelect) {
        onSelect(key);
      } else {
        void navigate(key);
      }
      return;
    }

    if (isExpandable) {
      ignoreSelectUntilRef.current = Date.now() + 150;
      toggleExpanded(key);
    }
  };

  const handleExpand: TreeProps["onExpand"] = (expandedKeys) => {
    setExpandedKeys(expandedKeys);
    try {
      localStorage.setItem(
        EXPANDED_KEYS_STORAGE_KEY,
        JSON.stringify(expandedKeys),
      );
    } catch (error) {
      console.error("Failed to save expanded keys to localStorage:", error);
    }
  };

  // Double click on expandable node with document: toggle expand/collapse
  // (allows expanding/collapsing without navigating)
  const handleDoubleClick: TreeProps["onDoubleClick"] = (_event, node) => {
    const key = String(node.key);
    const hasDocument = node.selectable;
    const isExpandable = expandableKeySet.has(key);

    if (hasDocument && isExpandable) {
      ignoreSelectUntilRef.current = Date.now() + 250;
      toggleExpanded(key);
    }
  };

  if (collapsed) {
    return null;
  }

  return (
    <div className={styles.sidebar}>
      <Tree
        treeData={treeData}
        selectedKeys={selectedKeys}
        onSelect={handleSelect}
        expandedKeys={expandedKeys}
        onExpand={handleExpand}
        autoExpandParent={false}
        blockNode
        expandAction={false}
        showLine={true}
        onDoubleClick={handleDoubleClick}
        switcherIcon={<RightOutlined />}
      />
    </div>
  );
};

// Wrap in React.memo to prevent unnecessary re-renders
export const DocumentationSidebar = React.memo(DocumentationSidebarComponent);
