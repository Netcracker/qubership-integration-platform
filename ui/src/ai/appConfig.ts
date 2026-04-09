import { getAiServiceUrlOverride } from "../config/aiServiceUrlOverride.ts";
import React, { useState, useEffect } from "react";

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

export function getIsAiServiceAvailable(): boolean {
  const [isAiServiceAvailable, setIsAiServiceAvailable] = useState(false);

  const url = getAiServiceUrl();

  useEffect(() => {
    const url = getAiServiceUrl();
    if (!url) {
      setIsAiServiceAvailable(false);
      return;
    }
    const base = url.replace(/\/$/, "");
    fetch(`${base}/health`, { method: "GET" })
      .then((response) => {
        console.log("status:", response.status);
        setIsAiServiceAvailable(response.ok);
      })
      .catch(() => setIsAiServiceAvailable(false));
  }, []);
  return isAiServiceAvailable;
}
