import React, { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "../../api/api.ts";
import { Event } from "../../api/apiTypes.ts";
import { useEventContext } from "./contexts/EventContext.tsx";

const REFRESH_TIME_MS = 3 * 1000;
const ON_ERR_TIME_MS = 8 * 1000;

export const EventPooling: React.FC = () => {
  const [refetchInterval, setRefetchInterval] = useState(REFRESH_TIME_MS);

  const [lastEventId, setLastEventId] = useState<string | null>(null);
  const { publish } = useEventContext();

  const fetchEvents = async () => {
    const lastEventIdRequestParam = lastEventId ? lastEventId : "";
    const result = await api.getEvents(lastEventIdRequestParam);
    if (!result || typeof result !== "object" || !Array.isArray(result.events)) {
      throw new Error("Invalid response format: expected EventsUpdate object");
    }
    return result;
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
      setRefetchInterval(
        refetchInterval !== ON_ERR_TIME_MS ? ON_ERR_TIME_MS : refetchInterval,
      );
      return;
    }

    if (data && typeof data === 'object' && Array.isArray(data.events) && data.events.length > 0) {
      setRefetchInterval(
        refetchInterval !== REFRESH_TIME_MS ? REFRESH_TIME_MS : refetchInterval,
      );

      data.events.forEach((event: Event) => publish(event));
      setLastEventId(data?.lastEventId);
    }
  }, [data, error, publish, refetchInterval]);

  return null;
};
