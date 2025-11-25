import React from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { notification } from "antd";
import { EventPooling } from "./EventPooling.tsx";
import { EventProvider } from "./contexts/EventContext.tsx";
import { NotificationLogProvider } from "./contexts/NotificationLogContext.tsx";
import { NotificationApiProvider } from "./contexts/NotificationApiContext.tsx";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      refetchOnMount: false,
      refetchOnWindowFocus: false,
      refetchOnReconnect: false,
      staleTime: 0,
      retry: 0,
    },
  },
});

export const EventNotification: React.FC<{ children: React.ReactNode }> = ({
  children,
}) => {
  const [notificationApi, notificationContextHolder] =
    notification.useNotification();

  return (
    <QueryClientProvider client={queryClient}>
      <NotificationApiProvider api={notificationApi}>
        {notificationContextHolder}
        <EventProvider>
          <NotificationLogProvider>
            <EventPooling />
            {children}
          </NotificationLogProvider>
        </EventProvider>
      </NotificationApiProvider>
    </QueryClientProvider>
  );
};
