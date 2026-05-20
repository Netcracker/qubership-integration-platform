/**
 * @jest-environment jsdom
 */
import React from "react";
import { describe, it, expect, beforeEach, jest } from "@jest/globals";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import {
  ContextSystem,
  IntegrationSystemType,
} from "../../../../src/api/apiTypes";
import { contextServiceCache } from "../../../../src/components/services/utils.tsx";
import { useNotificationService } from "../../../../src/hooks/useNotificationService.tsx";
import { useBlocker } from "react-router-dom";
import { ContextServiceParametersTab } from "../../../../src/components/services/context/ContextServiceParametersTab.tsx";

const mockGetContextService = jest.fn();
const mockUpdateContextService = jest.fn();
const mockShowModal = jest.fn();
const mockRequestFailed = jest.fn();
let isVsCodeFlag = false;

jest.mock("../../../../src/api/api", () => ({
  api: {
    getContextService: (...args: unknown[]) => mockGetContextService(...args),
    updateContextService: (...args: unknown[]) =>
      mockUpdateContextService(...args),
  },
}));

jest.mock("../../../../src/api/rest/vscodeExtensionApi.ts", () => ({
  get isVsCode() {
    return isVsCodeFlag;
  },
}));

jest.mock("../../../../src/Modals.tsx", () => ({
  useModalsContext: () => ({ showModal: mockShowModal }),
}));

jest.mock("../../../../src/hooks/useNotificationService.tsx", () => ({
  useNotificationService: jest.fn(),
}));

jest.mock("react-router-dom", () => ({
  ...jest.requireActual<typeof import("react-router-dom")>("react-router-dom"),
  useBlocker: jest.fn(),
}));

const mockDownloadFile = jest.fn();
jest.mock("../../../../src/misc/download-utils.ts", () => ({
  downloadFile: (...args: unknown[]) => mockDownloadFile(...args),
}));

jest.mock("../../../../src/components/modal/UnsavedChangesModal.tsx", () => ({
  UnsavedChangesModal: ({
    onYes,
    onNo,
    onCancelQuestion,
  }: {
    onYes: () => void;
    onNo?: () => void;
    onCancelQuestion?: () => void;
  }) => (
    <div>
      <button type="button" data-testid="unsaved-yes" onClick={onYes}>
        Yes
      </button>
      <button type="button" data-testid="unsaved-no" onClick={() => onNo?.()}>
        No
      </button>
      <button
        type="button"
        data-testid="unsaved-close"
        onClick={() => onCancelQuestion?.()}
      >
        Close
      </button>
    </div>
  ),
}));

function makeSystem(overrides: Partial<ContextSystem> = {}): ContextSystem {
  return {
    id: "sys-1",
    name: "Svc",
    type: IntegrationSystemType.CONTEXT,
    description: "d",
    labels: [],
    createdWhen: "2020-01-01T00:00:00Z",
    modifiedWhen: "2020-01-02T00:00:00Z",
    ...overrides,
  } as ContextSystem;
}

function renderTab() {
  return render(
    <ContextServiceParametersTab
      systemId="sys-1"
      activeTab="parameters"
      formatTimestamp={(v) => v}
      sidePadding={8}
      styles={{ "variables-actions": "va" }}
    />,
  );
}

describe("ContextServiceParametersTab", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    delete contextServiceCache["sys-1"];
    isVsCodeFlag = false;
    mockGetContextService.mockResolvedValue(makeSystem());
    mockUpdateContextService.mockImplementation(
      async (_id: string, s: ContextSystem) => s,
    );
    jest.mocked(useNotificationService).mockReturnValue({
      requestFailed: mockRequestFailed,
      info: jest.fn(),
    });
    jest.mocked(useBlocker).mockReturnValue({
      state: "unblocked" as const,
      proceed: jest.fn(),
      reset: jest.fn(),
    });
  });

  it("loads from contextServiceCache without calling getContextService", async () => {
    contextServiceCache["sys-1"] = makeSystem({ name: "Cached" });
    renderTab();
    await waitFor(() =>
      expect(screen.getByRole("textbox", { name: /name/i })).toHaveValue(
        "Cached",
      ),
    );
    expect(mockGetContextService).not.toHaveBeenCalled();
  });

  it("shows load error when getContextService fails", async () => {
    mockGetContextService.mockRejectedValue(new Error("boom"));
    renderTab();
    await waitFor(() => expect(screen.getByText(/boom/)).toBeInTheDocument());
  });

  it("unsaved modal Yes saves and then calls blocker proceed", async () => {
    const proceed = jest.fn();
    jest.mocked(useBlocker).mockReturnValue({
      state: "blocked" as const,
      proceed,
      reset: jest.fn(),
    });
    renderTab();
    await waitFor(() =>
      expect(screen.getByRole("textbox", { name: /name/i })).toHaveValue("Svc"),
    );
    await waitFor(() => expect(mockShowModal).toHaveBeenCalled());
    const modal = mockShowModal.mock.calls.at(-1)?.[0]
      .component as React.ReactElement;
    render(modal);
    fireEvent.click(screen.getByTestId("unsaved-yes"));
    await waitFor(() => expect(mockUpdateContextService).toHaveBeenCalled());
    expect(proceed).toHaveBeenCalled();
  });

  it("unsaved modal Yes does not open a second prompt when save updates system", async () => {
    const proceed = jest.fn();
    jest.mocked(useBlocker).mockReturnValue({
      state: "blocked" as const,
      proceed,
      reset: jest.fn(),
    });
    renderTab();
    await waitFor(() =>
      expect(screen.getByRole("textbox", { name: /name/i })).toHaveValue("Svc"),
    );
    await waitFor(() => expect(mockShowModal).toHaveBeenCalledTimes(1));
    const showModalCallsAfterFirstPrompt = mockShowModal.mock.calls.length;
    const modal = mockShowModal.mock.calls.at(-1)?.[0]
      .component as React.ReactElement;
    render(modal);
    fireEvent.click(screen.getByTestId("unsaved-yes"));
    await waitFor(() => expect(mockUpdateContextService).toHaveBeenCalled());
    expect(proceed).toHaveBeenCalled();
    await waitFor(() => {
      expect(mockShowModal.mock.calls.length).toBe(
        showModalCallsAfterFirstPrompt,
      );
    });
  });

  it("unsaved modal No calls blocker proceed without saving", async () => {
    const proceed = jest.fn();
    jest.mocked(useBlocker).mockReturnValue({
      state: "blocked" as const,
      proceed,
      reset: jest.fn(),
    });
    renderTab();
    await waitFor(() => expect(mockShowModal).toHaveBeenCalled());
    const modal = mockShowModal.mock.calls.at(-1)?.[0]
      .component as React.ReactElement;
    render(modal);
    fireEvent.click(screen.getByTestId("unsaved-no"));
    expect(proceed).toHaveBeenCalled();
    expect(mockUpdateContextService).not.toHaveBeenCalled();
  });

  it("unsaved modal close keeps editing by resetting blocker", async () => {
    const reset = jest.fn();
    jest.mocked(useBlocker).mockReturnValue({
      state: "blocked" as const,
      proceed: jest.fn(),
      reset,
    });
    renderTab();
    await waitFor(() => expect(mockShowModal).toHaveBeenCalled());
    const modal = mockShowModal.mock.calls.at(-1)?.[0]
      .component as React.ReactElement;
    render(modal);
    fireEvent.click(screen.getByTestId("unsaved-close"));
    expect(reset).toHaveBeenCalled();
    expect(mockUpdateContextService).not.toHaveBeenCalled();
  });

  it("maps technical and user labels from loaded system", async () => {
    mockGetContextService.mockResolvedValue(
      makeSystem({
        labels: [
          { name: "techL", technical: true },
          { name: "userL", technical: false },
        ],
      }),
    );
    renderTab();
    await waitFor(() => expect(screen.getByText("techL")).toBeInTheDocument());
    expect(screen.getByText("userL")).toBeInTheDocument();
  });

  it("save maps technical and user labels in payload", async () => {
    mockGetContextService.mockResolvedValue(
      makeSystem({
        labels: [
          { name: "techL", technical: true },
          { name: "userL", technical: false },
        ],
      }),
    );
    renderTab();
    await waitFor(() =>
      expect(
        screen.getByRole("textbox", { name: /name/i }),
      ).toBeInTheDocument(),
    );
    fireEvent.change(screen.getByRole("textbox", { name: /name/i }), {
      target: { value: "N2" },
    });
    fireEvent.click(screen.getByRole("button", { name: /save/i }));
    await waitFor(() => expect(mockUpdateContextService).toHaveBeenCalled());
    const payload = mockUpdateContextService.mock.calls[0][1] as ContextSystem;
    expect(payload.labels).toEqual(
      expect.arrayContaining([
        { name: "techL", technical: true },
        { name: "userL", technical: false },
      ]),
    );
  });

  it("shows save error when updateContextService fails", async () => {
    mockUpdateContextService.mockRejectedValue(new Error("save failed"));
    renderTab();
    await waitFor(() =>
      expect(
        screen.getByRole("textbox", { name: /name/i }),
      ).toBeInTheDocument(),
    );
    fireEvent.change(screen.getByRole("textbox", { name: /name/i }), {
      target: { value: "Z" },
    });
    fireEvent.click(screen.getByRole("button", { name: /save/i }));
    await waitFor(() =>
      expect(screen.getByText(/save failed/)).toBeInTheDocument(),
    );
  });
});
