import React, { createContext, useContext, useState, ReactNode } from "react";
import { ArgsProps } from "antd/es/notification";

export type NotificationType = "info" | "warning" | "error";

export type NotificationItem = ArgsProps & {
  type?: NotificationType;
};

export type NotificationLogContextType = {
  history: NotificationItem[];
  addToHistory: (item: NotificationItem) => void;
  removeFromHistory: (item: NotificationItem) => void;
  clearHistory: () => void;
};

const NotificationLogContext = createContext<
  NotificationLogContextType | undefined
>(undefined);

export const NotificationLogProvider: React.FC<{ children: ReactNode }> = ({
  children,
}) => {
  const [history, setHistory] = useState<NotificationItem[]>([]);

  const addToHistory = (item: NotificationItem) => {
    setHistory((items) => [...items, item]);
  };

  const removeFromHistory = (item: NotificationItem) => {
    setHistory((current) => current.filter((i) => i !== item));
  };

  const clearHistory = () => {
    setHistory([]);
  };

  return (
    <NotificationLogContext.Provider
      value={{ history, addToHistory, removeFromHistory, clearHistory }}
    >
      {children}
    </NotificationLogContext.Provider>
  );
};

export const useNotificationLog = () => {
  const ctx = useContext(NotificationLogContext);
  if (!ctx)
    throw new Error(
      "useNotificationLog must be used within NotificationLogProvider",
    );
  return ctx;
};
