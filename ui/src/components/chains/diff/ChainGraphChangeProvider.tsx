import React, { createContext, PropsWithChildren, useMemo } from "react";
import { Chain } from "../../../api/apiTypes.ts";
import { Change } from "./compare/types.ts";
import { traverseElementsDepthFirst } from "../../../misc/tree-utils.ts";

export enum NodeState {
  NOT_CHANGED = "NOT_CHANGED",
  CHANGED = "CHANGED",
  REMOVED = "REMOVED",
  CREATED = "CREATED",
}

export type ChainGraphChangeContextProps = {
  nodeState: Map<string, NodeState>;
};

export const ChainGraphChangeContext = createContext<
  ChainGraphChangeContextProps | undefined
>(undefined);

export type Side = "one" | "another";

export type ChainGraphContextProviderProps = PropsWithChildren & {
  chain: Chain;
  side: Side;
  changes: Change[];
  elementMap: Map<string, string>;
};

export function getOppositeSide(side: Side): Side {
  return side === "one" ? "another" : "one";
}

export function createNodeStateMap(
  chain: Chain,
  side: Side,
  changes: Change[],
  elementMap: Map<string, string>,
): Map<string, NodeState> {
  const m = new Map<string, NodeState>();
  traverseElementsDepthFirst(chain.elements, (element) => {
    const change = changes.find(
      (c) =>
        (c.kind === "element" && c[side]?.id === element.id) ||
        (c.kind === "element-property" && c[side]?.entityId === element.id),
    );
    const oppositeSide = getOppositeSide(side);
    const state = change
      ? change[oppositeSide] === undefined
        ? NodeState.CREATED
        : NodeState.CHANGED
      : elementMap.get(element.id)
        ? NodeState.NOT_CHANGED
        : NodeState.REMOVED;
    m.set(element.id, state);
  });
  return m;
}

export const ChainGraphChangeProvider: React.FC<
  ChainGraphContextProviderProps
> = ({ chain, side, changes, children, elementMap }): React.ReactNode => {
  const nodeState = useMemo(
    () => createNodeStateMap(chain, side, changes, elementMap),
    [chain, side, changes, elementMap],
  );
  const context = useMemo(() => ({ nodeState }), [nodeState]);
  return (
    <ChainGraphChangeContext.Provider value={context}>
      {children}
    </ChainGraphChangeContext.Provider>
  );
};
