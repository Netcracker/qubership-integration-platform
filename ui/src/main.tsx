import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";

import "./index.css";
import "./components/graph/nodes/Node.component.css";
import { isVsCode } from "./api/rest/vscodeExtensionApi.ts";
import AppExtension from "./AppExtension.tsx";

if (isVsCode) {
  createRoot(document.getElementById("app-root") as HTMLElement).render(
    <StrictMode>
      <AppExtension />
    </StrictMode>,
  );
} else {
  createRoot(document.getElementById("root") as HTMLElement).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}

