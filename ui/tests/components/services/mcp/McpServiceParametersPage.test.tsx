/**
 * @jest-environment jsdom
 */
import React from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import { MCPSystem } from "../../../../src/api/apiTypes";
// ---------------------------------------------------------------------------
// Component under test (imported after mocks due to jest hoisting)
// ---------------------------------------------------------------------------
import { McpServiceParametersPage } from "../../../../src/components/services/mcp/McpServiceParametersPage";
import { useNotificationService } from "../../../../src/hooks/useNotificationService";

// ---------------------------------------------------------------------------
// Browser API stubs required by Ant Design
// ---------------------------------------------------------------------------
Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: jest.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: jest.fn(),
    removeListener: jest.fn(),
    addEventListener: jest.fn(),
    removeEventListener: jest.fn(),
    dispatchEvent: jest.fn(),
  })),
});

// ---------------------------------------------------------------------------
// Mocks
// ---------------------------------------------------------------------------

const mockGetMcpSystem = jest.fn();
const mockUpdateMcpSystem = jest.fn();
const mockRequestFailed = jest.fn();
const mockNavigate = jest.fn();
let isVsCodeFlag = false;

jest.mock("../../../../src/api/api", () => ({
  api: {
    getMcpSystem: (...args: unknown[]) => mockGetMcpSystem(...args),
    updateMcpSystem: (...args: unknown[]) => mockUpdateMcpSystem(...args),
  },
}));

jest.mock("../../../../src/api/rest/vscodeExtensionApi.ts", () => ({
  get isVsCode() {
    return isVsCodeFlag;
  },
}));

jest.mock("../../../../src/hooks/useNotificationService", () => ({
  useNotificationService: jest.fn(),
}));

// McpServiceParametersPage imports useParams from "react-router"
jest.mock("react-router", () => ({
  useParams: () => ({ systemId: "mcp-1" }),
}));

// ServiceBreadcrumb imports useNavigate from "react-router-dom"
jest.mock("react-router-dom", () => ({
  useNavigate: () => mockNavigate,
}));

// ---------------------------------------------------------------------------
// Fixture factory
// ---------------------------------------------------------------------------

function makeMcpSystem(overrides: Partial<MCPSystem> = {}): MCPSystem {
  return {
    id: "mcp-1",
    name: "Test MCP",
    description: "A test description",
    identifier: "test-identifier",
    instructions: "Test instructions",
    labels: [],
    ...overrides,
  };
}

// ---------------------------------------------------------------------------
// Render helper
// ---------------------------------------------------------------------------

function renderPage() {
  return render(<McpServiceParametersPage />);
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe("McpServiceParametersPage", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    isVsCodeFlag = false;
    mockGetMcpSystem.mockResolvedValue(makeMcpSystem());
    mockUpdateMcpSystem.mockResolvedValue(makeMcpSystem());
    jest.mocked(useNotificationService).mockReturnValue({
      requestFailed: mockRequestFailed,
      info: jest.fn(),
    });
  });

  // --- Loading & initial render --------------------------------------------

  it("should call api.getMcpSystem with the systemId from URL params when mounted", async () => {
    renderPage();

    await waitFor(() => expect(mockGetMcpSystem).toHaveBeenCalledWith("mcp-1"));
  });

  it("should populate form fields with system data when loaded", async () => {
    mockGetMcpSystem.mockResolvedValue(
      makeMcpSystem({
        name: "My MCP",
        description: "My description",
        identifier: "my-id",
        instructions: "Do this",
      }),
    );
    renderPage();

    await waitFor(() =>
      expect(screen.getByRole("textbox", { name: /name/i })).toHaveValue(
        "My MCP",
      ),
    );
    expect(screen.getByRole("textbox", { name: /description/i })).toHaveValue(
      "My description",
    );
    expect(screen.getByRole("textbox", { name: /identifier/i })).toHaveValue(
      "my-id",
    );
    expect(screen.getByRole("textbox", { name: /instructions/i })).toHaveValue(
      "Do this",
    );
  });

  it("should show '...' in the name breadcrumb item before the system is loaded", () => {
    // system is null on the first render — ServiceNameBreadcrumbItem falls back to "..."
    mockGetMcpSystem.mockReturnValue(new Promise(() => {})); // never resolves
    renderPage();

    expect(screen.getByText("...")).toBeInTheDocument();
  });

  it("should show the system name in the name breadcrumb link after the system is loaded", async () => {
    mockGetMcpSystem.mockResolvedValue(makeMcpSystem({ name: "Named MCP" }));
    renderPage();

    await waitFor(() =>
      expect(
        screen.getByRole("link", { name: "Named MCP" }),
      ).toBeInTheDocument(),
    );
  });

  it("should call notificationService.requestFailed when loading fails", async () => {
    mockGetMcpSystem.mockRejectedValue(new Error("network error"));
    renderPage();

    await waitFor(() =>
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Failed to load MCP service",
        expect.any(Error),
      ),
    );
  });

  // --- Save button guard ---------------------------------------------------

  it("should disable the Save button when the form has not been modified", async () => {
    renderPage();

    await waitFor(() =>
      expect(
        screen.getByRole("textbox", { name: /name/i }),
      ).toBeInTheDocument(),
    );

    expect(screen.getByRole("button", { name: /save/i })).toBeDisabled();
  });

  it("should enable the Save button after a form field is changed", async () => {
    renderPage();

    await waitFor(() =>
      expect(
        screen.getByRole("textbox", { name: /name/i }),
      ).toBeInTheDocument(),
    );

    fireEvent.change(screen.getByRole("textbox", { name: /name/i }), {
      target: { value: "Changed Name" },
    });

    expect(screen.getByRole("button", { name: /save/i })).not.toBeDisabled();
  });

  // --- Save pipeline -------------------------------------------------------

  it("should call api.updateMcpSystem with the correct systemId and system values when the form is submitted", async () => {
    // Note: fireEvent.change triggers the form's onChange (enabling Save via hasChanges)
    // but does not overwrite the Ant Design form store. The store retains the values
    // set by form.setFieldsValue(system). This test verifies the full save pipeline:
    // correct systemId from useParams and correct field values from the loaded system.
    mockGetMcpSystem.mockResolvedValue(
      makeMcpSystem({ identifier: "unique-system-id" }),
    );
    renderPage();

    await waitFor(() =>
      expect(
        screen.getByRole("textbox", { name: /name/i }),
      ).toBeInTheDocument(),
    );

    // Trigger hasChanges to enable Save; store value is unchanged
    fireEvent.change(screen.getByRole("textbox", { name: /name/i }), {
      target: { value: "any" },
    });
    fireEvent.click(screen.getByRole("button", { name: /save/i }));

    await waitFor(() =>
      expect(mockUpdateMcpSystem).toHaveBeenCalledWith(
        "mcp-1",
        expect.objectContaining({ identifier: "unique-system-id" }),
      ),
    );
  });

  it("should clear the error alert after a successful save following a previous failure", async () => {
    mockUpdateMcpSystem
      .mockRejectedValueOnce(new Error("first failure"))
      .mockResolvedValue(makeMcpSystem());

    renderPage();

    await waitFor(() =>
      expect(
        screen.getByRole("textbox", { name: /name/i }),
      ).toBeInTheDocument(),
    );

    fireEvent.change(screen.getByRole("textbox", { name: /name/i }), {
      target: { value: "v1" },
    });

    // First save → fails → error alert appears
    fireEvent.click(screen.getByRole("button", { name: /save/i }));
    await waitFor(() => expect(screen.getByRole("alert")).toBeInTheDocument());

    // Second save → succeeds → error alert is cleared
    fireEvent.click(screen.getByRole("button", { name: /save/i }));
    await waitFor(() =>
      expect(screen.queryByRole("alert")).not.toBeInTheDocument(),
    );
  });

  it("should show an error alert with the error message when saving fails", async () => {
    mockUpdateMcpSystem.mockRejectedValue(new Error("Save failed"));
    renderPage();

    await waitFor(() =>
      expect(
        screen.getByRole("textbox", { name: /name/i }),
      ).toBeInTheDocument(),
    );

    fireEvent.change(screen.getByRole("textbox", { name: /name/i }), {
      target: { value: "X" },
    });
    fireEvent.click(screen.getByRole("button", { name: /save/i }));

    await waitFor(() => expect(screen.getByRole("alert")).toBeInTheDocument());
    expect(screen.getByText(/Save failed/)).toBeInTheDocument();
  });

  it("should call notificationService.requestFailed when saving fails", async () => {
    mockUpdateMcpSystem.mockRejectedValue(new Error("Save error"));
    renderPage();

    await waitFor(() =>
      expect(
        screen.getByRole("textbox", { name: /name/i }),
      ).toBeInTheDocument(),
    );

    fireEvent.change(screen.getByRole("textbox", { name: /name/i }), {
      target: { value: "X" },
    });
    fireEvent.click(screen.getByRole("button", { name: /save/i }));

    await waitFor(() =>
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Failed to update MCP service",
        expect.any(Error),
      ),
    );
  });

  // --- Form validation -----------------------------------------------------

  it("should show a validation error when Name is empty on submit", async () => {
    // Pre-load a system with name: "" so the Ant Design form store initialises
    // with an empty name field. A fireEvent.change on a different field (Description)
    // flips hasChanges without touching the store, enabling the Save button.
    // Submitting then triggers required-field validation on the empty name.
    mockGetMcpSystem.mockResolvedValue(makeMcpSystem({ name: "" }));
    renderPage();

    await waitFor(() =>
      expect(
        screen.getByRole("textbox", { name: /identifier/i }),
      ).toBeInTheDocument(),
    );

    // Enable Save by triggering onChange via the Description field
    fireEvent.change(screen.getByRole("textbox", { name: /description/i }), {
      target: { value: "trigger" },
    });
    fireEvent.click(screen.getByRole("button", { name: /save/i }));

    await waitFor(() =>
      expect(screen.getByText("Enter service name")).toBeInTheDocument(),
    );
    expect(mockUpdateMcpSystem).not.toHaveBeenCalled();
  });

  it("should show a validation error when Identifier is empty on submit", async () => {
    // Pre-load a system with identifier: "" so the form store initialises with
    // an empty identifier. The Name field is non-empty so only identifier fails.
    mockGetMcpSystem.mockResolvedValue(makeMcpSystem({ identifier: "" }));
    renderPage();

    await waitFor(() =>
      expect(
        screen.getByRole("textbox", { name: /name/i }),
      ).toBeInTheDocument(),
    );

    // Enable Save by triggering onChange via the Name field
    fireEvent.change(screen.getByRole("textbox", { name: /name/i }), {
      target: { value: "trigger" },
    });
    fireEvent.click(screen.getByRole("button", { name: /save/i }));

    await waitFor(() =>
      expect(screen.getByText("Enter service identifier")).toBeInTheDocument(),
    );
    expect(mockUpdateMcpSystem).not.toHaveBeenCalled();
  });

  // --- Labels serialisation ------------------------------------------------

  it("should display label names from the loaded system in the labels select field", async () => {
    mockGetMcpSystem.mockResolvedValue(
      makeMcpSystem({
        labels: [
          { name: "alpha", technical: false },
          { name: "beta", technical: true },
        ],
      }),
    );
    renderPage();

    await waitFor(() => expect(screen.getByText("alpha")).toBeInTheDocument());
    expect(screen.getByText("beta")).toBeInTheDocument();
  });

  it("should send labels as EntityLabel objects with technical: false in the update payload", async () => {
    mockGetMcpSystem.mockResolvedValue(
      makeMcpSystem({
        labels: [{ name: "my-label", technical: false }],
      }),
    );
    renderPage();

    await waitFor(() =>
      expect(
        screen.getByRole("textbox", { name: /name/i }),
      ).toBeInTheDocument(),
    );

    // Modify a field to enable Save, keeping the labels intact
    fireEvent.change(screen.getByRole("textbox", { name: /name/i }), {
      target: { value: "Changed" },
    });
    fireEvent.click(screen.getByRole("button", { name: /save/i }));

    await waitFor(() => expect(mockUpdateMcpSystem).toHaveBeenCalled());
    const payload = mockUpdateMcpSystem.mock.calls[0][1] as MCPSystem;
    expect(payload.labels).toEqual(
      expect.arrayContaining([{ name: "my-label", technical: false }]),
    );
  });

  // --- Timestamps (isVsCode flag) ------------------------------------------

  it("should show Created and Modified timestamps when not in VS Code", () => {
    isVsCodeFlag = false;
    renderPage();

    // The Descriptions block is rendered unconditionally when !isVsCode
    expect(screen.getByText("Created")).toBeInTheDocument();
    expect(screen.getByText("Modified")).toBeInTheDocument();
  });

  it("should hide the timestamps block when running in VS Code", () => {
    isVsCodeFlag = true;
    renderPage();

    expect(screen.queryByText("Created")).not.toBeInTheDocument();
    expect(screen.queryByText("Modified")).not.toBeInTheDocument();
  });
});
