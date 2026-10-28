import * as monaco from "monaco-editor";

import { configureMonacoLoader } from "./monaco-loader-config";
import {
  getMonacoWorkerBasePath,
  setMonacoWorkerBasePath,
} from "./monaco-worker-config";

let monacoWebviewCompatApplied = false;

/**
 * VS Code webviews break Monaco's native Edit Context (typing, clipboard).
 * Patch editor factories once so all editors default to textarea input.
 */
function applyMonacoWebviewCompat(monacoApi: typeof monaco): void {
  if (typeof window === "undefined") return;
  if (window.location?.protocol !== "vscode-webview:") return;
  if (monacoWebviewCompatApplied) return;
  monacoWebviewCompatApplied = true;

  const originalCreate = monacoApi.editor.create.bind(monacoApi.editor);
  const originalCreateDiffEditor = monacoApi.editor.createDiffEditor.bind(
    monacoApi.editor,
  );

  monacoApi.editor.create = (domElement, options, override) =>
    originalCreate(domElement, { editContext: false, ...options }, override);

  monacoApi.editor.createDiffEditor = (domElement, options, override) =>
    originalCreateDiffEditor(
      domElement,
      { editContext: false, ...options },
      override,
    );
}

function setDefaultWorkerBasePath(baseUrl: string): void {
  if (getMonacoWorkerBasePath() !== null) return;
  setMonacoWorkerBasePath(baseUrl);
}

export function initExternalMonaco(): void {
  configureMonacoLoader({ monaco });
  applyMonacoWebviewCompat(monaco);

  if (typeof window !== "undefined") {
    let origin = window.location.origin;
    while (origin.endsWith("/")) {
      origin = origin.slice(0, -1);
    }
    const base = `${origin}/`;
    setDefaultWorkerBasePath(`${base}assets/monaco-work`);
  }
}

export function initBundledMonaco(bundleUrl: string): void {
  configureMonacoLoader({ monaco });
  applyMonacoWebviewCompat(monaco);

  const globalBase =
    typeof window !== "undefined"
      ? (window as Window & { __CIP_MONACO_WORKER_BASE__?: string })
          .__CIP_MONACO_WORKER_BASE__
      : undefined;
  if (globalBase) {
    setDefaultWorkerBasePath(globalBase);
    return;
  }

  setDefaultWorkerBasePath(new URL("assets/monaco-work", bundleUrl).href);
}
