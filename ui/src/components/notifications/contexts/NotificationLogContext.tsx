import React, {createContext, useContext, useState, ReactNode} from "react";
import {ArgsProps} from "antd/es/notification";

type NotificationLogContextType = {
    history: ArgsProps[];
    addToHistory: (notificationConfig: ArgsProps) => void;
    removeFromHistory: (notificationConfig: ArgsProps) => void;
    clearHistory: () => void;
};

const NotificationLogContext = createContext<NotificationLogContextType | undefined>(undefined);

export const NotificationLogProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
    const [history, setHistory] = useState<ArgsProps[]>([]);

    const addToHistory = (notificationConfig: ArgsProps) => {
        setHistory((current) => [...current, notificationConfig]);
    };

    const removeFromHistory = (notificationConfig: ArgsProps) => {
        setHistory((current) => current.filter((currentNotificationConfig) => currentNotificationConfig !== notificationConfig));
    }

    const clearHistory = () => {
        setHistory([]);
    }


    return (
        <NotificationLogContext.Provider value={{ history, addToHistory, removeFromHistory, clearHistory }}>
            {children}
        </NotificationLogContext.Provider>
    );
};

export const useNotificationLog = () => {
    const ctx = useContext(NotificationLogContext);
    if (!ctx) throw new Error("useNotificationLog must be used within NotificationLogProvider");
    return ctx;
};