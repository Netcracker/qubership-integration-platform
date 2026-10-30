/**
 * @jest-environment jsdom
 */

import { act, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { MemoryRouter } from "react-router";
import DefaultExtensionPage from "../../src/pages/DefaultExtensionPage.tsx";
import { api } from "../../src/api/api.ts";
import { VSCodeExtensionApi } from "../../src/api/rest/vscodeExtensionApi.ts";

const mockNavigate = jest.fn();
const sendMessageToExtension = jest.fn();

jest.mock("react-router", () => ({
  ...jest.requireActual<typeof import("react-router")>("react-router"),
  useNavigate: () => mockNavigate,
}));

jest.mock("../../src/api/rest/vscodeExtensionApi.ts", () => ({
  ...jest.requireActual<object>("../../src/api/rest/vscodeExtensionApi.ts"),
  VSCodeExtensionApi: class VSCodeExtensionApi {},
}));

jest.mock("../../src/appConfig.ts", () => ({
  ...jest.requireActual<object>("../../src/appConfig.ts"),
  configureAppExtension: jest.fn(() => Promise.resolve()),
}));

function postMessage(type: string, payload: unknown): void {
  act(() => {
    window.dispatchEvent(
      new MessageEvent("message", { data: { type, payload } }),
    );
  });
}

function startUp(): void {
  render(
    <MemoryRouter>
      <DefaultExtensionPage />
    </MemoryRouter>,
  );
  postMessage("startup", {});
}

describe("DefaultExtensionPage", () => {
  beforeEach(() => {
    mockNavigate.mockReset();
    sendMessageToExtension.mockReset();
    Object.setPrototypeOf(api, VSCodeExtensionApi.prototype);
    (api as VSCodeExtensionApi).sendMessageToExtension = sendMessageToExtension;
  });

  test("asks the extension for the path on startup", () => {
    sendMessageToExtension.mockResolvedValue({});
    startUp();

    expect(sendMessageToExtension).toHaveBeenCalledWith("navigate");
    expect(screen.getByText("Loading")).toBeInTheDocument();
  });

  test("shows the error when the extension cannot open the file", async () => {
    sendMessageToExtension.mockRejectedValue(
      new Error("Failed to parse content: not valid JSON or YAML"),
    );
    startUp();

    expect(
      await screen.findByText(
        "Failed to parse content: not valid JSON or YAML",
      ),
    ).toBeInTheDocument();
    expect(screen.getByText("Cannot open the file")).toBeInTheDocument();
    expect(mockNavigate).not.toHaveBeenCalled();
  });
});
