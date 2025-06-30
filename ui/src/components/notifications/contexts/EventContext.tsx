import React, { createContext, useContext, useRef, ReactNode } from "react";
import { Event } from "../../../api/apiTypes.ts";

export type EventCallback = (event: Event) => void;

interface EventContextType {
  subscribe: (type: string, callback: EventCallback) => () => void;
  publish: (event: Event) => void;
}

const EventContext = createContext<EventContextType | undefined>(undefined);

export const EventProvider: React.FC<{ children: ReactNode }> = ({
  children,
}) => {
  const listeners = useRef<Map<string, Set<EventCallback>>>(new Map());

  const subscribe = (type: string, callback: EventCallback) => {
    if (!listeners.current.has(type)) {
      listeners.current.set(type, new Set());
    }
    listeners.current.get(type)!.add(callback);

    return () => {
      listeners.current.get(type)?.delete(callback);
    };
  };

  const publish = (event: Event) => {
    const set = listeners.current.get(event.objectType);
    if (set) {
      set.forEach((cb) => queueMicrotask(() => cb(event)));
    }
  };

  return (
    <EventContext.Provider value={{ subscribe, publish }}>
      {children}
    </EventContext.Provider>
  );
};

export const useEventContext = (): EventContextType => {
  const context = useContext(EventContext);
  if (!context) {
    throw new Error("useEventContext must be used within an EventProvider");
  }
  return context;
};
