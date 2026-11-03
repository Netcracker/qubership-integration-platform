/**
 * @jest-environment jsdom
 *
 * Tests `Deployments` page chain header toolbar via full page render (toolbar is inlined).
 */

import React from "react";
import { screen, waitFor, fireEvent, within } from "@testing-library/react";
import "@testing-library/jest-dom";
import type {
  CreateDeploymentRequest,
  Deployment,
  Snapshot,
} from "../../src/api/apiTypes.ts";
import { DeploymentStatus, DomainType } from "../../src/api/apiTypes.ts";
import type { CreateDeploymentOptions } from "../../src/components/modal/DeploymentCreate.tsx";
import { formatTimestamp } from "../../src/misc/format-utils.ts";
import { Deployments } from "../../src/pages/Deployments.tsx";
import { renderPageWithChainHeader } from "../helpers/renderWithChainHeader.tsx";

const mockUseParams = jest.fn(() => ({ chainId: "chain-1" }));

jest.mock("react-router", () => ({
  useParams: () => mockUseParams(),
}));

const mockSetDeployments = jest.fn<
  void,
  [React.SetStateAction<Deployment[]>]
>();
const mockRemoveDeployment = jest.fn<void, [Deployment]>();
const mockRefresh = jest.fn<Promise<void>, []>();

let mockDeployments: Deployment[] = [];
let mockSnapshots: Snapshot[] = [];
let mockIsLoading = false;

jest.mock("../../src/hooks/useDeployments.tsx", () => ({
  useDeployments: () => ({
    isLoading: mockIsLoading,
    deployments: mockDeployments,
    setDeployments: mockSetDeployments,
    removeDeployment: mockRemoveDeployment,
    refresh: mockRefresh,
  }),
}));

jest.mock("../../src/hooks/useSnapshots.tsx", () => ({
  useSnapshots: () => ({
    isLoading: false,
    snapshots: mockSnapshots,
    setSnapshots: jest.fn(),
  }),
}));

const mockRequestFailed = jest.fn<void, unknown[]>();
const mockNotificationService = {
  requestFailed: mockRequestFailed,
  errorWithDetails: jest.fn(),
  info: jest.fn(),
  warning: jest.fn(),
};

const mockDeleteDeployment = jest.fn<Promise<void>, [string]>();
const mockCreateDeployment = jest.fn<
  Promise<Deployment>,
  [string, CreateDeploymentRequest]
>();

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

jest.mock("../../src/hooks/useNotificationService.tsx", () => ({
  useNotificationService: () => mockNotificationService,
}));

jest.mock("../../src/Modals.tsx", () => {
  // eslint-disable-next-line @typescript-eslint/no-require-imports -- jest.mock factory; Fragment needs react in closure
  const R = require("react") as typeof import("react");
  const modalsApi = {
    showModal: jest.fn(),
    closeModal: jest.fn(),
  };
  return {
    Modals: ({ children }: { children: import("react").ReactNode }) => (
      <R.Fragment>{children}</R.Fragment>
    ),
    useModalsContext: () => modalsApi,
  };
});

jest.mock("../../src/components/modal/DeploymentCreate.tsx", () => ({
  DeploymentCreate: () => <div data-testid="deployment-create-stub" />,
}));

jest.mock("../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`}>{name}</span>
  ),
}));

jest.mock("../../src/permissions/Require.tsx", () => ({
  Require: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

jest.mock("../../src/components/LongActionButton.tsx", () => ({
  LongActionButton: ({
    icon,
    onSubmit,
  }: {
    icon?: React.ReactNode;
    onSubmit: () => void | Promise<void>;
  }) => (
    <button type="button" onClick={() => void onSubmit()}>
      {icon}
    </button>
  ),
}));

jest.mock("../../src/api/api.ts", () => ({
  api: {
    deleteDeployment: (...args: [string]) => mockDeleteDeployment(...args),
    createDeployment: (...args: [string, CreateDeploymentRequest]) =>
      mockCreateDeployment(...args),
  },
}));

function renderDeployments() {
  return renderPageWithChainHeader(<Deployments />);
}

function sampleDeployment(overrides: Partial<Deployment> = {}): Deployment {
  return {
    id: "dep-1",
    chainId: "chain-1",
    snapshotId: "snap-1",
    name: "dep-a",
    domain: "dom",
    domainType: DomainType.CLASSIC,
    createdWhen: 0,
    createdBy: { id: "u1", username: "user" },
    serviceName: "svc",
    ...overrides,
  };
}

describe("Deployments chain header toolbar", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockDeployments = [];
    mockSnapshots = [];
    mockIsLoading = false;
    mockUseParams.mockReturnValue({ chainId: "chain-1" });
    mockDeleteDeployment.mockResolvedValue(undefined);
    mockRefresh.mockResolvedValue(undefined);
  });

  test("registers header toolbar with search and Create deployment", async () => {
    renderDeployments();

    const slot = await waitFor(() => screen.getByTestId("chain-header-slot"));
    expect(
      within(slot).getByPlaceholderText("Search deployments..."),
    ).toBeInTheDocument();
    expect(within(slot).getByTestId("deployments-create")).toBeInTheDocument();
  });

  test("Create deployment invokes showModal", async () => {
    type ModalsTest = {
      useModalsContext: () => { showModal: jest.Mock; closeModal: jest.Mock };
    };
    const { useModalsContext } = jest.requireMock<ModalsTest>(
      "../../src/Modals.tsx",
    );
    const { showModal } = useModalsContext();

    renderDeployments();

    const slot = await waitFor(() => screen.getByTestId("chain-header-slot"));
    fireEvent.click(within(slot).getByTestId("deployments-create"));

    expect(showModal).toHaveBeenCalledTimes(1);
  });

  test("should open a warning modal before deleting a deployment", async () => {
    type ModalsTest = {
      useModalsContext: () => { showModal: jest.Mock; closeModal: jest.Mock };
    };
    const { useModalsContext } = jest.requireMock<ModalsTest>(
      "../../src/Modals.tsx",
    );
    const { showModal } = useModalsContext();
    mockDeployments = [sampleDeployment()];

    renderDeployments();

    fireEvent.click(screen.getByTestId("icon-delete").closest("button")!);

    expect(showModal).toHaveBeenCalledTimes(1);
    expect(mockDeleteDeployment).not.toHaveBeenCalled();

    const modal = showModal.mock.calls[0][0].component as React.ReactElement<{
      onDelete: () => void;
    }>;
    modal.props.onDelete();

    await waitFor(() => {
      expect(mockDeleteDeployment).toHaveBeenCalledWith("dep-1");
      expect(mockRemoveDeployment).toHaveBeenCalledWith(mockDeployments[0]);
    });
  });

  test("table scroll includes empty y when filtered deployments exist", async () => {
    mockDeployments = [sampleDeployment()];
    renderDeployments();

    await waitFor(() => {
      const raw = document
        .querySelector(".flex-table")
        ?.getAttribute("data-scroll");
      expect(raw).toBeTruthy();
      const scroll = JSON.parse(raw!) as { x: number; y?: string };
      expect(scroll.y).toBe("");
      expect(typeof scroll.x).toBe("number");
    });
  });

  test("table scroll omits y when there are no filtered deployments", async () => {
    mockDeployments = [];
    renderDeployments();

    await waitFor(() => {
      const raw = document
        .querySelector(".flex-table")
        ?.getAttribute("data-scroll");
      expect(raw).toBeTruthy();
      const scroll = JSON.parse(raw!) as { x: number; y?: string };
      expect("y" in scroll).toBe(false);
    });
  });

  test("renders snapshot names, micro tag, runtime states and timestamps", async () => {
    const createdWhen = new Date(2024, 0, 2, 12, 0, 0).getTime();
    mockSnapshots = [
      {
        id: "snap-1",
        name: "snapshot-one",
        description: "",
        xmlDefinition: "",
        labels: [],
      },
    ];
    mockDeployments = [
      sampleDeployment({
        id: "dep-classic",
        snapshotId: "snap-1",
        domain: "classic-domain",
        domainType: DomainType.CLASSIC,
        serviceName: "svc-a",
        createdWhen,
        createdBy: { id: "u1", username: "alice" },
        runtime: {
          states: {
            first: {
              status: DeploymentStatus.DEPLOYED,
              error: "",
              stacktrace: "",
              suspended: false,
            },
            second: {
              status: DeploymentStatus.FAILED,
              error: "",
              stacktrace: "",
              suspended: false,
            },
          },
        },
      }),
      sampleDeployment({
        id: "dep-micro",
        snapshotId: "snap-missing",
        domain: "micro-domain",
        domainType: DomainType.MICRO,
        serviceName: "svc-b",
        createdWhen,
        createdBy: { id: "u2", username: "bob" },
      }),
    ];

    renderDeployments();

    await waitFor(() =>
      expect(screen.getByText("snapshot-one")).toBeInTheDocument(),
    );
    expect(screen.getByText("snap-missing")).toBeInTheDocument();
    expect(screen.getAllByText("micro")).toHaveLength(1);
    expect(screen.getByText("classic-domain")).toBeInTheDocument();
    expect(screen.getByText("micro-domain")).toBeInTheDocument();
    expect(screen.getByText("alice")).toBeInTheDocument();
    expect(screen.getByText("bob")).toBeInTheDocument();
    expect(screen.getAllByText(formatTimestamp(createdWhen))).toHaveLength(2);
    expect(
      screen.getByTestId("deployment-status-deployed"),
    ).toBeInTheDocument();
    expect(screen.getByTestId("deployment-status-failed")).toBeInTheDocument();
    expect(screen.getByText("First")).toBeInTheDocument();
    expect(screen.getByText("Second")).toBeInTheDocument();
  });

  test("filters deployments across every searchable field", async () => {
    const createdAt = new Date(2024, 0, 2, 12, 0, 0).getTime();
    mockSnapshots = [
      {
        id: "snap-aaa",
        name: "snapshot-alpha",
        description: "",
        xmlDefinition: "",
        labels: [],
      },
    ];
    mockDeployments = [
      sampleDeployment({
        id: "dep-a",
        name: "deploy-alpha",
        domain: "domain-alpha",
        serviceName: "service-alpha",
        snapshotId: "snap-aaa",
        createdWhen: createdAt,
        createdBy: { id: "u1", username: "alice" },
      }),
      sampleDeployment({
        id: "dep-b",
        name: "deploy-beta",
        domain: "domain-beta",
        serviceName: "service-beta",
        snapshotId: "snap-bbb",
        createdWhen: new Date(2023, 5, 15, 12, 0, 0).getTime(),
        createdBy: { id: "u2", username: "bob" },
      }),
    ];

    renderDeployments();
    const search = await waitFor(() => screen.getByTestId("search-input"));

    const terms = [
      "deploy-alpha",
      "domain-alpha",
      "service-alpha",
      "snapshot-alpha",
      "snap-aaa",
      "alice",
      "Jan 02, 2024",
    ];
    for (const term of terms) {
      fireEvent.change(search, { target: { value: term } });
      await waitFor(() =>
        expect(
          document.querySelector('tr[data-row-key="dep-a"]'),
        ).toBeInTheDocument(),
      );
      expect(
        document.querySelector('tr[data-row-key="dep-b"]'),
      ).not.toBeInTheDocument();
    }
  });

  test("reports a failed delete and keeps the deployment", async () => {
    const error = new Error("delete failed");
    mockDeleteDeployment.mockRejectedValueOnce(error);
    mockDeployments = [sampleDeployment()];

    renderDeployments();

    fireEvent.click(screen.getByTestId("icon-delete").closest("button")!);

    type ModalsTest = {
      useModalsContext: () => { showModal: jest.Mock };
    };
    const { useModalsContext } = jest.requireMock<ModalsTest>(
      "../../src/Modals.tsx",
    );
    const modal = useModalsContext().showModal.mock.calls[0][0].component as
      | React.ReactElement<{ onDelete: () => void }>
      | undefined;
    modal!.props.onDelete();

    await waitFor(() =>
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Failed to delete deployment",
        error,
      ),
    );
    expect(mockRemoveDeployment).not.toHaveBeenCalled();
  });

  test("creates a deployment per domain, skips duplicates and reports a failure", async () => {
    type ModalsTest = {
      useModalsContext: () => { showModal: jest.Mock };
    };
    const { useModalsContext } = jest.requireMock<ModalsTest>(
      "../../src/Modals.tsx",
    );
    const { showModal } = useModalsContext();

    renderDeployments();
    const slot = await waitFor(() => screen.getByTestId("chain-header-slot"));
    fireEvent.click(within(slot).getByTestId("deployments-create"));

    type CreateProps = {
      chainId?: string;
      initialDomains?: { name: string; type: DomainType }[];
      onSubmit?: (options: CreateDeploymentOptions) => void | Promise<void>;
    };
    const createElement = showModal.mock.calls[0][0].component as
      | React.ReactElement<CreateProps>
      | undefined;
    expect(createElement!.props.chainId).toBe("chain-1");
    expect(createElement!.props.initialDomains).toEqual([]);

    const first = sampleDeployment({ id: "dep-a" });
    const second = sampleDeployment({ id: "dep-b" });
    mockCreateDeployment
      .mockResolvedValueOnce(first)
      .mockResolvedValueOnce(second);

    await createElement!.props.onSubmit!({
      snapshotId: "snap-x",
      domains: [
        { name: "classic-domain", type: DomainType.CLASSIC },
        { name: "micro-domain", type: DomainType.MICRO },
      ],
    });

    expect(mockCreateDeployment).toHaveBeenNthCalledWith(1, "chain-1", {
      domain: "classic-domain",
      domainType: DomainType.CLASSIC,
      snapshotId: "snap-x",
      suspended: false,
    });
    expect(mockCreateDeployment).toHaveBeenNthCalledWith(2, "chain-1", {
      domain: "micro-domain",
      domainType: DomainType.MICRO,
      snapshotId: "snap-x",
      suspended: false,
    });

    const updaters = mockSetDeployments.mock.calls.map(([action]) => {
      if (typeof action !== "function") {
        throw new Error("expected a state updater");
      }
      return action;
    });
    expect(updaters).toHaveLength(2);
    expect(updaters[0]([])).toEqual([first]);
    expect(updaters[0]([first])).toEqual([first]);
    expect(updaters[1]([first])).toEqual([first, second]);

    const error = new Error("create failed");
    mockCreateDeployment.mockRejectedValueOnce(error);
    await createElement!.props.onSubmit!({
      snapshotId: "snap-x",
      domains: [{ name: "broken-domain", type: DomainType.CLASSIC }],
    });
    expect(mockRequestFailed).toHaveBeenCalledWith(
      "Failed to create deployment",
      error,
    );
  });

  test("refreshes deployments from the header toolbar", async () => {
    renderDeployments();
    const slot = await waitFor(() => screen.getByTestId("chain-header-slot"));

    fireEvent.click(within(slot).getByTestId("protected-btn-refresh"));

    await waitFor(() => expect(mockRefresh).toHaveBeenCalledTimes(1));
  });
});
