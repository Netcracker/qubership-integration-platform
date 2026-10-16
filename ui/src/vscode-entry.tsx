import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import AppExtension from "./AppExtension.tsx";

import "./styles/theme-variables.css";
import "./styles/antd-overrides.css";

// Auto-initialize React app for VS Code extension
const initializeVSCodeApp = () => {
  if (typeof window !== "undefined" && document.getElementById("app-root")) {
    console.log("CIP UI: Auto-initializing React app for VS Code");

    const rootElement = document.getElementById("app-root");
    if (rootElement) {
      createRoot(rootElement).render(
        <StrictMode>
          <AppExtension />
        </StrictMode>,
      );
      console.log("CIP UI: React app initialized successfully");
    } else {
      console.error("CIP UI: Element with id 'app-root' not found");
    }
  }
};

// Auto-initialize when module loads
initializeVSCodeApp();

export default initializeVSCodeApp;
