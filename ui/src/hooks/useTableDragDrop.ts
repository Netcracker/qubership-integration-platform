import React, { useCallback, useRef, useState } from "react";
import { CatalogItemType } from "../api/apiTypes.ts";
import { traverseElementsDepthFirst } from "../misc/tree-utils.ts";

type DragDropItem = {
  id: string;
  name: string;
  parentId?: string;
  itemType: CatalogItemType;
  children?: DragDropItem[];
};

export type UseTableDragDropProps<T extends DragDropItem> = {
  tableItems: T[];
  onMoveChain: (chainId: string, folderId?: string) => Promise<void>;
  onMoveFolder: (folderId: string, targetFolderId?: string) => Promise<void>;
  disabled?: boolean;
};

export type UseTableDragDropResult<T extends DragDropItem> = {
  draggedItemId: string | null;
  dropTargetId: string | null;
  isDragging: boolean;
  dropBreadcrumbId: string | null;
  getBreadcrumbDropProps: (
    folderId?: string,
  ) => React.HTMLAttributes<HTMLSpanElement>;
  onRow: (record: T) => React.HTMLAttributes<HTMLTableRowElement>;
};

const DRAG_DATA_TYPE = "text/plain";

export function isDescendantOf(
  items: DragDropItem[],
  ancestorId: string,
  targetId: string,
): boolean {
  let found = false;
  traverseElementsDepthFirst(items, (element, path) => {
    if (element.id === targetId && path.some((p) => p.id === ancestorId)) {
      found = true;
    }
  });
  return found;
}

function canDrop(
  items: DragDropItem[],
  draggedId: string,
  draggedType: CatalogItemType,
  target: DragDropItem,
): boolean {
  if (target.itemType !== CatalogItemType.FOLDER) {
    return false;
  }
  if (target.id === draggedId) {
    return false;
  }
  if (
    draggedType === CatalogItemType.FOLDER &&
    isDescendantOf(items, draggedId, target.id)
  ) {
    return false;
  }
  return true;
}

function findParentId(
  items: DragDropItem[],
  itemId: string,
): string | undefined {
  let parentId: string | undefined;
  traverseElementsDepthFirst(items, (element) => {
    if (element.id === itemId) {
      parentId = element.parentId;
    }
  });
  return parentId;
}

export function useTableDragDrop<T extends DragDropItem>({
  tableItems,
  onMoveChain,
  onMoveFolder,
  disabled,
}: UseTableDragDropProps<T>): UseTableDragDropResult<T> {
  const [draggedItemId, setDraggedItemId] = useState<string | null>(null);
  const [dropTargetId, setDropTargetId] = useState<string | null>(null);
  const [dropBreadcrumbId, setDropBreadcrumbId] = useState<string | null>(null);

  // Refs for synchronous access in drag event handlers.
  // State updates in onDragStart cause antd Table to re-render and
  // replace the <tr> DOM node, which makes Chrome cancel the drag.
  // Using refs lets handlers read current values without triggering renders.
  const draggedIdRef = useRef<string | null>(null);
  const draggedTypeRef = useRef<CatalogItemType | null>(null);
  const dropTargetIdRef = useRef<string | null>(null);
  const previewRef = useRef<HTMLElement | null>(null);

  const resetDrag = useCallback(() => {
    draggedIdRef.current = null;
    draggedTypeRef.current = null;
    dropTargetIdRef.current = null;
    previewRef.current?.remove();
    previewRef.current = null;
    setDraggedItemId(null);
    setDropTargetId(null);
    setDropBreadcrumbId(null);
  }, []);

  const onRow = useCallback(
    (record: T): React.HTMLAttributes<HTMLTableRowElement> => {
      if (disabled) {
        return { draggable: false };
      }

      return {
        draggable: true,

        onDragStart: (e: React.DragEvent<HTMLTableRowElement>) => {
          e.dataTransfer.effectAllowed = "move";
          e.dataTransfer.setData(DRAG_DATA_TYPE, record.id);

          // Compact drag preview — a small pill with icon + name.
          // Native ghost can't be made transparent; setDragImage with
          // a simple element avoids the layout issues of cloning <tr>.
          const preview = document.createElement("div");
          const icon = e.currentTarget.querySelector(".anticon");
          if (icon) {
            preview.appendChild(icon.cloneNode(true));
            preview.appendChild(document.createTextNode(" " + record.name));
          } else {
            preview.textContent = record.name;
          }
          Object.assign(preview.style, {
            display: "inline-flex",
            alignItems: "center",
            gap: "6px",
            padding: "6px 12px",
            borderRadius: "6px",
            fontSize: "13px",
            background: "var(--vscode-editor-background, #fff)",
            color: "var(--vscode-foreground, #000)",
            border: "1px solid var(--vscode-border, #d9d9d9)",
            opacity: "0.85",
            whiteSpace: "nowrap",
            position: "fixed",
            left: "0px",
            top: "0px",
            zIndex: "-1",
            pointerEvents: "none",
          });
          document.body.appendChild(preview);
          e.dataTransfer.setDragImage(preview, 0, 0);
          previewRef.current = preview;
          requestAnimationFrame(() => {
            preview.remove();
            previewRef.current = null;
          });

          draggedIdRef.current = record.id;
          draggedTypeRef.current = record.itemType;
          // Defer state update — synchronous setState causes antd Table
          // to re-render and replace the <tr> DOM node mid-drag,
          // which makes Chrome immediately cancel the drag operation.
          requestAnimationFrame(() => setDraggedItemId(record.id));
        },

        onDragOver: (e: React.DragEvent<HTMLTableRowElement>) => {
          const draggedId = draggedIdRef.current;
          const draggedType = draggedTypeRef.current;
          if (!draggedId || !draggedType) {
            return;
          }

          if (canDrop(tableItems, draggedId, draggedType, record)) {
            e.preventDefault();
            e.dataTransfer.dropEffect = "move";
            // Avoid re-render if target hasn't changed
            if (dropTargetIdRef.current !== record.id) {
              dropTargetIdRef.current = record.id;
              setDropTargetId(record.id);
            }
          } else {
            e.dataTransfer.dropEffect = "none";
          }
        },

        onDragLeave: (e: React.DragEvent<HTMLTableRowElement>) => {
          const relatedTarget = e.relatedTarget as Node | null;
          const currentTarget = e.currentTarget as Node;
          if (relatedTarget && currentTarget.contains(relatedTarget)) {
            return;
          }
          if (dropTargetIdRef.current === record.id) {
            dropTargetIdRef.current = null;
            setDropTargetId(null);
          }
        },

        onDrop: (e: React.DragEvent<HTMLTableRowElement>) => {
          e.preventDefault();
          const draggedId =
            e.dataTransfer.getData(DRAG_DATA_TYPE) || draggedIdRef.current;
          const draggedType = draggedTypeRef.current;

          if (
            !draggedId ||
            !draggedType ||
            !canDrop(tableItems, draggedId, draggedType, record)
          ) {
            resetDrag();
            return;
          }

          const draggedParentId = findParentId(
            tableItems as DragDropItem[],
            draggedId,
          );

          // No-op if already in target folder
          if (draggedParentId === record.id) {
            resetDrag();
            return;
          }

          if (draggedType === CatalogItemType.CHAIN) {
            void onMoveChain(draggedId, record.id);
          } else {
            void onMoveFolder(draggedId, record.id);
          }

          resetDrag();
        },

        onDragEnd: () => {
          resetDrag();
        },
      };
    },
    [disabled, tableItems, onMoveChain, onMoveFolder, resetDrag],
  );

  const getBreadcrumbDropProps = useCallback(
    (folderId?: string): React.HTMLAttributes<HTMLSpanElement> => {
      if (disabled) {
        return {};
      }
      // Use "root" sentinel when folderId is undefined (Home breadcrumb)
      const targetId = folderId ?? "root";
      return {
        onDragOver: (e: React.DragEvent<HTMLSpanElement>) => {
          const draggedId = draggedIdRef.current;
          const draggedType = draggedTypeRef.current;
          if (!draggedId || !draggedType) {
            return;
          }
          if (draggedId === folderId) {
            return;
          }
          const draggedParentId = findParentId(
            tableItems as DragDropItem[],
            draggedId,
          );
          // No-op if already in this folder (undefined means root)
          if (draggedParentId === folderId) {
            return;
          }
          e.preventDefault();
          e.dataTransfer.dropEffect = "move";
          setDropBreadcrumbId(targetId);
        },
        onDragLeave: (e: React.DragEvent<HTMLSpanElement>) => {
          const relatedTarget = e.relatedTarget as Node | null;
          const currentTarget = e.currentTarget as Node;
          if (relatedTarget && currentTarget.contains(relatedTarget)) {
            return;
          }
          setDropBreadcrumbId((prev) => (prev === targetId ? null : prev));
        },
        onDrop: (e: React.DragEvent<HTMLSpanElement>) => {
          e.preventDefault();
          const draggedId =
            e.dataTransfer.getData(DRAG_DATA_TYPE) || draggedIdRef.current;
          const draggedType = draggedTypeRef.current;
          if (!draggedId || !draggedType) {
            resetDrag();
            return;
          }
          if (draggedId === folderId) {
            resetDrag();
            return;
          }
          const draggedParentId = findParentId(
            tableItems as DragDropItem[],
            draggedId,
          );
          if (draggedParentId === folderId) {
            resetDrag();
            return;
          }
          if (draggedType === CatalogItemType.CHAIN) {
            void onMoveChain(draggedId, folderId);
          } else {
            void onMoveFolder(draggedId, folderId);
          }
          resetDrag();
        },
      };
    },
    [disabled, tableItems, onMoveChain, onMoveFolder, resetDrag],
  );

  return {
    draggedItemId,
    dropTargetId,
    isDragging: draggedItemId !== null,
    dropBreadcrumbId,
    getBreadcrumbDropProps,
    onRow,
  };
}
