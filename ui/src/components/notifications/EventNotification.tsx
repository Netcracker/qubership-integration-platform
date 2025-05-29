import React from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { EventPooling } from "./EventPooling.tsx";
import { EventProvider } from "./contexts/EventContext.tsx";
import { NotificationLogProvider } from "./contexts/NotificationLogContext.tsx";

const queryClient = new QueryClient({
    defaultOptions: {
        queries: {
            refetchOnMount: false,
            refetchOnWindowFocus: false,
            refetchOnReconnect: false,
            staleTime: 0,
            retry: 0
        },
    },
});

export const EventNotification: React.FC<{ children: React.ReactNode }> = ({children}) => {
    return (
        <QueryClientProvider client={queryClient}>
            <EventProvider>
                <NotificationLogProvider>
                    <EventPooling/>
                    {children}
                </NotificationLogProvider>
            </EventProvider>
        </QueryClientProvider>
    )
}
