import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import AppExtension from "./AppExtension";

// Initialize React app for VS Code extension
export function initializeVSCodeApp() {
  const rootElement = document.getElementById("app-root");
  if (rootElement) {
    createRoot(rootElement).render(
      <StrictMode>
        <AppExtension />
      </StrictMode>,
    );
  } else {
    console.error("Element with id 'app-root' not found");
  }
}

// Auto-initialize if running in VS Code
if (
  typeof window !== "undefined" &&
  window.location.protocol === "vscode-webview:"
) {
  initializeVSCodeApp();
}
