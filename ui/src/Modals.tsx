import React, {
  createContext,
  PropsWithChildren,
  ReactNode,
  useCallback,
  useContext,
  useMemo,
  useState,
} from "react";
import { ConfigProvider } from "antd";
import { ModalContextProvider } from "./ModalContextProvider.tsx";

const ModalsContext = createContext<PropsToForward | null>(null);

type PropsToForward = Omit<ReturnType<typeof useModals>, "modals">;

type Modal = {
  id: string;
  component: ReactNode;
};

type ShowModalProps = {
  id?: string;
  component: ReactNode;
};

const useModals = () => {
  const [modals, setModals] = useState<Modal[]>([]);

  const showModal = useCallback(
    ({ component, id = crypto.randomUUID() }: ShowModalProps) => {
      setModals((modals) => {
        const existingIndex = modals.findIndex((m) => m.id === id);
        if (existingIndex >= 0) {
          return modals;
        }
        return [...modals, { component, id }];
      });
    },
    [],
  );

  const closeModal = useCallback((id: string) => {
    setModals((components) =>
      components.filter((component) => component.id !== id),
    );
  }, []);

  return { modals, showModal, closeModal };
};

export const useModalsContext = () => {
  const context = useContext(ModalsContext);
  if (!context) {
    throw new Error("useModalsContext must be used within Modals");
  }
  return context;
};

export const Modals: React.FC<PropsWithChildren> = ({ children }) => {
  const { modals, showModal, closeModal } = useModals();
  const value = useMemo(
    () => ({ showModal, closeModal }),
    [showModal, closeModal],
  );

  return (
    <ModalsContext.Provider value={value}>
      {/* React Flow treats Space as its pan key and swallows it on window unless the
          target is inside .nokey. Monaco's EditContext input is a plain div, so an
          editor in a dialog over the chain graph would otherwise never get a space. */}
      <ConfigProvider modal={{ classNames: { body: "nokey" } }}>
        {modals.map((modal) => {
          return (
            <ModalContextProvider key={modal.id} modalId={modal.id}>
              {modal.component}
            </ModalContextProvider>
          );
        })}
      </ConfigProvider>
      {children}
    </ModalsContext.Provider>
  );
};
