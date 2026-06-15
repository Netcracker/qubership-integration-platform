import { useLibraryContext } from "../../../components/LibraryContext.tsx";
import { useNotificationService } from "../../useNotificationService.tsx";
import { ContextMenuItemsHook } from "./useContextMenu.tsx";
import {
  CreateElementRequest,
  LibraryElement,
  Element,
} from "../../../api/apiTypes.ts";
import { api } from "../../../api/api.ts";
import {
  buildGraphNodes,
  findLibraryElement,
  getLibraryElement,
  getNodeFromElement,
  sortParentsBeforeChildren,
} from "../../../misc/chain-graph-utils.ts";
import {
  ChainGraphNode,
  ChainGraphNodeData,
} from "../../../components/graph/nodes/ChainGraphNodeTypes.ts";
import { useAutoLayout } from "../useAutoLayout.tsx";
import { getErrorMessage } from "../../../misc/error-utils.ts";
import { Node } from "@xyflow/react";
import { v4 as uuidv4 } from "uuid";
import { ContextMenuItem } from "../../../components/graph/ContextMenu.tsx";
import { useExpandCollapse } from "../useExpandCollapse.tsx";

export const useAddChildContextMenuItem: ContextMenuItemsHook = ({
  nodes,
  setNodes,
  edges,
  setEdges,
  structureChanged,
  chainId,
  onChainUpdate,
}) => {
  const notificationService = useNotificationService();
  const { libraryElements } = useLibraryContext();
  const { arrangeNodes, direction } = useAutoLayout();
  const { attachToggle, setNestedUnitCounts } = useExpandCollapse(
    nodes,
    setNodes,
    edges,
    setEdges,
    structureChanged,
  );

  const buildItems = (
    selectedElements: Node<ChainGraphNodeData>[],
  ): ContextMenuItem[] => {
    const node = selectedElements[0];
    const libraryElement = findLibraryElement(
      node.data.elementType,
      libraryElements,
    )!;

    const allowedChildren = libraryElement.allowedChildren;
    if (libraryElement.container && Object.keys(allowedChildren).length > 0) {
      return [
        {
          id: uuidv4(),
          text: "Add child",
          children: Object.keys(allowedChildren).map((key) => {
            const childTemplate: LibraryElement = findLibraryElement(
              key,
              libraryElements,
            )!;

            return {
              id: uuidv4(),
              text: childTemplate.title,
              handler: () => createChildElement(childTemplate.name, node.id),
            };
          }),
          handler: () => {},
        },
      ];
    }

    return [];
  };

  const createChildElement = async (type: string, parentId: string) => {
    const createElementRequest: CreateElementRequest = {
      type: type,
      parentElementId: parentId,
    };

    try {
      const response = await api.createElement(createElementRequest, chainId!);
      const createdElement = response.createdElements?.[0];
      if (!createdElement) return;

      const newNode: ChainGraphNode = getNodeFromElement(
        createdElement,
        getLibraryElement(createdElement, libraryElements),
        direction,
      );
      if (!newNode) return;

      const childNodes: ChainGraphNode[] = createdElement?.children
        ? createdElement.children.map((child: Element) =>
            getNodeFromElement(
              child,
              getLibraryElement(child, libraryElements),
              direction,
            ),
          )
        : [];

      const updatedNodes = buildGraphNodes(
        response.updatedElements ?? [],
        libraryElements,
      );

      const arrangedNodes = await arrangeNodes(
        childNodes.concat(newNode, ...updatedNodes),
        edges,
      );
      const allNodes = nodes
        .filter((node) => !arrangedNodes.some((n) => n.id === node.id))
        .concat(arrangedNodes);
      const withToggle = attachToggle(allNodes);
      const withCount = setNestedUnitCounts(withToggle);

      const ordered = sortParentsBeforeChildren(withCount);
      setNodes(ordered);

      structureChanged();

      if (onChainUpdate) {
        void onChainUpdate();
      }
    } catch (error) {
      notificationService.errorWithDetails(
        "Failed to create element",
        getErrorMessage(error, "Failed to create element"),
        error,
      );
    }
  };

  return { buildItems };
};
