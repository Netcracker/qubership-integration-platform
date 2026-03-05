import {
  createContext,
  type DependencyList,
  type ReactNode,
  useContext,
  useEffect,
  useRef,
} from "react";

type ChainHeaderActionsContextValue = {
  setActions: (actions: ReactNode) => void;
};

const ChainHeaderActionsContext =
  createContext<ChainHeaderActionsContextValue | null>(null);

export const useChainHeaderActions =
  (): ChainHeaderActionsContextValue | null =>
    useContext(ChainHeaderActionsContext);

export const useRegisterChainHeaderActions = (
  actions: ReactNode,
  dependencies: DependencyList = [],
): void => {
  const setActions = useChainHeaderActions()?.setActions;
  const actionsRef = useRef<ReactNode>(actions);

  actionsRef.current = actions;

  useEffect(() => {
    if (!setActions || actionsRef.current === undefined) {
      return;
    }

    setActions(actionsRef.current);
    return () => setActions(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [setActions, ...dependencies]);
};

export const ChainHeaderActionsContextProvider =
  ChainHeaderActionsContext.Provider;
