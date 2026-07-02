import React, { useCallback, useContext, useEffect } from "react";
import { Node, useReactFlow } from "@xyflow/react";

export type FitViewToElementIdFn = (id: string) => void;

const withSingleNodeSelected = <T extends Node>(
  nodes: T[],
  selectedId: string,
): T[] =>
  nodes.map((node) => ({
    ...node,
    selected: node.id === selectedId,
  }));

export const ElementFocusContext =
  React.createContext<React.MutableRefObject<FitViewToElementIdFn | null> | null>(
    null,
  );

export const useElementFocusRef = () => {
  const ref = useContext(ElementFocusContext);
  if (!ref) {
    throw new Error(
      "useElementFocusRef must be used within ElementFocusContext.Provider",
    );
  }
  return ref;
};

export const useFocusToElementId = (): FitViewToElementIdFn => {
  const ref = useElementFocusRef();
  return (id: string) => ref.current?.(id);
};

export const ElementFocus = () => {
  const ref = useElementFocusRef();
  const { fitView, getNodes, setNodes } = useReactFlow();

  const focusToElementId = useCallback<FitViewToElementIdFn>(
    (id) => {
      const nodes = getNodes();
      if (!nodes.some((n) => n.id === id)) return;

      setNodes((currentNodes) => withSingleNodeSelected(currentNodes, id));

      void fitView({
        nodes: [{ id }],
        padding: 0.2,
        duration: 300,
      });
    },
    [fitView, getNodes, setNodes],
  );

  useEffect(() => {
    ref.current = focusToElementId;
    return () => {
      ref.current = null;
    };
  }, [ref, focusToElementId]);

  return null;
};
