/**
 * @jest-environment jsdom
 */
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

const mockGetService =
  jest.fn<(...args: unknown[]) => Promise<IntegrationSystem>>();
const mockUpdateService =
  jest.fn<(...args: unknown[]) => Promise<IntegrationSystem>>();
const mockSetToolbar = jest.fn<(...args: unknown[]) => void>();
let isVsCodeFlag = false;

jest.mock("../../../../src/api/api", () => ({
  api: {
    getService: (...args: unknown[]) => mockGetService(...args),
    updateService: (...args: unknown[]) => mockUpdateService(...args),
    exportServices: jest.fn(),
  },
}));

jest.mock("../../../../src/api/rest/vscodeExtensionApi.ts", () => ({
  get isVsCode() {
    return isVsCodeFlag;
  },
}));

jest.mock("../../../../src/Modals.tsx", () => ({
  useModalsContext: () => ({ showModal: jest.fn() }),
}));

jest.mock("../../../../src/hooks/useNotificationService.tsx", () => ({
  useNotificationService: jest.fn(),
}));

jest.mock("react-router-dom", () => ({
  ...jest.requireActual<typeof import("react-router-dom")>("react-router-dom"),
  useBlocker: jest.fn(),
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
    ...overrides,
  };
}

function renderTab() {
  return render(
    <UserPermissionsContext.Provider value={getAllPermissions()}>
      <ServiceParametersToolbarContext.Provider
        value={{ setToolbar: mockSetToolbar }}
      >
        <ServiceParametersTab
          systemId="sys-1"
          activeTab="parameters"
          formatTimestamp={(v) => String(v ?? "")}
          sidePadding={8}
          styles={{ "variables-actions": "va" }}
        />
      </ServiceParametersToolbarContext.Provider>
    </UserPermissionsContext.Provider>,
  );
}

async function saveWithRenamedName(): Promise<Record<string, unknown>> {
  await waitFor(() =>
    expect(screen.getByRole("textbox", { name: /name/i })).toBeInTheDocument(),
  );
  fireEvent.change(screen.getByRole("textbox", { name: /name/i }), {
    target: { value: "Renamed" },
  });
  fireEvent.click(screen.getByRole("button", { name: /save/i }));
  await waitFor(() => expect(mockUpdateService).toHaveBeenCalled());
  return mockUpdateService.mock.calls[0][1] as Record<string, unknown>;
}

describe("ServiceParametersTab service type", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    delete serviceCache["sys-1"];
    isVsCodeFlag = false;
    mockGetService.mockResolvedValue(makeSystem());
    mockUpdateService.mockImplementation(
      async (..._args: unknown[]) => _args[1] as IntegrationSystem,
    );
    jest.mocked(useNotificationService).mockReturnValue({
      requestFailed: jest.fn(),
      info: jest.fn(),
      warning: jest.fn(),
      errorWithDetails: jest.fn(),
    });
    jest.mocked(useBlocker).mockReturnValue({
      state: "unblocked",
      proceed: jest.fn(),
      reset: jest.fn(),
      location: undefined,
    } as unknown as ReturnType<typeof useBlocker>);
  });

  it.each([
    ["web", false],
    ["VS Code", true],
  ])(
    "should render the type read-only when running on %s",
    async (_name, isVsCode) => {
      isVsCodeFlag = isVsCode;
      renderTab();

      await waitFor(() =>
        expect(screen.getByText("Implemented")).toBeVisible(),
      );
      expect(
        screen.queryByRole("combobox", { name: /type/i }),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByRole("textbox", { name: /type/i }),
      ).not.toBeInTheDocument();
    },
  );

  it.each([
    ["web", false],
    ["VS Code", true],
  ])("should submit no type field when saving on %s", async (_n, isVsCode) => {
    isVsCodeFlag = isVsCode;
    renderTab();

    const payload = await saveWithRenamedName();

    expect(payload).not.toHaveProperty("type");
    expect(payload.name).toBe("Renamed");
  });

  it("should render a dash when the type is null", async () => {
    // A backend row written before the type became mandatory can still hold no type.
    mockGetService.mockResolvedValue(
      makeSystem({ type: null as unknown as IntegrationSystemType }),
    );
    renderTab();

    await waitFor(() =>
      expect(
        screen.getByRole("textbox", { name: /name/i }),
      ).toBeInTheDocument(),
    );
    expect(screen.getByText("Type")).toBeVisible();
    expect(screen.getAllByText("-").length).toBeGreaterThan(0);
  });
});
