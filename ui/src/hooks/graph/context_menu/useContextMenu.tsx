import { MouseEvent, Dispatch, SetStateAction, useState } from "react";
import {
  ChainGraphNode,
  ChainGraphNodeData,
  OnDeleteEvent,
} from "../../../components/graph/nodes/ChainGraphNodeTypes";

import { Edge, Node } from "@xyflow/react";
import { v4 as uuidv4 } from "uuid";
import {
  ContextMenuData,
  ContextMenuItem,
} from "../../../components/graph/ContextMenu.tsx";
import {
  buildGraphNodes,
  collectChildren,
  findLibraryElement,
  sortParentsBeforeChildren,
} from "../../../misc/chain-graph-utils.ts";
import { useAutoLayout } from "./../useAutoLayout.tsx";
import { api } from "../../../api/api.ts";
import { Element } from "../../../api/apiTypes.ts";
import { useLibraryContext } from "../../../components/LibraryContext.tsx";
import { useExpandCollapse } from "./../useExpandCollapse.tsx";
import { useAddChildContextMenuItem } from "./../context_menu/useAddChildContextMenuItem.tsx";
import { usePriorityContextMenuItems } from "./../context_menu/usePriorityContextMenuItems.tsx";
import { useGroupUngroupContextMenuItems } from "./useGroupUngroupContextMenuItems.tsx";

export type ContextMenuItemsHookProps = {
  handleDelete: (changes: OnDeleteEvent) => Promise<void>;
  openElementModal: (node?: Node<ChainGraphNodeData>) => void;
  updateNodeData: (element: Element, node: ChainGraphNode) => void;
  nodes: Node<ChainGraphNodeData>[];
  setNodes: Dispatch<SetStateAction<Node<ChainGraphNodeData>[]>>;
  edges: Edge[];
  setEdges: Dispatch<React.SetStateAction<Edge[]>>;
  structureChanged: (parentIds?: string[]) => void;
  chainId?: string;
  onChainUpdate?: () => void | Promise<void>;
};

export type ContextMenuItemsHook = (props: ContextMenuItemsHookProps) => {
  buildItems: (
    selectedElements: Node<ChainGraphNodeData>[],
  ) => ContextMenuItem[];
};

export const useContextMenu = (props: ContextMenuItemsHookProps) => {
  const {
    handleDelete,
    openElementModal,
    nodes,
    setNodes,
    edges,
    setEdges,
    structureChanged,
    chainId,
    onChainUpdate,
  } = props;
  const [menu, setMenu] = useState<ContextMenuData | null>(null);
  const { arrangeNodes } = useAutoLayout();
  const [copiedNodes, setCopiedNodes] = useState<Node<ChainGraphNodeData>[]>(
    [],
  );
  const { libraryElements } = useLibraryContext();
  const { attachToggle, setNestedUnitCounts } = useExpandCollapse(
    nodes,
    setNodes,
    edges,
    setEdges,
    structureChanged,
  );
  const { buildItems: buildAddChildItem } = useAddChildContextMenuItem(props);
  const { buildItems: buildPriorityItems } = usePriorityContextMenuItems(props);
  const { buildItems: buildGroupUngroupItems } =
    useGroupUngroupContextMenuItems(props);

  const closeMenu = () => setMenu(null);

  const parentElementHasRestrictions = (
    elements: Node<ChainGraphNodeData>[],
  ): boolean => {
    for (const nodeData of elements) {
      if (
        nodeData.parentId &&
        (findLibraryElement(nodeData.data.elementType, libraryElements)
          ?.parentRestriction?.length ??
          0)
      ) {
        return true;
      }
    }
    return false;
  };

  const onContextMenuCall = (
    event: MouseEvent,
    selectedElements: Node<ChainGraphNodeData>[],
  ) => {
    event.preventDefault();

    const items: ContextMenuItem[] =
      selectedElements?.length === 0
        ? [
            {
              id: uuidv4(),
              text: "Paste",
              handler: () => pasteElements(),
              disabled: copiedNodes.length === 0,
            },
          ]
        : [
            ...(parentElementHasRestrictions(selectedElements)
              ? []
              : [
                  {
                    id: uuidv4(),
                    text: "Copy",
                    handler: () => copyElements(selectedElements),
                  },
                ]),
            ...(selectedElements?.length === 1 &&
            selectedElements[0].data.elementType === "container"
              ? [
                  {
                    id: uuidv4(),
                    text: "Paste",
                    handler: () => pasteElements(selectedElements[0]),
                    disabled: copiedNodes.length === 0,
                  },
                ]
              : []),
            {
              id: uuidv4(),
              text: "Delete",
              handler: () => deleteElements(selectedElements),
            },
          ];
    if (
      selectedElements?.length === 1 &&
      selectedElements[0].data.elementType !== "container"
    ) {
      items.unshift(...buildAddChildItem(selectedElements));

      items.unshift({
        id: uuidv4(),
        text: "Edit",
        handler: () => openElementModal(selectedElements[0]),
      });
    }
    items.unshift(...buildGroupUngroupItems(selectedElements));
    items.unshift(...buildPriorityItems(selectedElements));

    setMenu({
      x: event.clientX,
      y: event.clientY,
      items,
    });
  };

  const deleteElements = (selectedElements: Node<ChainGraphNodeData>[]) => {
    const nodesWithChildren: Node<ChainGraphNodeData>[] = [];
    for (const node of selectedElements) {
      if (node.type === "container") {
        nodesWithChildren.push(...collectChildren(node.id, nodes));
      }
      nodesWithChildren.push(node);
    }

    void handleDelete({ nodes: nodesWithChildren, edges: [] });
  };

  const copyElements = (selectedElements: Node<ChainGraphNodeData>[]) => {
    setCopiedNodes(selectedElements);
  };

  const pasteElements = async (target?: Node<ChainGraphNodeData>) => {
    if (chainId == null) {
      return;
    }

    const createdElements = await api.cloneElements(
      chainId,
      copiedNodes.map((item) => item.id),
      target?.id,
    );

    const newNodes = buildGraphNodes(createdElements, libraryElements);

    const arrangedNew = await arrangeNodes(newNodes, []);
    const allNodes = nodes.concat(arrangedNew);
    const withToggle = attachToggle(allNodes);
    const withCount = setNestedUnitCounts(withToggle);

    const ordered = sortParentsBeforeChildren(withCount);
    setNodes(ordered);

    if (onChainUpdate) {
      void onChainUpdate();
    }

    structureChanged();
  };

  return {
    menu,
    closeMenu,
    onContextMenuCall,
  };
};
