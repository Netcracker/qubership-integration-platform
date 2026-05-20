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
      className: hasDocument ? undefined : "doc-tree-group",
    },
  ];
};

interface DocumentationSidebarProps {
  onSelect?: (path: string) => void;
  collapsed?: boolean;
}

/** Walk up parentByKey to collect all expandable ancestors of currentKey. */
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

  // Load tree structure once — TOC and paths are cached by the service layer.
  useEffect(() => {
    Promise.all([loadTOC(), loadPaths()])
      .then(([toc, paths]) => {
        const fullData = convertTOCToTreeData(
          toc,
          DOCUMENTATION_ROUTE_BASE,
          paths,
        );

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

        setTreeData(fullData);
        setExpandableKeySet(expandable);
        setParentKeyByKey(parentByKey);
      })
      .catch((error) => {
        console.error("Failed to load sidebar:", error);
      });
  }, [loadTOC, loadPaths]);

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
      return Array.from(set);
    });
  }, []);

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
        switcherIcon={<RightOutlined />}
      />
    </div>
  );
};

// Wrap in React.memo to prevent unnecessary re-renders
export const DocumentationSidebar = React.memo(DocumentationSidebarComponent);
