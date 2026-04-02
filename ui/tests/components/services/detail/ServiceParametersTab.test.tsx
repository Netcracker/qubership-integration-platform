/**
 * @jest-environment jsdom
 */
import React from "react";
import { describe, it, expect, beforeEach, jest } from "@jest/globals";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import {
  IntegrationSystem,
  IntegrationSystemType,
} from "../../../../src/api/apiTypes";
import { UserPermissionsContext } from "../../../../src/permissions/UserPermissionsContext.tsx";
import { getAllPermissions } from "../../../../src/permissions/funcs.ts";
import { serviceCache } from "../../../../src/components/services/utils.tsx";
import { ServiceParametersToolbarContext } from "../../../../src/components/services/detail/ServiceParametersPage";
import { ServiceParametersTab } from "../../../../src/components/services/detail/ServiceParametersTab";
import { useNotificationService } from "../../../../src/hooks/useNotificationService.tsx";
import { useBlocker } from "react-router-dom";

const mockGetService = jest.fn();
const mockUpdateService = jest.fn();
const mockExportServices = jest.fn();
const mockShowModal = jest.fn();
const mockSetToolbar = jest.fn();
const mockRequestFailed = jest.fn();
let isVsCodeFlag = false;

jest.mock("../../../../src/api/api", () => ({
  api: {
    getService: (...args: unknown[]) => mockGetService(...args),
    updateService: (...args: unknown[]) => mockUpdateService(...args),
    exportServices: (...args: unknown[]) => mockExportServices(...args),
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
  }: {
    onYes: () => void;
    onNo?: () => void;
  }) => (
    <div>
      <button type="button" data-testid="unsaved-yes" onClick={onYes}>
        Yes
      </button>
      <button type="button" data-testid="unsaved-no" onClick={() => onNo?.()}>
        No
      </button>
    </div>
  ),
}));

jest.mock("../../../../src/permissions/ProtectedButton.tsx", () => ({
  ProtectedButton: ({
    buttonProps,
    tooltipProps,
  }: {
    buttonProps: Record<string, unknown> & { onClick?: () => void };
    tooltipProps: { title: string };
  }) => {
    const { iconName: _i, icon: _n, ...rest } = buttonProps;
    return (
      <button
        type="button"
        data-testid={`param-toolbar-${String(tooltipProps.title).replace(/\s+/g, "-").toLowerCase()}`}
        {...(rest as React.ButtonHTMLAttributes<HTMLButtonElement>)}
      />
    );
  },
}));

function makeSystem(
  overrides: Partial<IntegrationSystem> = {},
): IntegrationSystem {
  return {
    id: "sys-1",
    name: "Svc",
    type: IntegrationSystemType.IMPLEMENTED,
    description: "d",
    activeEnvironmentId: "e1",
    internalServiceName: "int",
    protocol: "http",
    extendedProtocol: "",
    specification: "",
    labels: [],
    createdWhen: "2020-01-01T00:00:00Z",
    modifiedWhen: "2020-01-02T00:00:00Z",
    ...overrides,
  } as IntegrationSystem;
}

function renderTab(ui?: React.ReactElement) {
  const el = ui ?? (
    <ServiceParametersTab
      systemId="sys-1"
      activeTab="parameters"
      formatTimestamp={(v) => v}
      sidePadding={8}
      styles={{ "variables-actions": "va" }}
    />
  );
  return render(
    <UserPermissionsContext.Provider value={getAllPermissions()}>
      <ServiceParametersToolbarContext.Provider
        value={{ setToolbar: mockSetToolbar }}
      >
        {el}
      </ServiceParametersToolbarContext.Provider>
    </UserPermissionsContext.Provider>,
  );
}

describe("ServiceParametersTab", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    delete serviceCache["sys-1"];
    isVsCodeFlag = false;
    mockGetService.mockResolvedValue(makeSystem());
    mockUpdateService.mockImplementation(
      async (_id: string, s: IntegrationSystem) => s,
    );
    mockExportServices.mockResolvedValue(new File([], "x"));
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

  it("hides Type field on web (not VS Code)", async () => {
    renderTab();
    await waitFor(() => expect(mockGetService).toHaveBeenCalledWith("sys-1"));
    expect(
      screen.queryByRole("combobox", { name: /type/i }),
    ).not.toBeInTheDocument();
  });

  it("shows Type field in VS Code", async () => {
    isVsCodeFlag = true;
    renderTab();
    await waitFor(() => expect(mockGetService).toHaveBeenCalledWith("sys-1"));
    expect(screen.getByRole("combobox", { name: /type/i })).toBeInTheDocument();
  });

  it("on web save sends original system.type, not form-only type", async () => {
    mockGetService.mockResolvedValue(
      makeSystem({ type: IntegrationSystemType.IMPLEMENTED }),
    );
    renderTab();
    await waitFor(() =>
      expect(
        screen.getByRole("textbox", { name: /name/i }),
      ).toBeInTheDocument(),
    );

    fireEvent.change(screen.getByRole("textbox", { name: /name/i }), {
      target: { value: "Renamed" },
    });
    fireEvent.click(screen.getByRole("button", { name: /save/i }));

    await waitFor(() => expect(mockUpdateService).toHaveBeenCalled());
    const payload = mockUpdateService.mock.calls[0][1] as IntegrationSystem;
    expect(payload.type).toBe(IntegrationSystemType.IMPLEMENTED);
    expect(payload.name).toBe("Renamed");
  });

  it("loads from serviceCache without calling getService", async () => {
    serviceCache["sys-1"] = makeSystem({ name: "Cached" });
    renderTab();
    await waitFor(() =>
      expect(screen.getByRole("textbox", { name: /name/i })).toHaveValue(
        "Cached",
      ),
    );
    expect(mockGetService).not.toHaveBeenCalled();
  });

  it("shows load error when getService fails", async () => {
    mockGetService.mockRejectedValue(new Error("boom"));
    renderTab();
    await waitFor(() => expect(screen.getByText(/boom/)).toBeInTheDocument());
  });

  it("on web registers export toolbar and export success calls downloadFile", async () => {
    renderTab();
    await waitFor(() => {
      expect(mockSetToolbar.mock.calls.some((c) => c[0] !== null)).toBe(true);
    });
    const toolbarCall = mockSetToolbar.mock.calls.find(
      (c) => c[0] !== null,
    )?.[0] as React.ReactElement;
    expect(toolbarCall).toBeTruthy();
    render(
      <UserPermissionsContext.Provider value={getAllPermissions()}>
        {toolbarCall}
      </UserPermissionsContext.Provider>,
    );
    const exportBtn = screen.getByTestId("param-toolbar-export-service");
    fireEvent.click(exportBtn);
    await waitFor(() => expect(mockDownloadFile).toHaveBeenCalled());
  });

  it("export failure calls notification requestFailed", async () => {
    mockExportServices.mockRejectedValue(new Error("export failed"));
    renderTab();
    await waitFor(() => {
      expect(mockSetToolbar.mock.calls.some((c) => c[0] !== null)).toBe(true);
    });
    const toolbarNode = mockSetToolbar.mock.calls.find(
      (c) => c[0] !== null,
    )?.[0] as React.ReactElement | undefined;
    expect(toolbarNode).toBeTruthy();
    render(
      <UserPermissionsContext.Provider value={getAllPermissions()}>
        {toolbarNode!}
      </UserPermissionsContext.Provider>,
    );
    fireEvent.click(screen.getByTestId("param-toolbar-export-service"));
    await waitFor(() =>
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Export error",
        expect.any(Error),
      ),
    );
  });

  it("unsaved modal Yes calls blocker proceed", async () => {
    const proceed = jest.fn();
    jest.mocked(useBlocker).mockReturnValue({
      state: "blocked" as const,
      proceed,
      reset: jest.fn(),
    });
    renderTab();
    await waitFor(() => expect(mockShowModal).toHaveBeenCalled());
    const modal = mockShowModal.mock.calls[0][0]
      .component as React.ReactElement;
    render(modal);
    fireEvent.click(screen.getByTestId("unsaved-yes"));
    expect(proceed).toHaveBeenCalled();
  });

  it("unsaved modal No calls blocker reset", async () => {
    const reset = jest.fn();
    jest.mocked(useBlocker).mockReturnValue({
      state: "blocked" as const,
      proceed: jest.fn(),
      reset,
    });
    renderTab();
    await waitFor(() => expect(mockShowModal).toHaveBeenCalled());
    const modal = mockShowModal.mock.calls[0][0]
      .component as React.ReactElement;
    render(modal);
    fireEvent.click(screen.getByTestId("unsaved-no"));
    expect(reset).toHaveBeenCalled();
  });

  it("shows dash when protocol is missing", async () => {
    mockGetService.mockResolvedValue(makeSystem({ protocol: "" }));
    renderTab();
    await waitFor(() =>
      expect(screen.getAllByText("-").length).toBeGreaterThan(0),
    );
  });

  it("maps technical and user labels from loaded system", async () => {
    mockGetService.mockResolvedValue(
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
    mockGetService.mockResolvedValue(
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
    await waitFor(() => expect(mockUpdateService).toHaveBeenCalled());
    const payload = mockUpdateService.mock.calls[0][1] as IntegrationSystem;
    expect(payload.labels).toEqual(
      expect.arrayContaining([
        { name: "techL", technical: true },
        { name: "userL", technical: false },
      ]),
    );
  });

  it("shows save error when updateService fails", async () => {
    mockUpdateService.mockRejectedValue(new Error("save failed"));
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

  it("VS Code save sends form type in payload", async () => {
    isVsCodeFlag = true;
    mockGetService.mockResolvedValue(
      makeSystem({ type: IntegrationSystemType.IMPLEMENTED }),
    );
    renderTab();
    await waitFor(() =>
      expect(
        screen.getByRole("combobox", { name: /type/i }),
      ).toBeInTheDocument(),
    );
    fireEvent.mouseDown(screen.getByRole("combobox", { name: /type/i }));
    await waitFor(() => screen.getByText("External"));
    fireEvent.click(screen.getByText("External"));
    fireEvent.change(screen.getByRole("textbox", { name: /name/i }), {
      target: { value: "X" },
    });
    fireEvent.click(screen.getByRole("button", { name: /save/i }));
    await waitFor(() => expect(mockUpdateService).toHaveBeenCalled());
    const payload = mockUpdateService.mock.calls[0][1] as IntegrationSystem;
    expect(payload.type).toBe(IntegrationSystemType.EXTERNAL);
  });
});
