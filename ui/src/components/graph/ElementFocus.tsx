import React, { useContext, useEffect } from "react";
import { useReactFlow } from "@xyflow/react";

export type FitViewToElementIdFn = (id: string) => void;

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
  const { fitView, getNodes } = useReactFlow();

  useEffect(() => {
    ref.current = (id: string) => {
      const nodes = getNodes();
      if (!nodes.some((n) => n.id === id)) return;
      fitView({
        nodes: [{ id }],
        padding: 0.2,
        duration: 300,
      });
    };
    return () => {
      ref.current = null;
    };
  }, [ref, fitView, getNodes]);

  return null;
};
