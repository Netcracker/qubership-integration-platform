import React, {useEffect, useState} from "react";
import {useQuery} from "@tanstack/react-query";
import {api} from "../../api/api.ts";
import {Event} from "../../api/apiTypes.ts";
import {useEventContext} from "./contexts/EventContext.tsx";

export const EventPooling: React.FC = () => {
    const REFRESH_TIME_MS = 3 * 1000;
    const ON_ERR_TIME_MS = 8 * 1000;
    const [refetchInterval, setRefetchInterval] = useState(REFRESH_TIME_MS);

    const [lastEventId, setLastEventId] = useState<string | null>(null);
    const { publish } = useEventContext();

    const fetchEvents = async () => {
        const lastEventIdRequestParam = lastEventId ? lastEventId : "";
        return await api.getEvents(lastEventIdRequestParam);
    };

    const { data, error } = useQuery({
        queryKey: ["events"],
        queryFn: fetchEvents,
        refetchInterval,
        refetchOnWindowFocus: false,
        enabled: true,
    });

    useEffect(() => {
        if (error) {
            setRefetchInterval(refetchInterval !== ON_ERR_TIME_MS ? ON_ERR_TIME_MS : refetchInterval);
            return;
        }

        if (data && data?.events.length > 0) {
            setRefetchInterval(refetchInterval !== REFRESH_TIME_MS ? REFRESH_TIME_MS : refetchInterval);

            data.events.forEach((event: Event) => publish(event));
            setLastEventId(data?.lastEventId);
        }
    }, [data, publish]);

    return null;
};
