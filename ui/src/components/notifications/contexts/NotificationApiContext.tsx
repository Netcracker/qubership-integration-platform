import { createContext, useContext, ReactNode } from "react";
import type { NotificationInstance } from "antd/es/notification/interface";

const NotificationApiContext = createContext<NotificationInstance | null>(null);

type ProviderProps = {
  api: NotificationInstance;
  children: ReactNode;
};

export const NotificationApiProvider = ({ api, children }: ProviderProps) => {
  return (
    <NotificationApiContext.Provider value={api}>
      {children}
    </NotificationApiContext.Provider>
  );
};

export const useNotificationApi = (): NotificationInstance => {
  const api = useContext(NotificationApiContext);

  if (!api) {
    throw new Error("NotificationApiProvider is missing in the component tree");
  }

  return api;
};
