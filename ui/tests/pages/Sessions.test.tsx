/**
 * @jest-environment jsdom
 */

import React from "react";
import { screen, waitFor, within, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import {
  ExecutionStatus,
  SessionsLoggingLevel,
  type Session,
} from "../../src/api/apiTypes";
import { api } from "../../src/api/api.ts";
import { Sessions } from "../../src/pages/Sessions.tsx";
import { renderPageWithChainHeader } from "../helpers/renderWithChainHeader.tsx";

jest.mock("../../src/api/api.ts", () => ({
  api: {
    getSessions: jest.fn(),
    getCheckpointSessions: jest.fn(),
  },
}));

const mockNavigate = jest.fn();
const mockUseParams = jest.fn(() => ({ chainId: "chain-1" }));

jest.mock("react-router", () => ({
  useNavigate: () => mockNavigate,
  useParams: () => mockUseParams(),
}));

jest.mock("antd", () =>
  // eslint-disable-next-line @typescript-eslint/no-require-imports, @typescript-eslint/no-unsafe-call, @typescript-eslint/no-unsafe-member-access, @typescript-eslint/no-unsafe-return -- jest.mock hoisting; require avoids TDZ
  require("tests/helpers/chainPageAntdJestMock").createChainPageAntdMock(),
);

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
    <span>{String(status)}</span>
  ),
}));

jest.mock(
  "../../src/components/table/TimestampColumnFilterDropdown.tsx",
  () => ({
    TimestampColumnFilterDropdown: () => <div />,
    isTimestampFilter: () => false,
  }),
);

jest.mock("../../src/components/table/TextColumnFilterDropdown.tsx", () => ({
  TextColumnFilterDropdown: () => <div />,
  isTextFilter: () => false,
}));

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

jest.mock("../../src/hooks/useNotificationService.tsx", () => {
  const notificationService = {
    requestFailed: jest.fn(),
    errorWithDetails: jest.fn(),
    info: jest.fn(),
    warning: jest.fn(),
  };
  return {
    useNotificationService: () => notificationService,
  };
});

jest.mock("../../src/Modals.tsx", () => {
  const R = require("react") as typeof import("react");
  const modalsApi = {
    showModal: jest.fn(),
    closeModal: jest.fn(),
  };
  return {
    Modals: ({ children }: { children: R.ReactNode }) => (
      <R.Fragment>{children}</R.Fragment>
    ),
    useModalsContext: () => modalsApi,
  };
});

jest.mock("../../src/misc/download-utils.ts", () => ({
  downloadFile: jest.fn(),
}));

jest.mock("../../src/misc/format-utils.ts", () => {
  const actual = jest.requireActual("../../src/misc/format-utils.ts") as Record<
    string,
    unknown
  >;
  return {
    ...actual,
    formatUTCSessionDate: () => "2024-01-01 00:00:00 UTC",
    formatDuration: (ms: number) => `${ms}ms`,
    capitalize: (s: string) =>
      s ? s.charAt(0).toUpperCase() + s.slice(1) : "",
  };
});

function baseSession(overrides: Partial<Session> & { id: string }): Session {
  return {
    id: overrides.id,
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
    ...overrides,
  };
}

function renderSessions() {
  return renderPageWithChainHeader(<Sessions />);
}

describe("Sessions", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockUseParams.mockReturnValue({ chainId: "chain-1" });
    (api.getCheckpointSessions as jest.Mock).mockResolvedValue([]);
  });

  test("renders Refresh control with sessions-refresh test id", async () => {
    (api.getSessions as jest.Mock).mockResolvedValue({
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
    (api.getSessions as jest.Mock)
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

    expect(api.getSessions).toHaveBeenCalledTimes(2);
    expect(api.getSessions).toHaveBeenLastCalledWith(
      "chain-1",
      expect.objectContaining({
        filterRequestList: [],
        searchString: "",
      }),
      expect.objectContaining({ offset: 0 }),
    );
  });

  test("Refresh clears row selection", async () => {
    (api.getSessions as jest.Mock)
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
      expect(api.getSessions).toHaveBeenCalledTimes(2);
    });

    const rowAfter = screen.getByRole("row", { name: /sel-row/i });
    expect(within(rowAfter).getByRole("checkbox")).not.toBeChecked();
  });
});
