/**
 * @jest-environment jsdom
 */

import "@testing-library/jest-dom";
import React from "react";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import {
  CatalogItemType,
  ChainItem,
  ChainLoggingSettings,
  Deployment,
  DeploymentStatus,
  LogLoggingLevel,
  LogPayload,
  SessionsLoggingLevel,
} from "../../../src/api/apiTypes";

// Replace antd Drawer with a simple conditional renderer; keep Table lightweight.
const DrawerStub = ({
  title,
  open,
  onClose,
  children,
}: {
  title?: React.ReactNode;
  open?: boolean;
  onClose?: () => void;
  children?: React.ReactNode;
}) =>
  open ? (
    <div data-testid="chain-details-drawer">
      <div data-testid="drawer-title">{title}</div>
      <button data-testid="drawer-close" onClick={onClose}>
        close
      </button>
      <div>{children}</div>
    </div>
  ) : null;

jest.mock("antd", () =>
  require("tests/helpers/antdMockWithLightweightTable").antdMockWithLightweightTable(
    { Drawer: DrawerStub },
  ),
);

const mockGetDeployments = jest.fn();
const mockGetLoggingSettings = jest.fn();
jest.mock("../../../src/api/api", () => ({
  api: {
    getDeployments: (...args: unknown[]) => mockGetDeployments(...args),
    getLoggingSettings: (...args: unknown[]) => mockGetLoggingSettings(...args),
  },
}));

const mockRequestFailed = jest.fn();
jest.mock("../../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => ({
    requestFailed: mockRequestFailed,
    info: jest.fn(),
    warning: jest.fn(),
    errorWithDetails: jest.fn(),
  }),
}));

jest.mock(
  "../../../src/components/deployment_runtime_states/DeploymentsCumulativeState",
  () => ({
    DeploymentsCumulativeState: ({ chainId }: { chainId: string }) => (
      <span data-testid="deployment-cumulative-state">{chainId}</span>
    ),
  }),
);

jest.mock("../../../src/components/labels/EntityLabels", () => ({
  EntityLabels: ({ labels }: { labels: { name: string }[] }) => (
    <span data-testid="entity-labels">
      {labels.map((l) => l.name).join(",")}
    </span>
  ),
}));

jest.mock("../../../src/components/logging/LoggingSettingsSourceTag", () => ({
  LoggingSettingsSourceTag: ({
    isCustom,
    isConsulDefault,
  }: {
    isCustom?: boolean;
    isConsulDefault?: boolean;
  }) => (
    <span data-testid="logging-source-tag">
      {isCustom
        ? "Custom"
        : isConsulDefault
          ? "Default (Consul)"
          : "Default (Fallback)"}
    </span>
  ),
}));

jest.mock("../../../src/misc/format-utils", () => ({
  formatTimestamp: (ts: number) => `ts:${ts}`,
  capitalize: (s: string) =>
    s ? s.charAt(0).toUpperCase() + s.slice(1).toLowerCase() : "",
}));

// Import after mocks
import { ChainDetailsDrawer } from "../../../src/components/chains/ChainDetailsDrawer";

const chainFull: ChainItem = {
  id: "chain-1",
  name: "My Chain",
  description: "chain description",
  itemType: CatalogItemType.CHAIN,
  labels: [{ name: "env", technical: false }],
  createdWhen: 1700000000000,
  createdBy: { id: "u1", username: "alice" },
  modifiedWhen: 1700000001000,
  modifiedBy: { id: "u2", username: "bob" },
};

const chainMinimal: ChainItem = {
  id: "chain-2",
  name: "",
  description: "",
  itemType: CatalogItemType.CHAIN,
  labels: [],
};

const baseRuntimeState = {
  error: "",
  stacktrace: "",
  suspended: false,
};

const deploymentWithEngines: Deployment = {
  id: "dep-1",
  chainId: "chain-1",
  snapshotId: "snap-1",
  name: "dep-1",
  domain: "default",
  serviceName: "svc",
  createdWhen: 123,
  createdBy: { id: "u1", username: "alice" },
  runtime: {
    states: {
      "10.0.0.1": { status: DeploymentStatus.DEPLOYED, ...baseRuntimeState },
      "10.0.0.2": { status: DeploymentStatus.FAILED, ...baseRuntimeState },
    },
  },
};

const customLoggingSettings: ChainLoggingSettings = {
  fallbackDefault: {
    sessionsLoggingLevel: SessionsLoggingLevel.OFF,
    logLoggingLevel: LogLoggingLevel.ERROR,
    logPayload: [LogPayload.BODY],
    dptEventsEnabled: false,
    maskingEnabled: true,
  },
  custom: {
    sessionsLoggingLevel: SessionsLoggingLevel.DEBUG,
    logLoggingLevel: LogLoggingLevel.INFO,
    logPayload: [LogPayload.HEADERS, LogPayload.PROPERTIES],
    dptEventsEnabled: true,
    maskingEnabled: false,
  },
};

const consulLoggingSettings: ChainLoggingSettings = {
  fallbackDefault: {
    sessionsLoggingLevel: SessionsLoggingLevel.OFF,
    logLoggingLevel: LogLoggingLevel.ERROR,
    logPayload: [],
    dptEventsEnabled: false,
    maskingEnabled: false,
  },
  consulDefault: {
    sessionsLoggingLevel: SessionsLoggingLevel.INFO,
    logLoggingLevel: LogLoggingLevel.WARNING,
    logPayload: [],
    dptEventsEnabled: true,
    maskingEnabled: false,
  },
};

describe("ChainDetailsDrawer", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockGetDeployments.mockResolvedValue([]);
    mockGetLoggingSettings.mockResolvedValue(null);
  });

  it("does not render drawer content when closed", () => {
    render(
      <ChainDetailsDrawer chain={chainFull} open={false} onClose={jest.fn()} />,
    );
    expect(
      screen.queryByTestId("chain-details-drawer"),
    ).not.toBeInTheDocument();
    expect(mockGetDeployments).not.toHaveBeenCalled();
  });

  it("does not fetch when chain is null even if open", () => {
    render(<ChainDetailsDrawer chain={null} open={true} onClose={jest.fn()} />);
    expect(mockGetDeployments).not.toHaveBeenCalled();
    expect(mockGetLoggingSettings).not.toHaveBeenCalled();
  });

  it("loads deployments and logging settings when open and renders all fields", async () => {
    mockGetDeployments.mockResolvedValue([deploymentWithEngines]);
    mockGetLoggingSettings.mockResolvedValue(customLoggingSettings);

    render(
      <ChainDetailsDrawer chain={chainFull} open={true} onClose={jest.fn()} />,
    );

    await waitFor(() => {
      expect(mockGetDeployments).toHaveBeenCalledWith("chain-1");
      expect(mockGetLoggingSettings).toHaveBeenCalledWith("chain-1");
    });

    expect(screen.getByTestId("drawer-title")).toHaveTextContent(
      "Chain Details",
    );
    // The chain id appears both in the Id field and in the cumulative-state mock.
    expect(screen.getAllByText("chain-1").length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText("My Chain")).toBeInTheDocument();
    expect(screen.getByText("chain description")).toBeInTheDocument();
    expect(screen.getByTestId("entity-labels")).toHaveTextContent("env");
    expect(screen.getByTestId("deployment-cumulative-state")).toHaveTextContent(
      "chain-1",
    );

    // Deployment row becomes visible once the async fetch resolves.
    expect(await screen.findByText("default")).toBeInTheDocument();
    expect(screen.getByText("10.0.0.1")).toBeInTheDocument();
    expect(screen.getByText("10.0.0.2")).toBeInTheDocument();

    // Custom logging settings picked
    expect(screen.getByTestId("logging-source-tag")).toHaveTextContent(
      "Custom",
    );
    expect(screen.getByText("Debug")).toBeInTheDocument();
    expect(screen.getByText("Info")).toBeInTheDocument();
    expect(screen.getByText("Headers")).toBeInTheDocument();
    expect(screen.getByText("Properties")).toBeInTheDocument();
    expect(screen.getByText("true")).toBeInTheDocument();
    expect(screen.getByText("false")).toBeInTheDocument();

    // Timestamps with author
    expect(screen.getByText("ts:1700000000000 by alice")).toBeInTheDocument();
    expect(screen.getByText("ts:1700000001000 by bob")).toBeInTheDocument();
  });

  it("falls back to em dash placeholders when fields are empty", async () => {
    render(
      <ChainDetailsDrawer
        chain={chainMinimal}
        open={true}
        onClose={jest.fn()}
      />,
    );
    await waitFor(() => expect(mockGetDeployments).toHaveBeenCalled());
    // name, description, labels, logging fields, created, modified → many dashes
    const dashes = screen.getAllByText("—");
    expect(dashes.length).toBeGreaterThan(5);
  });

  it("shows 'Not deployed' empty state when there are no deployments", async () => {
    render(
      <ChainDetailsDrawer chain={chainFull} open={true} onClose={jest.fn()} />,
    );
    await waitFor(() => expect(mockGetDeployments).toHaveBeenCalled());
    expect(screen.getByText("Not deployed")).toBeInTheDocument();
  });

  it("renders deployment row with em dash when there are no active engines", async () => {
    mockGetDeployments.mockResolvedValue([
      { ...deploymentWithEngines, runtime: undefined },
    ]);
    render(
      <ChainDetailsDrawer chain={chainFull} open={true} onClose={jest.fn()} />,
    );
    await waitFor(() => expect(mockGetDeployments).toHaveBeenCalled());
    expect(screen.getByText("default")).toBeInTheDocument();
    // No engine hosts rendered as tags
    expect(screen.queryByText("10.0.0.1")).not.toBeInTheDocument();
    expect(screen.getAllByText("—").length).toBeGreaterThan(0);
  });

  it("falls back to em dash for domain when deployment.domain is empty", async () => {
    mockGetDeployments.mockResolvedValue([
      { ...deploymentWithEngines, domain: "" },
    ]);
    render(
      <ChainDetailsDrawer chain={chainFull} open={true} onClose={jest.fn()} />,
    );
    await waitFor(() => expect(mockGetDeployments).toHaveBeenCalled());
    expect(screen.queryByText("default")).not.toBeInTheDocument();
  });

  it("uses consulDefault logging when custom is absent", async () => {
    mockGetLoggingSettings.mockResolvedValue(consulLoggingSettings);
    render(
      <ChainDetailsDrawer chain={chainFull} open={true} onClose={jest.fn()} />,
    );
    await waitFor(() => expect(mockGetLoggingSettings).toHaveBeenCalled());
    expect(screen.getByTestId("logging-source-tag")).toHaveTextContent(
      "Default (Consul)",
    );
    expect(screen.getByText("Info")).toBeInTheDocument();
    expect(screen.getByText("Warn")).toBeInTheDocument();
  });

  it("reports deployments API errors via the notification service", async () => {
    mockGetDeployments.mockRejectedValue(new Error("dep fail"));
    render(
      <ChainDetailsDrawer chain={chainFull} open={true} onClose={jest.fn()} />,
    );
    await waitFor(() => {
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Failed to load deployments",
        expect.any(Error),
      );
    });
  });

  it("reports logging settings API errors via the notification service", async () => {
    mockGetLoggingSettings.mockRejectedValue(new Error("logs fail"));
    render(
      <ChainDetailsDrawer chain={chainFull} open={true} onClose={jest.fn()} />,
    );
    await waitFor(() => {
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Failed to load logging settings",
        expect.any(Error),
      );
    });
  });

  it("fires onClose when the close button is clicked", async () => {
    const onClose = jest.fn();
    render(
      <ChainDetailsDrawer chain={chainFull} open={true} onClose={onClose} />,
    );
    await waitFor(() => expect(mockGetDeployments).toHaveBeenCalled());
    fireEvent.click(screen.getByTestId("drawer-close"));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("does not crash when unmounted while requests are in-flight", async () => {
    let resolveDeployments: (value: Deployment[]) => void = () => undefined;
    mockGetDeployments.mockReturnValue(
      new Promise<Deployment[]>((resolve) => {
        resolveDeployments = resolve;
      }),
    );
    const { unmount } = render(
      <ChainDetailsDrawer chain={chainFull} open={true} onClose={jest.fn()} />,
    );
    unmount();
    await act(async () => {
      resolveDeployments([deploymentWithEngines]);
      await Promise.resolve();
    });
    // No errors — test simply completes.
  });
});
