import { LibraryElement } from "../../../api/apiTypes.ts";
import { ElkDirection } from "../../../hooks/graph/useElkDirection.tsx";
import { Edge, Node } from "@xyflow/react";
import { ContainerNode } from "./ContainerNode.tsx";
import { UnitNode } from "./UnitNode.tsx";
import { SwimlaneNode } from "./SwimlaneNode.tsx";

export type ChainGraphNodeData = {
  elementType: string;
  label: string;
  description: string;
  properties: LibraryElement["properties"];
  direction?: ElkDirection;
  inputEnabled?: boolean;
  outputEnabled?: boolean;
  collapsed?: boolean;
  unitCount?: number;
  onToggleCollapse?: () => void;
  typeTitle?: string;
  mandatoryChecksPassed?: boolean;
  deprecated?: boolean;
};

export type ChainGraphNode = Node<ChainGraphNodeData>;

export type ChainGraphNodeWithChildren = ChainGraphNode & {
  children?: ChainGraphNodeWithChildren[];
};

export const nodeTypes = {
  container: ContainerNode,
  swimlane: SwimlaneNode,
  unit: UnitNode,
};

export type OnDeleteEvent = {
  nodes: Node<ChainGraphNodeData>[];
  edges: Edge[];
};
