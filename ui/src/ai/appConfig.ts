import { getAiServiceUrlOverride } from "../config/aiServiceUrlOverride.ts";
import { useState, useEffect } from "react";

let aiServiceUrl: string | null = null;

export function setAiServiceUrl(url: string | undefined): void {
  aiServiceUrl = url || null;
}

export function getAiServiceUrl(): string | null {
  const configured = getAiServiceUrlOverride();
  if (configured) {
    return configured;
  }
  if (aiServiceUrl) {
    return aiServiceUrl;
  }

  const envUrl = import.meta.env.VITE_AI_SERVICE_URL;
  if (envUrl) {
    return envUrl;
  }

  if (
    typeof window !== "undefined" &&
    (window.location.protocol === "http:" ||
      window.location.protocol === "https:")
  ) {
    return window.location.origin;
  }

  return null;
}

export function useIsAiServiceAvailable(): boolean {
  const [isAiServiceAvailable, setIsAiServiceAvailable] = useState(false);

  useEffect(() => {
    const url = getAiServiceUrl();
    if (!url) {
      setIsAiServiceAvailable(false);
      return;
    }
    const base = url.replace(/\/$/, "");
    fetch(`${base}/q/health`, { method: "GET" })
      .then((response) => {
        setIsAiServiceAvailable(response.ok);
      })
      .catch(() => setIsAiServiceAvailable(false));
  }, []);
  return isAiServiceAvailable;
}
