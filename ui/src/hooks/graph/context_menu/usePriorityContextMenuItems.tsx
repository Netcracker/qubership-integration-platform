import { api } from "../../../api/api";
import { LibraryElement, PatchElementRequest } from "../../../api/apiTypes";
import { ContextMenuItem } from "../../../components/graph/ContextMenu";
import { ChainGraphNodeData } from "../../../components/graph/nodes/ChainGraphNodeTypes";
import { useLibraryContext } from "../../../components/LibraryContext";
import { findLibraryElement } from "../../../misc/chain-graph-utils";
import { ContextMenuItemsHook } from "./useContextMenu";
import { Node } from "@xyflow/react";
import { v4 as uuidv4 } from "uuid";

export const usePriorityContextMenuItems: ContextMenuItemsHook = ({
  nodes,
  chainId,
  updateNodeData,
}) => {
  const { libraryElements } = useLibraryContext();

  const getPriority = (
    node: Node<ChainGraphNodeData>,
    priorityProperty: string,
  ): number => {
    return (node.data.properties as Record<string, unknown>)[
      priorityProperty
    ] as number;
  };

  const buildItems = (
    selectedElements: Node<ChainGraphNodeData>[],
  ): ContextMenuItem[] => {
    if (selectedElements.length !== 1) {
      return [];
    }

    const selectedNode = selectedElements[0];
    const libraryElement: LibraryElement = findLibraryElement(
      selectedNode.data.elementType,
      libraryElements,
    )!;

    if (selectedNode.parentId && libraryElement.ordered) {
      const priorityProperty: string =
        libraryElement.priorityProperty ?? "priority";
      const childrenOfTheSameType = nodes
        .filter((node) => selectedNode.parentId === node.parentId)
        .filter(
          (node) => selectedNode.data.elementType === node.data.elementType,
        )
        .sort((leftElement, rightElement) =>
          getPriority(leftElement, priorityProperty) >
          getPriority(rightElement, priorityProperty)
            ? 1
            : -1,
        );

      const currentIndex: number = childrenOfTheSameType
        .map((node) => node.id)
        .indexOf(selectedNode.id);
      const currentPriority: number = getPriority(
        selectedNode,
        priorityProperty,
      );

      const items: ContextMenuItem[] = [];
      if (currentIndex > 0 && currentPriority > 0) {
        const newPriority = calculateMoveUpPriority(
          childrenOfTheSameType,
          currentIndex,
          priorityProperty,
        );
        items.push({
          id: uuidv4(),
          text: `Move up (New priority: ${newPriority})`,
          handler: () =>
            updateElementPriority(
              chainId!,
              selectedNode,
              priorityProperty,
              newPriority,
            ),
        });
      }

      if (currentIndex + 1 < childrenOfTheSameType.length) {
        const newPriority = calculateMoveDownPriority(
          childrenOfTheSameType,
          currentIndex,
          priorityProperty,
        );
        items.push({
          id: uuidv4(),
          text: `Move down (New priority: ${newPriority})`,
          handler: () =>
            updateElementPriority(
              chainId!,
              selectedNode,
              priorityProperty,
              newPriority,
            ),
        });
      }

      return items;
    }

    return [];
  };

  const calculateMoveUpPriority = (
    elements: Node<ChainGraphNodeData>[],
    currentIndex: number,
    priorityProperty: string,
  ): number => {
    const prevElement = elements[currentIndex - 1];
    return getPriority(prevElement, priorityProperty);
  };

  const calculateMoveDownPriority = (
    elements: Node<ChainGraphNodeData>[],
    currentIndex: number,
    priorityProperty: string,
  ): number => {
    const nextElement = elements[currentIndex + 1];
    return getPriority(nextElement, priorityProperty);
  };

  const updateElementPriority = async (
    chainId: string,
    node: Node<ChainGraphNodeData>,
    priorityProperty: string,
    newPriority: number,
  ) => {
    const patchRequest: PatchElementRequest = buildPatchRequest(
      node,
      priorityProperty,
      newPriority,
    );

    const response = await api.updateElement(patchRequest, chainId, node.id);

    response.updatedElements?.forEach((updatedElement) => {
      const updatedNode =
        updatedElement.id === node.id
          ? node
          : nodes.find((n) => n.id === updatedElement.id)!;
      updateNodeData(updatedElement, updatedNode);
    });
  };

  const buildPatchRequest = (
    node: Node<ChainGraphNodeData>,
    priorityProperty: string,
    newPriority: number,
  ): PatchElementRequest => {
    return {
      name: node.data.label,
      description: node.data.description,
      type: node.data.elementType,
      parentElementId: node.parentId,
      properties: { ...node.data.properties, [priorityProperty]: newPriority },
    };
  };

  return { buildItems };
};
