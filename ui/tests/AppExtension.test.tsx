/**
 * @jest-environment jsdom
 */

import { act, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { useLocation } from "react-router";
import { installDataRouterGlobals } from "./helpers/dataRouterGlobals.ts";

// A page that shows its name and the path it is rendered at.
function mockPage(name: string) {
  const Page = () => (
    <div>
      {name} {useLocation().pathname}
    </div>
  );
  return { __esModule: true, default: Page, [name]: Page };
}

jest.mock("../src/pages/DefaultExtensionPage.tsx", () =>
  mockPage("DefaultExtensionPage"),
);
jest.mock("../src/components/services/detail/ServiceParametersPage.tsx", () =>
  mockPage("ServiceParametersPage"),
);
jest.mock("../src/pages/ChainPage.tsx", () => mockPage("ChainPage"));
jest.mock("../src/pages/ChainGraph.tsx", () => mockPage("ChainGraph"));
jest.mock("../src/pages/ChainProperties.tsx", () =>
  mockPage("ChainProperties"),
);
jest.mock("../src/pages/Masking.tsx", () => mockPage("Masking"));
jest.mock("../src/batch/export/BatchExportRunner.tsx", () =>
  mockPage("BatchExportRunner"),
);
jest.mock("../src/components/chains/diff/ChainDiffViewWebview.tsx", () =>
  mockPage("ChainDiffViewWebview"),
);
jest.mock(
  "../src/components/services/context/ContextServiceParametersPage.tsx",
  () => mockPage("ContextServiceParametersPage"),
);
jest.mock("../src/components/services/mcp/McpServiceParametersPage.tsx", () =>
  mockPage("McpServiceParametersPage"),
);

jest.mock("../src/hooks/useVSCodeTheme.ts", () => ({
  useVSCodeTheme: () => ({ isDark: false }),
}));

import AppExtension from "../src/AppExtension.tsx";

installDataRouterGlobals();

function postMessage(type: string, payload: unknown): void {
  act(() => {
    window.dispatchEvent(
      new MessageEvent("message", { data: { type, payload } }),
    );
  });
}

describe("AppExtension", () => {
  test("navigates to the path the extension sends from a page other than the default one", async () => {
    render(<AppExtension />);
    expect(screen.getByText("DefaultExtensionPage /")).toBeInTheDocument();

    postMessage("theme-update", {});
    postMessage("navigate", undefined);
    expect(screen.getByText("DefaultExtensionPage /")).toBeInTheDocument();

    postMessage("navigate", "/services/systems/s-1/parameters");
    expect(
      await screen.findByText(
        "ServiceParametersPage /services/systems/s-1/parameters",
      ),
    ).toBeInTheDocument();

    // A "Go to operation" link activating the open service tab.
    const operation =
      "/services/systems/s-1/specificationGroups/g-1/specifications/sp-1/operations/op-1";
    postMessage("navigate", operation);
    expect(
      await screen.findByText(`ServiceParametersPage ${operation}`),
    ).toBeInTheDocument();
  });
});
