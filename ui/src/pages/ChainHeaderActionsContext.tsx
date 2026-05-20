import {
  createContext,
  type DependencyList,
  type ReactNode,
  useContext,
  useEffect,
  useRef,
} from "react";

type ChainHeaderActionsContextValue = {
  registerHeaderActions: (actions: ReactNode) => () => void;
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
  const registerHeaderActions = useChainHeaderActions()?.registerHeaderActions;
  const actionsRef = useRef<ReactNode>(actions);

  actionsRef.current = actions;

  useEffect(() => {
    if (!registerHeaderActions || actionsRef.current === undefined) {
      return;
    }

    return registerHeaderActions(actionsRef.current);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [registerHeaderActions, ...dependencies]);
};

export const ChainHeaderActionsContextProvider =
  ChainHeaderActionsContext.Provider;
