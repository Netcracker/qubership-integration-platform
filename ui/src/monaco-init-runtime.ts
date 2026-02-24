import * as monaco from "monaco-editor";

import { configureMonacoLoader } from "./monaco-loader-config";
import {
  getMonacoWorkerBasePath,
  setMonacoWorkerBasePath,
} from "./monaco-worker-config";

function setDefaultWorkerBasePath(baseUrl: string): void {
  if (getMonacoWorkerBasePath() !== null) return;
  setMonacoWorkerBasePath(baseUrl);
}

export function initExternalMonaco(): void {
  configureMonacoLoader({ monaco });

  if (typeof window !== "undefined") {
    let origin = window.location.origin;
    while (origin.endsWith("/")) {
      origin = origin.slice(0, -1);
    }
    const base = `${origin}/`;
    setDefaultWorkerBasePath(`${base}assets/monaco-work`);
  }
}

export function initBundledMonaco(): void {
  configureMonacoLoader({ monaco });

  if (typeof document === "undefined") return;

  const globalBase =
    typeof window !== "undefined"
      ? (window as Window & { __QIP_MONACO_WORKER_BASE__?: string })
          .__QIP_MONACO_WORKER_BASE__
      : undefined;
  if (globalBase) {
    setDefaultWorkerBasePath(globalBase);
    return;
  }

  const script = document.currentScript as HTMLScriptElement | null;
  if (!script?.src) return;

  try {
    const scriptUrl = new URL(script.src);
    const pathname = scriptUrl.pathname;
    const lastSlash = pathname.lastIndexOf("/");
    const basePath =
      lastSlash === -1 ? "" : pathname.slice(0, Math.max(0, lastSlash));
    scriptUrl.pathname = `${basePath}/assets/monaco-work`;
    setDefaultWorkerBasePath(scriptUrl.href);
  } catch {
    // Ignore invalid URL and keep current config.
  }
}
