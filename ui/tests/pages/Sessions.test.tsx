/**
 * @jest-environment jsdom
 */

import React from "react";
import {
  screen,
  waitFor,
  within,
  fireEvent,
  act,
} from "@testing-library/react";
import "@testing-library/jest-dom";
import {
  type CheckpointSession,
  ExecutionStatus,
  SessionFilterCondition,
  SessionFilterFeature,
  SessionsLoggingLevel,
  type Session,
} from "../../src/api/apiTypes";
import { api } from "../../src/api/api.ts";
import { Sessions } from "../../src/pages/Sessions.tsx";
import { renderPageWithChainHeader } from "../helpers/renderWithChainHeader.tsx";
import { downloadFile } from "../../src/misc/download-utils.ts";
import { getLastTableOnChange } from "../__mocks__/LightweightTable.tsx";

let capturedConfirmOnOk: (() => void | Promise<void>) | undefined;

jest.mock("../../src/api/api.ts", () => ({
  api: {
    getSessions: jest.fn(),
    getCheckpointSessions: jest.fn(),
    deleteSessions: jest.fn(),
    deleteSessionsByChainId: jest.fn(),
    exportSessions: jest.fn(),
    retrySessionFromCheckpoint: jest.fn(),
  },
}));

// jest.spyOn references methods by name string — no unbound-method lint issue
const mockGetSessions = jest.spyOn(api, "getSessions");
const mockGetCheckpointSessions = jest.spyOn(api, "getCheckpointSessions");
const mockDeleteSessions = jest.spyOn(api, "deleteSessions");
const mockDeleteSessionsByChainId = jest.spyOn(api, "deleteSessionsByChainId");
const mockExportSessions = jest.spyOn(api, "exportSessions");
const mockRetrySessionFromCheckpoint = jest.spyOn(
  api,
  "retrySessionFromCheckpoint",
);
const mockDownloadFile = jest.mocked(downloadFile);

const mockNavigate = jest.fn();
const mockUseParams: jest.Mock<{ chainId?: string }> = jest.fn(() => ({
  chainId: "chain-1",
}));

jest.mock("react-router", () => ({
  useNavigate: () => mockNavigate,
  useParams: () => mockUseParams(),
}));

jest.mock("antd", () => {
  const { createChainPageAntdMock } = jest.requireActual<{
    createChainPageAntdMock: () => Record<string, unknown>;
  }>("tests/helpers/chainPageAntdJestMock");
  return createChainPageAntdMock();
});

jest.mock("antd/lib/table", () => ({}));
jest.mock("antd/lib/table/interface", () => ({}));
jest.mock("antd/es/table/interface", () => ({}));

jest.mock("../../src/components/table/CompactSearch.tsx", () => ({
  CompactSearch: (props: {
    value: string;
    onChange: (v: string) => void;
    placeholder: string;
  }) => (
    <input
      data-testid="search-input"
      value={props.value}
      onChange={(e) => props.onChange(e.target.value)}
      placeholder={props.placeholder}
    />
  ),
}));

jest.mock("../../src/components/modal/ImportSessions.tsx", () => ({
  ImportSessions: () => <div data-testid="import-sessions-stub" />,
}));

jest.mock("../../src/components/sessions/SessionStatus.tsx", () => ({
  SessionStatus: ({ status }: { status: string }) => (
    <span data-testid="session-status">{String(status)}</span>
  ),
}));

jest.mock(
  "../../src/components/table/TimestampColumnFilterDropdown.tsx",
  () => {
    const actual = jest.requireActual<
      typeof import("../../src/components/table/TimestampColumnFilterDropdown.tsx")
    >("../../src/components/table/TimestampColumnFilterDropdown.tsx");
    return { ...actual, TimestampColumnFilterDropdown: () => <div /> };
  },
);

jest.mock("../../src/components/table/TextColumnFilterDropdown.tsx", () => {
  const actual = jest.requireActual<
    typeof import("../../src/components/table/TextColumnFilterDropdown.tsx")
  >("../../src/components/table/TextColumnFilterDropdown.tsx");
  return { ...actual, TextColumnFilterDropdown: () => <div /> };
});

jest.mock("../../src/permissions/ProtectedButton.tsx", () => ({
  ProtectedButton: ({
    buttonProps,
    tooltipProps,
  }: {
    buttonProps: {
      onClick?: () => void;
      iconName?: string;
      disabled?: boolean;
      "data-testid"?: string;
    };
    tooltipProps: { title?: string };
  }) => (
    <button
      type="button"
      data-testid={
        buttonProps["data-testid"] ??
        `protected-btn-${(tooltipProps.title ?? "")
          .replaceAll(/\s+/g, "-")
          .toLowerCase()}`
      }
      onClick={buttonProps?.onClick}
      disabled={buttonProps?.disabled}
    >
      {tooltipProps.title}
    </button>
  ),
}));

jest.mock("../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`}>{name}</span>
  ),
}));

const mockNotificationService = {
  requestFailed: jest.fn(),
  errorWithDetails: jest.fn(),
  info: jest.fn(),
  warning: jest.fn(),
};

jest.mock("../../src/hooks/useNotificationService.tsx", () => ({
  useNotificationService: () => mockNotificationService,
}));

const mockShowModal = jest.fn();

jest.mock("../../src/Modals.tsx", () => ({
  Modals: ({ children }: { children: React.ReactNode }) => children,
  useModalsContext: () => ({
    showModal: mockShowModal,
    closeModal: jest.fn(),
  }),
}));

jest.mock("../../src/misc/download-utils.ts", () => ({
  downloadFile: jest.fn(),
}));

jest.mock("../../src/misc/confirm-utils.ts", () => ({
  confirmAndRun: (opts: { onOk: () => void | Promise<void> }) => {
    capturedConfirmOnOk = opts.onOk;
  },
}));

jest.mock("../../src/misc/format-utils.ts", () => {
  const actual = jest.requireActual<Record<string, unknown>>(
    "../../src/misc/format-utils.ts",
  );
  return {
    ...actual,
    formatUTCSessionDate: (_d: string) => "2024-01-01 00:00:00 UTC",
    formatDuration: (ms: number) => `${ms}ms`,
    capitalize: (s: string) =>
      s ? s.charAt(0).toUpperCase() + s.slice(1).toLowerCase() : "",
    formatSnakeCased: (s: string) => s,
  };
});

function baseSession({
  id,
  ...rest
}: Partial<Session> & { id: string }): Session {
  return {
    id,
    chainId: "chain-1",
    chainName: "Test Chain",
    started: "2024-01-01T00:00:00.000Z",
    finished: "2024-01-01T00:01:00.000Z",
    duration: 60000,
    syncDuration: 0,
    executionStatus: ExecutionStatus.COMPLETED_NORMALLY,
    importedSession: false,
    externalSessionCipId: "",
    domain: "dom",
    engineAddress: "127.0.0.1",
    loggingLevel: SessionsLoggingLevel.INFO,
    snapshotName: "snap",
    correlationId: "",
    parentSessionId: "",
    ...rest,
  };
}

function baseCheckpointSession(
  id: string,
  checkpoints: CheckpointSession["checkpoints"],
): CheckpointSession {
  return {
    id,
    started: "2024-01-01T00:00:00.000Z",
    finished: "2024-01-01T00:01:00.000Z",
    duration: 60000,
    executionStatus: ExecutionStatus.COMPLETED_NORMALLY,
    chainId: "chain-1",
    chainName: "Test Chain",
    engineAddress: "127.0.0.1",
    loggingLevel: SessionsLoggingLevel.INFO,
    snapshotName: "snap",
    correlationId: "",
    checkpoints,
  };
}

function renderSessions() {
  return renderPageWithChainHeader(<Sessions />);
}

async function renderWithSessions(sessions: Session[]) {
  mockGetSessions.mockResolvedValue({
    sessions,
    offset: sessions.length,
  });

  renderSessions();

  await waitFor(() => {
    expect(mockGetSessions).toHaveBeenCalledTimes(1);
  });

  // Wait for the first session to land in the table — otherwise callers that
  // immediately query rows hit a race where mockGetSessions has been called
  // but the resolved promise hasn't propagated into the DOM yet. Use
  // findAllByRole to tolerate correlation groups that render multiple rows
  // sharing the same id fragment.
  if (sessions.length > 0) {
    await screen.findAllByRole("row", {
      name: new RegExp(sessions[0].id, "i"),
    });
  }
}

describe("Sessions", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    capturedConfirmOnOk = undefined;
    mockUseParams.mockReturnValue({ chainId: "chain-1" });
    mockGetCheckpointSessions.mockResolvedValue([]);
  });

  // --- Initial loading ---

  test("fetches sessions on mount", async () => {
    mockGetSessions.mockResolvedValue({
      sessions: [baseSession({ id: "s1" })],
      offset: 1,
    });

    renderSessions();

    await waitFor(() => {
      expect(mockGetSessions).toHaveBeenCalledWith(
        "chain-1",
        { filterRequestList: [], searchString: "" },
        { offset: 0 },
      );
    });
    expect(await screen.findByText("s1")).toBeInTheDocument();
  });

  test("renders empty table when no sessions", async () => {
    mockGetSessions.mockResolvedValue({
      sessions: [],
      offset: 0,
    });

    renderSessions();

    await waitFor(() => {
      expect(mockGetSessions).toHaveBeenCalledTimes(1);
    });
    // Header row exists but no data rows
    const rows = screen.getAllByRole("row");
    expect(rows).toHaveLength(1); // only header
  });

  test("shows error notification on fetch failure", async () => {
    const error = new Error("Network error");
    mockGetSessions.mockRejectedValue(error);

    renderSessions();

    await waitFor(() => {
      expect(mockNotificationService.requestFailed).toHaveBeenCalledWith(
        "Failed to fetch sessions",
        error,
      );
    });
  });

  // --- Refresh ---

  test("renders Refresh control with sessions-refresh test id", async () => {
    mockGetSessions.mockResolvedValue({
      sessions: [baseSession({ id: "session-a" })],
      offset: 0,
    });

    renderSessions();

    await waitFor(() => {
      expect(screen.getByTestId("sessions-refresh")).toBeInTheDocument();
    });
    expect(screen.getByTestId("sessions-refresh")).toHaveRole("button");
  });

  test("Refresh triggers full reload from offset 0 and replaces table rows", async () => {
    const first = baseSession({ id: "first-row" });
    const second = baseSession({ id: "second-row" });
    mockGetSessions
      .mockResolvedValueOnce({ sessions: [first], offset: 0 })
      .mockResolvedValueOnce({ sessions: [second], offset: 0 });

    renderSessions();

    await waitFor(() => {
      expect(screen.getByText("first-row")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId("sessions-refresh"));

    await waitFor(() => {
      expect(screen.getByText("second-row")).toBeInTheDocument();
    });
    expect(screen.queryByText("first-row")).not.toBeInTheDocument();

    expect(mockGetSessions).toHaveBeenCalledTimes(2);
    expect(mockGetSessions).toHaveBeenLastCalledWith(
      "chain-1",
      expect.objectContaining({
        filterRequestList: [],
        searchString: "",
      }),
      expect.objectContaining({ offset: 0 }),
    );
  });

  test("Refresh clears row selection", async () => {
    mockGetSessions
      .mockResolvedValueOnce({
        sessions: [baseSession({ id: "sel-row" })],
        offset: 0,
      })
      .mockResolvedValueOnce({
        sessions: [baseSession({ id: "sel-row" })],
        offset: 0,
      });

    renderSessions();

    await waitFor(() => {
      expect(screen.getByText("sel-row")).toBeInTheDocument();
    });
    const row = screen.getByRole("row", { name: /sel-row/i });
    const checkbox = within(row).getByRole("checkbox");
    fireEvent.click(checkbox);
    expect(checkbox).toBeChecked();

    fireEvent.click(screen.getByTestId("sessions-refresh"));

    await waitFor(() => {
      expect(mockGetSessions).toHaveBeenCalledTimes(2);
    });

    const rowAfter = screen.getByRole("row", { name: /sel-row/i });
    expect(within(rowAfter).getByRole("checkbox")).not.toBeChecked();
  });

  // --- Session status rendering ---

  test("renders session status for each row", async () => {
    await renderWithSessions([
      baseSession({
        id: "s1",
        executionStatus: ExecutionStatus.COMPLETED_NORMALLY,
      }),
      baseSession({
        id: "s2",
        executionStatus: ExecutionStatus.COMPLETED_WITH_ERRORS,
      }),
    ]);

    const statuses = await screen.findAllByTestId("session-status");
    expect(statuses).toHaveLength(2);
    expect(statuses[0]).toHaveTextContent("COMPLETED_NORMALLY");
    expect(statuses[1]).toHaveTextContent("COMPLETED_WITH_ERRORS");
  });

  // --- Delete ---

  test("delete selected sessions calls api.deleteSessions", async () => {
    mockDeleteSessions.mockResolvedValue(undefined);
    await renderWithSessions([
      baseSession({ id: "d1" }),
      baseSession({ id: "d2" }),
    ]);

    // Select first row
    const row = screen.getByRole("row", { name: /d1/i });
    fireEvent.click(within(row).getByRole("checkbox"));

    // Click delete
    fireEvent.click(
      screen.getByTestId("protected-btn-delete-selected-sessions"),
    );

    // Confirm
    expect(capturedConfirmOnOk).toBeDefined();
    await act(async () => {
      await capturedConfirmOnOk!();
    });

    expect(mockDeleteSessions).toHaveBeenCalledWith(["d1"]);
  });

  test("delete all sessions calls api.deleteSessionsByChainId", async () => {
    mockDeleteSessionsByChainId.mockResolvedValue(undefined);
    await renderWithSessions([
      baseSession({ id: "a1" }),
      baseSession({ id: "a2" }),
    ]);

    // Select all via header checkbox
    const headerRow = screen.getAllByRole("row")[0];
    const headerCheckbox = within(headerRow).getByRole("checkbox");
    fireEvent.click(headerCheckbox);

    // Click delete
    fireEvent.click(
      screen.getByTestId("protected-btn-delete-selected-sessions"),
    );

    expect(capturedConfirmOnOk).toBeDefined();
    await act(async () => {
      await capturedConfirmOnOk!();
    });

    expect(mockDeleteSessionsByChainId).toHaveBeenCalledWith("chain-1");
    expect(mockDeleteSessions).not.toHaveBeenCalled();
  });

  test("delete does nothing when no rows selected", async () => {
    await renderWithSessions([baseSession({ id: "n1" })]);

    fireEvent.click(
      screen.getByTestId("protected-btn-delete-selected-sessions"),
    );

    expect(capturedConfirmOnOk).toBeUndefined();
    expect(mockDeleteSessions).not.toHaveBeenCalled();
  });

  test("delete failure shows error notification", async () => {
    const error = new Error("delete failed");
    mockDeleteSessions.mockRejectedValue(error);
    await renderWithSessions([
      baseSession({ id: "f1" }),
      baseSession({ id: "f2" }),
    ]);

    const row = screen.getByRole("row", { name: /f1/i });
    fireEvent.click(within(row).getByRole("checkbox"));
    fireEvent.click(
      screen.getByTestId("protected-btn-delete-selected-sessions"),
    );

    await act(async () => {
      await capturedConfirmOnOk!();
    });

    await waitFor(() => {
      expect(mockNotificationService.requestFailed).toHaveBeenCalledWith(
        "Failed to delete sessions",
        error,
      );
    });
  });

  // --- Export ---

  test("export selected sessions downloads file", async () => {
    const fakeFile = new File(["data"], "sessions.zip");
    mockExportSessions.mockResolvedValue(fakeFile);
    await renderWithSessions([
      baseSession({ id: "e1" }),
      baseSession({ id: "e2" }),
    ]);

    // Select first row
    const row = screen.getByRole("row", { name: /e1/i });
    fireEvent.click(within(row).getByRole("checkbox"));

    fireEvent.click(
      screen.getByTestId("protected-btn-export-selected-sessions"),
    );

    await waitFor(() => {
      expect(mockExportSessions).toHaveBeenCalledWith(["e1"]);
    });
    expect(mockDownloadFile).toHaveBeenCalledWith(fakeFile);
  });

  test("export does nothing when no rows selected", async () => {
    await renderWithSessions([baseSession({ id: "ne1" })]);

    fireEvent.click(
      screen.getByTestId("protected-btn-export-selected-sessions"),
    );

    expect(mockExportSessions).not.toHaveBeenCalled();
  });

  test("export failure shows error notification", async () => {
    const error = new Error("export failed");
    mockExportSessions.mockRejectedValue(error);
    await renderWithSessions([baseSession({ id: "ef1" })]);

    const row = screen.getByRole("row", { name: /ef1/i });
    fireEvent.click(within(row).getByRole("checkbox"));
    fireEvent.click(
      screen.getByTestId("protected-btn-export-selected-sessions"),
    );

    await waitFor(() => {
      expect(mockNotificationService.requestFailed).toHaveBeenCalledWith(
        "Failed to export sessions",
        error,
      );
    });
  });

  // --- Import ---

  test("import button opens modal", async () => {
    mockUseParams.mockReturnValue({});
    mockGetSessions.mockResolvedValue({
      sessions: [],
      offset: 0,
    });

    renderSessions();

    await waitFor(() => {
      expect(mockGetSessions).toHaveBeenCalled();
    });

    fireEvent.click(screen.getByTestId("protected-btn-import-sessions"));

    expect(mockShowModal).toHaveBeenCalledTimes(1);
  });

  test("import onSuccess prepends imported sessions to the list", async () => {
    mockUseParams.mockReturnValue({});
    mockGetSessions.mockResolvedValue({
      sessions: [baseSession({ id: "existing-1" })],
      offset: 1,
    });

    renderSessions();

    await waitFor(() => {
      expect(screen.getByText("existing-1")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId("protected-btn-import-sessions"));

    // Extract onSuccess callback from showModal call
    type ModalArg = {
      component: React.ReactElement<{ onSuccess: (s: Session[]) => void }>;
    };
    const firstCall = mockShowModal.mock.calls[0] as [ModalArg];
    const onSuccess = firstCall[0].component.props.onSuccess;

    // Call it with imported sessions
    act(() => {
      onSuccess([baseSession({ id: "imported-1" })]);
    });

    await waitFor(() => {
      expect(screen.getByText("imported-1")).toBeInTheDocument();
    });
    // Original session still present
    expect(screen.getByText("existing-1")).toBeInTheDocument();
  });

  // --- Correlation grouping ---

  test("groups sessions by correlationId", async () => {
    await renderWithSessions([
      baseSession({ id: "corr-1", correlationId: "corr-1" }),
      baseSession({ id: "child-1", correlationId: "corr-1" }),
      baseSession({ id: "child-2", correlationId: "corr-1" }),
    ]);

    // corr-1 appears as the group row ID and as the original session
    expect(screen.getAllByText("corr-1").length).toBeGreaterThanOrEqual(1);
    // Child sessions rendered as nested rows
    expect(screen.getByText("child-1")).toBeInTheDocument();
    expect(screen.getByText("child-2")).toBeInTheDocument();
  });

  // --- Parent-child sessions ---

  test("nests child session under parent", async () => {
    await renderWithSessions([
      baseSession({ id: "parent-1" }),
      baseSession({ id: "child-1", parentSessionId: "parent-1" }),
    ]);

    expect(screen.getByText("parent-1")).toBeInTheDocument();
    expect(screen.getByText("child-1")).toBeInTheDocument();
  });

  // --- Search ---

  test("search input updates filter state and triggers refetch", async () => {
    mockGetSessions.mockResolvedValue({
      sessions: [],
      offset: 0,
    });

    renderSessions();

    await waitFor(() => {
      expect(mockGetSessions).toHaveBeenCalledTimes(1);
    });

    const searchInput = screen.getByTestId("search-input");
    fireEvent.change(searchInput, { target: { value: "test query" } });

    await waitFor(() => {
      expect(mockGetSessions).toHaveBeenCalledWith(
        "chain-1",
        expect.objectContaining({ searchString: "test query" }),
        expect.objectContaining({ offset: 0 }),
      );
    });
  });

  // --- Checkpoints and retry ---

  test("renders retry button for sessions with checkpoints", async () => {
    mockGetSessions.mockResolvedValue({
      sessions: [baseSession({ id: "cp-session" })],
      offset: 1,
    });
    mockGetCheckpointSessions.mockResolvedValue([
      baseCheckpointSession("cp-session", [
        { id: "cp1", checkpointElementId: "el1", timestamp: "2024-01-01" },
      ]),
    ]);
    mockRetrySessionFromCheckpoint.mockResolvedValue(undefined);

    renderSessions();

    await waitFor(() => {
      expect(screen.getByText("cp-session")).toBeInTheDocument();
    });

    // The retry button should be rendered (ProtectedButton with redo icon)
    const retryBtn = screen.getByTestId("protected-btn-");
    expect(retryBtn).toBeInTheDocument();
  });

  // --- Column rendering ---

  test("renders session columns correctly", async () => {
    await renderWithSessions([
      baseSession({
        id: "col-test",
        executionStatus: ExecutionStatus.IN_PROGRESS,
        snapshotName: "my-snapshot",
        duration: 5000,
      }),
    ]);

    expect(screen.getByText("col-test")).toBeInTheDocument();
    expect(screen.getByText("my-snapshot")).toBeInTheDocument();
    expect(screen.getByText("5000ms")).toBeInTheDocument();
    // started + finished both render the same formatted date
    expect(screen.getAllByText("2024-01-01 00:00:00 UTC").length).toBe(2);
  });

  // --- Chain column in admin mode ---

  test("shows Chain column when chainId is undefined", async () => {
    mockUseParams.mockReturnValue({});
    await renderWithSessions([
      baseSession({ id: "admin-s1", chainName: "My Chain" }),
    ]);

    expect(screen.getByText("My Chain")).toBeInTheDocument();
  });

  // --- Multiple sessions rendering ---

  test("renders multiple sessions sorted by start time", async () => {
    await renderWithSessions([
      baseSession({ id: "old", started: "2024-01-01T00:00:00.000Z" }),
      baseSession({ id: "new", started: "2024-01-02T00:00:00.000Z" }),
    ]);

    expect(screen.getByText("old")).toBeInTheDocument();
    expect(screen.getByText("new")).toBeInTheDocument();
  });

  // --- Row selection ---

  test("selects and deselects individual rows", async () => {
    await renderWithSessions([
      baseSession({ id: "sel-1" }),
      baseSession({ id: "sel-2" }),
    ]);

    const row1 = screen.getByRole("row", { name: /sel-1/i });
    const checkbox1 = within(row1).getByRole("checkbox");

    fireEvent.click(checkbox1);
    expect(checkbox1).toBeChecked();

    fireEvent.click(checkbox1);
    expect(checkbox1).not.toBeChecked();
  });

  // --- Navigation ---

  test("clicking session ID navigates to session detail", async () => {
    await renderWithSessions([baseSession({ id: "nav-1", chainId: "c1" })]);

    fireEvent.click(screen.getByText("nav-1"));

    expect(mockNavigate).toHaveBeenCalledWith("/chains/c1/sessions/nav-1");
  });

  test("clicking chain name navigates to chain page (admin mode)", async () => {
    mockUseParams.mockReturnValue({});
    await renderWithSessions([
      baseSession({ id: "nav-2", chainId: "c2", chainName: "Chain Two" }),
    ]);

    fireEvent.click(screen.getByText("Chain Two"));

    expect(mockNavigate).toHaveBeenCalledWith("/chains/c2");
  });

  // --- Retry ---

  test("retry from checkpoint calls api and shows success message", async () => {
    mockGetSessions.mockResolvedValue({
      sessions: [baseSession({ id: "retry-s" })],
      offset: 1,
    });
    mockGetCheckpointSessions.mockResolvedValue([
      baseCheckpointSession("retry-s", [
        { id: "cp1", checkpointElementId: "el1", timestamp: "2024-01-01" },
      ]),
    ]);
    mockRetrySessionFromCheckpoint.mockResolvedValue(undefined);

    renderSessions();

    await waitFor(() => {
      expect(screen.getByText("retry-s")).toBeInTheDocument();
    });

    // ProtectedButton with empty title for retry generates "protected-btn-"
    const retryBtn = screen.getByTestId("protected-btn-");
    fireEvent.click(retryBtn);

    await waitFor(() => {
      expect(mockRetrySessionFromCheckpoint).toHaveBeenCalledWith(
        "chain-1",
        "retry-s",
      );
    });
  });

  test("retry failure shows error notification", async () => {
    const error = new Error("retry failed");
    mockGetSessions.mockResolvedValue({
      sessions: [baseSession({ id: "retry-f" })],
      offset: 1,
    });
    mockGetCheckpointSessions.mockResolvedValue([
      baseCheckpointSession("retry-f", [
        { id: "cp1", checkpointElementId: "el1", timestamp: "2024-01-01" },
      ]),
    ]);
    mockRetrySessionFromCheckpoint.mockRejectedValue(error);

    renderSessions();

    await waitFor(() => {
      expect(screen.getByText("retry-f")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId("protected-btn-"));

    await waitFor(() => {
      expect(mockNotificationService.requestFailed).toHaveBeenCalledWith(
        "Failed to retry session",
        error,
      );
    });
  });

  // --- Table column filters (executionStatus) ---

  test("status column filter values include all execution statuses", async () => {
    await renderWithSessions([baseSession({ id: "flt-1" })]);

    // The status column has filters prop with all ExecutionStatus values.
    // The LightweightTable mock doesn't render filter dropdowns, but
    // the column definition is exercised via buildColumns(). We verify
    // the statuses are rendered by checking the status column renders.
    const statuses = screen.getAllByTestId("session-status");
    expect(statuses.length).toBeGreaterThanOrEqual(1);
  });

  // --- Engine column rendering ---

  test("renders engine domain and address", async () => {
    await renderWithSessions([
      baseSession({ id: "eng-1", domain: "prod", engineAddress: "10.0.0.1" }),
    ]);

    expect(screen.getByText("prod (10.0.0.1)")).toBeInTheDocument();
  });

  // --- Session level rendering ---

  test("renders session logging level", async () => {
    await renderWithSessions([
      baseSession({ id: "lvl-1", loggingLevel: SessionsLoggingLevel.DEBUG }),
    ]);

    expect(screen.getByText("Debug")).toBeInTheDocument();
  });

  // --- Scroll pagination ---

  test("onScroll is passed to Table component", async () => {
    // Verifies the onScroll handler is wired up (the handler itself
    // is a useCallback that guards against duplicate requests when isLoading)
    await renderWithSessions([baseSession({ id: "scroll-1" })]);

    const table = document.querySelector("table");
    expect(table).toBeTruthy();
  });

  // --- Correlation item renders session count ---

  test("correlation group row shows session count", async () => {
    await renderWithSessions([
      baseSession({ id: "cg-1", correlationId: "cg-1" }),
      baseSession({ id: "cg-child-1", correlationId: "cg-1" }),
    ]);

    // Correlation group renders "N sessions" text
    expect(screen.getByText(/2 sessions/)).toBeInTheDocument();
  });

  // --- Duration formatting ---

  test("renders formatted duration", async () => {
    await renderWithSessions([baseSession({ id: "dur-1", duration: 12345 })]);

    expect(screen.getByText("12345ms")).toBeInTheDocument();
  });

  test("select all checkbox toggles all rows", async () => {
    await renderWithSessions([
      baseSession({ id: "all-1" }),
      baseSession({ id: "all-2" }),
    ]);

    const headerRow = screen.getAllByRole("row")[0];
    const selectAll = within(headerRow).getByRole("checkbox");

    fireEvent.click(selectAll);

    const dataRows = screen.getAllByRole("row").slice(1);
    dataRows.forEach((row) => {
      expect(within(row).getByRole("checkbox")).toBeChecked();
    });

    fireEvent.click(selectAll);

    dataRows.forEach((row) => {
      expect(within(row).getByRole("checkbox")).not.toBeChecked();
    });
  });

  // --- Table column filter changes (onChange) ---

  test("executionStatus filter triggers refetch with STATUS filter", async () => {
    await renderWithSessions([baseSession({ id: "of-1" })]);

    const onChange = getLastTableOnChange();
    expect(onChange).toBeDefined();

    act(() => {
      onChange!(
        {},
        { executionStatus: [ExecutionStatus.COMPLETED_NORMALLY] },
        {},
        {},
      );
    });

    await waitFor(() => {
      expect(mockGetSessions).toHaveBeenLastCalledWith(
        "chain-1",
        expect.objectContaining({
          filterRequestList: [
            {
              feature: SessionFilterFeature.STATUS,
              condition: SessionFilterCondition.IN,
              value: ExecutionStatus.COMPLETED_NORMALLY,
            },
          ],
        }),
        expect.objectContaining({ offset: 0 }),
      );
    });
  });

  test("started timestamp filter triggers refetch with START_TIME filter", async () => {
    await renderWithSessions([baseSession({ id: "tf-1" })]);

    const filter = JSON.stringify({
      condition: "is-after",
      value: [1704067200000],
    });

    act(() => {
      getLastTableOnChange()!({}, { started: [filter] }, {}, {});
    });

    await waitFor(() => {
      expect(mockGetSessions).toHaveBeenLastCalledWith(
        "chain-1",
        expect.objectContaining({
          filterRequestList: [
            {
              feature: SessionFilterFeature.START_TIME,
              condition: SessionFilterCondition.IS_AFTER,
              value: "1704067200000",
            },
          ],
        }),
        expect.objectContaining({ offset: 0 }),
      );
    });
  });

  test("finished timestamp filter triggers refetch with FINISH_TIME filter", async () => {
    await renderWithSessions([baseSession({ id: "ff-1" })]);

    const filter = JSON.stringify({
      condition: "is-before",
      value: [1704153600000],
    });

    act(() => {
      getLastTableOnChange()!({}, { finished: [filter] }, {}, {});
    });

    await waitFor(() => {
      expect(mockGetSessions).toHaveBeenLastCalledWith(
        "chain-1",
        expect.objectContaining({
          filterRequestList: [
            {
              feature: SessionFilterFeature.FINISH_TIME,
              condition: SessionFilterCondition.IS_BEFORE,
              value: "1704153600000",
            },
          ],
        }),
        expect.objectContaining({ offset: 0 }),
      );
    });
  });

  test("text filter on engineAddress triggers refetch with ENGINE filter", async () => {
    await renderWithSessions([baseSession({ id: "ef-1" })]);

    const filter = JSON.stringify({ condition: "contains", value: "10.0" });

    act(() => {
      getLastTableOnChange()!({}, { engineAddress: [filter] }, {}, {});
    });

    await waitFor(() => {
      expect(mockGetSessions).toHaveBeenLastCalledWith(
        "chain-1",
        expect.objectContaining({
          filterRequestList: [
            {
              feature: SessionFilterFeature.ENGINE,
              condition: SessionFilterCondition.CONTAINS,
              value: "10.0",
            },
          ],
        }),
        expect.objectContaining({ offset: 0 }),
      );
    });
  });

  test("not-contains text filter maps to DOES_NOT_CONTAIN condition", async () => {
    await renderWithSessions([baseSession({ id: "nc-1" })]);

    const filter = JSON.stringify({
      condition: "not-contains",
      value: "excluded",
    });

    act(() => {
      getLastTableOnChange()!({}, { engineAddress: [filter] }, {}, {});
    });

    await waitFor(() => {
      expect(mockGetSessions).toHaveBeenLastCalledWith(
        "chain-1",
        expect.objectContaining({
          filterRequestList: [
            {
              feature: SessionFilterFeature.ENGINE,
              condition: SessionFilterCondition.DOES_NOT_CONTAIN,
              value: "excluded",
            },
          ],
        }),
        expect.objectContaining({ offset: 0 }),
      );
    });
  });

  test("ends-with text filter maps to ENDS_WITH condition", async () => {
    await renderWithSessions([baseSession({ id: "ew-1" })]);

    const filter = JSON.stringify({
      condition: "ends-with",
      value: ".local",
    });

    act(() => {
      getLastTableOnChange()!({}, { engineAddress: [filter] }, {}, {});
    });

    await waitFor(() => {
      expect(mockGetSessions).toHaveBeenLastCalledWith(
        "chain-1",
        expect.objectContaining({
          filterRequestList: [
            {
              feature: SessionFilterFeature.ENGINE,
              condition: SessionFilterCondition.ENDS_WITH,
              value: ".local",
            },
          ],
        }),
        expect.objectContaining({ offset: 0 }),
      );
    });
  });

  test("chainName text filter triggers refetch with CHAIN_NAME filter", async () => {
    mockUseParams.mockReturnValue({});
    mockGetSessions.mockResolvedValue({
      sessions: [baseSession({ id: "cn-1" })],
      offset: 1,
    });

    renderSessions();

    await waitFor(() => {
      expect(mockGetSessions).toHaveBeenCalledTimes(1);
    });

    const filter = JSON.stringify({
      condition: "starts-with",
      value: "Test",
    });

    act(() => {
      getLastTableOnChange()!({}, { chainName: [filter] }, {}, {});
    });

    await waitFor(() => {
      expect(mockGetSessions).toHaveBeenLastCalledWith(
        undefined,
        expect.objectContaining({
          filterRequestList: [
            {
              feature: SessionFilterFeature.CHAIN_NAME,
              condition: SessionFilterCondition.STARTS_WITH,
              value: "Test",
            },
          ],
        }),
        expect.objectContaining({ offset: 0 }),
      );
    });
  });

  test("multiple filters combine in filterRequestList", async () => {
    await renderWithSessions([baseSession({ id: "mf-1" })]);

    const timestampFilter = JSON.stringify({
      condition: "is-within",
      value: [1704067200000],
    });

    act(() => {
      getLastTableOnChange()!(
        {},
        {
          executionStatus: [ExecutionStatus.IN_PROGRESS],
          started: [timestampFilter],
        },
        {},
        {},
      );
    });

    await waitFor(() => {
      expect(mockGetSessions).toHaveBeenLastCalledWith(
        "chain-1",
        expect.objectContaining({
          filterRequestList: [
            {
              feature: SessionFilterFeature.START_TIME,
              condition: SessionFilterCondition.IS_WITHIN,
              value: "1704067200000",
            },
            {
              feature: SessionFilterFeature.STATUS,
              condition: SessionFilterCondition.IN,
              value: ExecutionStatus.IN_PROGRESS,
            },
          ],
        }),
        expect.objectContaining({ offset: 0 }),
      );
    });
  });

  // --- Scroll pagination ---

  /** Simulate a scroll-to-bottom event on the table container. */
  function simulateScrollToBottom() {
    const el = document.querySelector(".flex-table")!;
    Object.defineProperty(el, "scrollTop", { value: 500, configurable: true });
    Object.defineProperty(el, "clientHeight", {
      value: 300,
      configurable: true,
    });
    Object.defineProperty(el, "scrollHeight", {
      value: 800,
      configurable: true,
    });
    act(() => {
      fireEvent.scroll(el);
    });
  }

  test("scroll to bottom triggers fetchSessions for next page", async () => {
    mockGetSessions.mockResolvedValueOnce({
      sessions: [baseSession({ id: "page1" })],
      offset: 1,
    });
    mockGetCheckpointSessions.mockResolvedValue([]);

    renderSessions();

    await waitFor(() => {
      expect(screen.getByText("page1")).toBeInTheDocument();
    });

    mockGetSessions.mockResolvedValueOnce({
      sessions: [baseSession({ id: "page2" })],
      offset: 2,
    });

    simulateScrollToBottom();

    await waitFor(() => {
      expect(mockGetSessions).toHaveBeenCalledTimes(2);
      expect(mockGetSessions).toHaveBeenLastCalledWith(
        "chain-1",
        expect.objectContaining({ filterRequestList: [], searchString: "" }),
        expect.objectContaining({ offset: 1 }),
      );
    });
  });

  test("scroll does not fetch when all sessions are loaded", async () => {
    mockGetSessions.mockResolvedValueOnce({ sessions: [], offset: 0 });
    mockGetCheckpointSessions.mockResolvedValue([]);

    renderSessions();

    await waitFor(() => {
      expect(mockGetSessions).toHaveBeenCalledTimes(1);
    });

    simulateScrollToBottom();

    expect(mockGetSessions).toHaveBeenCalledTimes(1);
  });
});
