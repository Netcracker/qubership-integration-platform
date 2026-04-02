/**
 * @jest-environment jsdom
 */
import React from "react";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import { Modal } from "antd";
import { LiveExchanges } from "../../../../src/components/admin_tools/exchanges/LiveExchanges";
import { SessionsLoggingLevel } from "../../../../src/api/apiTypes";
import { ProtectedButtonProps } from "../../../../src/permissions/ProtectedButton";
import type { EntityFilterModel } from "../../../../src/components/table/filter/filter";

jest.mock("antd", () =>
  require("tests/helpers/antdMockWithLightweightTable").antdMockWithLightweightTable(),
);

const mockGetAndFilterExchanges = jest.fn();
const mockTerminateExchange = jest.fn();

jest.mock("../../../../src/api/api", () => ({
  api: {
    getAndFilterExchanges: (...args: unknown[]) =>
      mockGetAndFilterExchanges(...args),
    terminateExchange: (...args: unknown[]) => mockTerminateExchange(...args),
  },
}));

const mockNavigate = jest.fn();
jest.mock("react-router", () => ({
  useNavigate: () => mockNavigate,
}));

/** Stable reference — a new [] each render breaks refresh/useEffect deps and loops forever. */
const stableLiveExchangeFilters: EntityFilterModel[] = [];

jest.mock(
  "../../../../src/components/admin_tools/exchanges/useLiveExchangeFilters",
  () => ({
    useLiveExchangeFilters: () => ({
      filters: stableLiveExchangeFilters,
      filterButton: (
        <span data-testid="live-exchange-filter-button">Filters</span>
      ),
    }),
  }),
);

jest.mock(
  "../../../../src/components/admin_tools/CommonStyle.module.css",
  () => ({}),
);

jest.mock("../../../../src/icons/IconProvider", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`} />
  ),
}));

jest.mock("../../../../src/permissions/ProtectedButton", () => ({
  ProtectedButton: ({ buttonProps, tooltipProps }: ProtectedButtonProps) => {
    const { iconName: _icon, icon: _iconNode, ...rest } = buttonProps;
    return (
      <button
        type="button"
        data-testid={String(tooltipProps.title)}
        {...(rest as React.ButtonHTMLAttributes<HTMLButtonElement>)}
      />
    );
  },
}));

const mockRequestFailed = jest.fn();
jest.mock("../../../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => ({
    requestFailed: mockRequestFailed,
  }),
}));

globalThis.ResizeObserver = jest.fn().mockImplementation(() => ({
  observe: jest.fn(),
  unobserve: jest.fn(),
  disconnect: jest.fn(),
}));

const singleExchange = {
  exchangeId: "ex-1",
  deploymentId: "dep-1",
  sessionId: "sess-1",
  chainId: "chain-1",
  chainName: "Chain A",
  duration: 1000,
  sessionDuration: 5000,
  sessionStartTime: 1_700_000_000_000,
  sessionLogLevel: SessionsLoggingLevel.INFO,
  main: true,
  podIp: "10.0.0.1",
};

describe("LiveExchanges", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    localStorage.clear();
    mockGetAndFilterExchanges.mockResolvedValue([singleExchange]);
    mockTerminateExchange.mockResolvedValue(undefined);
    jest.spyOn(Modal, "confirm").mockImplementation((config) => {
      void Promise.resolve(config.onOk?.());
      return { destroy: jest.fn(), update: jest.fn() };
    });
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it("loads exchanges on mount and shows toolbar", async () => {
    render(<LiveExchanges />);

    expect(screen.getByText("Live Exchanges")).toBeInTheDocument();
    expect(screen.getByText("Exchanges per engine:")).toBeInTheDocument();
    expect(
      screen.getByTestId("live-exchange-filter-button"),
    ).toBeInTheDocument();

    await waitFor(() => {
      expect(mockGetAndFilterExchanges).toHaveBeenCalledWith(10, []);
    });
    await screen.findByText("sess-1");
    expect(screen.getByText("Chain A")).toBeInTheDocument();
  });

  it("renders no exchange rows when API returns an empty list", async () => {
    mockGetAndFilterExchanges.mockResolvedValue([]);
    render(<LiveExchanges />);

    await waitFor(() => {
      expect(mockGetAndFilterExchanges).toHaveBeenCalled();
    });
    expect(screen.queryByText("sess-1")).not.toBeInTheDocument();
  });

  it("refresh triggers another fetch", async () => {
    render(<LiveExchanges />);

    await waitFor(() =>
      expect(mockGetAndFilterExchanges.mock.calls.length).toBeGreaterThan(0),
    );
    const callsBefore = mockGetAndFilterExchanges.mock.calls.length;

    const refreshIcon = screen.getByTestId("icon-refresh");
    const refreshBtn = refreshIcon.closest("button");
    if (!refreshBtn) {
      throw new Error("refresh button not found");
    }
    fireEvent.click(refreshBtn);

    await waitFor(() => {
      expect(mockGetAndFilterExchanges.mock.calls.length).toBeGreaterThan(
        callsBefore,
      );
    });
  });

  it("notifies on fetch failure", async () => {
    mockGetAndFilterExchanges.mockRejectedValue(new Error("boom"));
    render(<LiveExchanges />);

    await waitFor(() => {
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Failed to get live exchanges",
        expect.any(Error),
      );
    });
  });

  it("navigates to chain when chain name is clicked", async () => {
    render(<LiveExchanges />);
    await screen.findByText("Chain A");

    const chainLink = screen.getByText("Chain A").closest("a");
    if (!chainLink) {
      throw new Error("chain link not found");
    }
    fireEvent.click(chainLink);

    expect(mockNavigate).toHaveBeenCalledWith("/chains/chain-1");
  });

  it("groups rows by session and uses stable row keys", async () => {
    mockGetAndFilterExchanges.mockResolvedValue([
      { ...singleExchange, exchangeId: "ex-a" },
      { ...singleExchange, exchangeId: "ex-b", main: false },
    ]);
    render(<LiveExchanges />);

    await screen.findByText("sess-1");
    const rows = document.querySelectorAll("[data-row-key]");
    expect(rows.length).toBeGreaterThanOrEqual(1);
    expect(
      Array.from(rows).some((r) => r.dataset.rowKey?.startsWith("group-")),
    ).toBe(true);
  });

  it("terminate calls API after confirm", async () => {
    render(<LiveExchanges />);
    await screen.findByText("sess-1");

    fireEvent.click(screen.getByTestId("Terminate exchange"));

    await waitFor(() => {
      expect(mockTerminateExchange).toHaveBeenCalledWith(
        "10.0.0.1",
        "dep-1",
        "ex-1",
      );
    });
  });
});
