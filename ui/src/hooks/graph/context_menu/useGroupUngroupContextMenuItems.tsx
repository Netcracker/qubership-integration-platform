import { api } from "../../../api/api";
import { Element } from "../../../api/apiTypes.ts";
import { ContextMenuItem } from "../../../components/graph/ContextMenu";
import {
  ChainGraphNode,
  ChainGraphNodeData,
} from "../../../components/graph/nodes/ChainGraphNodeTypes";
import {
  getNodeFromElement,
  sortParentsBeforeChildren,
} from "../../../misc/chain-graph-utils";
import { useNotificationService } from "../../useNotificationService";
import { useAutoLayout } from "../useAutoLayout";
import { useExpandCollapse } from "../useExpandCollapse.tsx";
import { ContextMenuItemsHook } from "./useContextMenu";
import { Node } from "@xyflow/react";
import { v4 as uuidv4 } from "uuid";

export const useGroupUngroupContextMenuItems: ContextMenuItemsHook = ({
  nodes,
  setNodes,
  edges,
  setEdges,
  chainId,
  structureChanged,
}) => {
  const { arrangeNodes, direction } = useAutoLayout();
  const notificationService = useNotificationService();
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
    if (
      selectedElements?.length === 1 &&
      selectedElements[0].data.elementType === "container"
    ) {
      return [
        {
          id: uuidv4(),
          text: "Ungroup",
          handler: () => ungroupElements(selectedElements[0]),
        },
      ];
    }

    if (
      selectedElements?.length > 1 &&
      selectedElements.every((el) => !el.parentId)
    ) {
      return [
        {
          id: uuidv4(),
          text: "Group",
          handler: () => groupElements(selectedElements),
        },
      ];
    }

    return [];
  };

  const groupElements = async (
    selectedElements: Node<ChainGraphNodeData>[],
  ) => {
    if (chainId == null) {
      return;
    }

    try {
      const container = await api.groupElements(
        chainId,
        selectedElements.map((node) => node.id),
      );

      const containerNode: ChainGraphNode = getNodeFromElement(
        container,
        undefined,
        direction,
      );
      if (!containerNode) return;

      const childNodes: ChainGraphNode[] = [];
      let nodesWithoutChangedElements = nodes;

      if (container?.children?.length) {
        nodes.forEach((prevNode) => {
          for (const childrenElement of container.children as Element[]) {
            if (prevNode.id === childrenElement.id) {
              const updatedNode: ChainGraphNode = {
                ...prevNode,
                parentId: containerNode.id,
              };
              childNodes.push(updatedNode);
              break;
            }
          }
        });

        const childrenElementIds = container.children.map((node) => node.id);
        nodesWithoutChangedElements = nodes.filter(
          (node) => !childrenElementIds.includes(node.id),
        );
      }

      const arrangedNew = await arrangeNodes(
        childNodes.concat(containerNode),
        [],
      );
      const allNodes = nodesWithoutChangedElements.concat(arrangedNew);
      const withToggle = attachToggle(allNodes);
      const withCount = setNestedUnitCounts(withToggle);

      const ordered = sortParentsBeforeChildren(withCount);
      setNodes(ordered);

      structureChanged();
    } catch (error) {
      notificationService.requestFailed("Failed to group elements", error);
    }
  };

  const ungroupElements = async (selectedGroup: Node<ChainGraphNodeData>) => {
    if (chainId == null) {
      return;
    }

    try {
      const elements = await api.ungroupElements(chainId, selectedGroup.id);

      let nodesWithoutContainer = nodes.filter(
        (node) => node.id !== selectedGroup.id,
      );

      if (elements?.length) {
        nodesWithoutContainer = nodesWithoutContainer.map((prevNode) => {
          for (const childrenElement of elements) {
            if (prevNode.id === childrenElement.id) {
              const updatedNode: ChainGraphNode = {
                ...prevNode,
                parentId: undefined,
              };
              return updatedNode;
            }
          }
          return prevNode;
        });
      }

      setNodes(nodesWithoutContainer);

      structureChanged();
    } catch (error) {
      notificationService.requestFailed("Failed to ungroup elements", error);
    }
  };

  return { buildItems };
};
