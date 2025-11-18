import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import AppExtension from "./AppExtension";
import { ThemeProvider } from "./contexts/ThemeContext";

// Initialize React app for VS Code extension
export function initializeVSCodeApp() {
  const rootElement = document.getElementById("app-root");
  if (rootElement) {
    createRoot(rootElement).render(
      <StrictMode>
        <ThemeProvider>
          <AppExtension />
        </ThemeProvider>
      </StrictMode>,
    );
  } else {
    console.error("Element with id 'app-root' not found");
  }
}

// Auto-initialize if running in VS Code
if (typeof window !== 'undefined' && window.location.protocol === "vscode-webview:") {
  initializeVSCodeApp();
}
