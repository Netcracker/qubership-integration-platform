/**
 * @jest-environment jsdom
 *
 * Tests `Snapshots` page chain header toolbar via full page render (toolbar is inlined).
 */

import React from "react";
import { screen, waitFor, fireEvent, within } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { Snapshot } from "../../src/api/apiTypes.ts";
import { api } from "../../src/api/api.ts";
import { ChainContext } from "../../src/pages/ChainPage.tsx";
import { Snapshots } from "../../src/pages/Snapshots.tsx";
import { renderPageWithChainHeader } from "../helpers/renderWithChainHeader.tsx";

const mockUseParams = jest.fn(() => ({ chainId: "chain-1" }));
const mockNavigate = jest.fn();
const chainRefreshMock = jest.fn().mockResolvedValue(undefined);
const mockConfirmAndRun = jest.fn();

jest.mock("react-router", () => ({
  useParams: () => mockUseParams(),
  useNavigate: () => mockNavigate,
}));

jest.mock("../../src/api/api.ts", () => ({
  api: {
    getSnapshots: jest.fn(),
    createSnapshot: jest.fn(),
    deleteSnapshot: jest.fn(),
    deleteSnapshots: jest.fn(),
    updateSnapshot: jest.fn(),
    revertToSnapshot: jest.fn(),
    getSnapshotSequenceDiagram: jest.fn(),
  },
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

jest.mock("../../src/components/table/TextColumnFilterDropdown.tsx", () => {
  const actual = jest.requireActual<
    typeof import("../../src/components/table/TextColumnFilterDropdown.tsx")
  >("../../src/components/table/TextColumnFilterDropdown.tsx");
  return {
    ...actual,
    TextColumnFilterDropdown: () => <div />,
  };
});

jest.mock(
  "../../src/components/table/TimestampColumnFilterDropdown.tsx",
  () => {
    const actual = jest.requireActual<
      typeof import("../../src/components/table/TimestampColumnFilterDropdown.tsx")
    >("../../src/components/table/TimestampColumnFilterDropdown.tsx");
    return {
      ...actual,
      TimestampColumnFilterDropdown: () => <div />,
    };
  },
);

jest.mock("../../src/permissions/Require.tsx", () => ({
  Require: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

jest.mock("../../src/components/InlineEdit.tsx", () => ({
  InlineEdit: ({ viewer }: { viewer: React.ReactNode }) => <>{viewer}</>,
}));

jest.mock("../../src/components/table/TextValueEdit.tsx", () => ({
  TextValueEdit: () => <input data-testid="text-value-edit" />,
}));

jest.mock("../../src/components/table/LabelsEdit.tsx", () => ({
  LabelsEdit: () => <span />,
}));

jest.mock("../../src/components/labels/EntityLabels.tsx", () => ({
  EntityLabels: () => <span />,
}));

jest.mock("../../src/permissions/ProtectedDropdown.tsx", () => ({
  ProtectedDropdown: ({
    children,
    menu,
  }: {
    children: React.ReactNode;
    menu?: { items?: { key: string; label: string; onClick?: () => void }[] };
  }) => (
    <div data-testid="protected-dropdown">
      {menu?.items?.map((it) => (
        <button
          key={it.key}
          type="button"
          data-testid={`snapshot-action-${it.key}`}
          onClick={it.onClick}
        >
          {it.label}
        </button>
      ))}
      {children}
    </div>
  ),
}));

jest.mock("../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`}>{name}</span>
  ),
}));

jest.mock("../../src/components/modal/SequenceDiagram.tsx", () => ({
  SequenceDiagram: () => <div data-testid="sequence-diagram-stub" />,
}));

jest.mock("../../src/components/modal/SnapshotXml.tsx", () => ({
  SnapshotXmlView: () => <div data-testid="snapshot-xml-stub" />,
}));

jest.mock("../../src/components/modal/SnapshotsCompare.tsx", () => ({
  SnapshotsCompare: () => <div data-testid="snapshots-compare-stub" />,
}));

jest.mock("../../src/misc/confirm-utils.ts", () => ({
  confirmAndRun: (...args: unknown[]) => mockConfirmAndRun(...args),
}));

function baseSnapshot(id: string, name: string): Snapshot {
  return {
    id,
    name,
    description: "",
    xmlDefinition: "",
    labels: [],
  };
}

function renderSnapshots() {
  return renderPageWithChainHeader(
    <ChainContext.Provider
      value={{
        chain: undefined,
        update: jest.fn().mockResolvedValue(undefined),
        refresh: chainRefreshMock,
      }}
    >
      <Snapshots />
    </ChainContext.Provider>,
  );
}

describe("Snapshots chain header toolbar", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockUseParams.mockReturnValue({ chainId: "chain-1" });
    chainRefreshMock.mockResolvedValue(undefined);
    (api.getSnapshots as jest.Mock).mockResolvedValue([]);
    (api.createSnapshot as jest.Mock).mockResolvedValue(
      baseSnapshot("new-snap", "new"),
    );
    (api.revertToSnapshot as jest.Mock).mockResolvedValue(undefined);
  });

  test("registers header toolbar with search and Compare / Delete / Create", async () => {
    renderSnapshots();

    const slot = await waitFor(() => screen.getByTestId("chain-header-slot"));
    expect(
      within(slot).getByPlaceholderText("Search snapshots..."),
    ).toBeInTheDocument();
    expect(
      within(slot).getByTestId("protected-btn-compare-selected-snapshots"),
    ).toBeInTheDocument();
    expect(
      within(slot).getByTestId("protected-btn-delete-selected-snapshots"),
    ).toBeInTheDocument();
    expect(
      within(slot).getByTestId("protected-btn-create-snapshot"),
    ).toBeInTheDocument();
  });

  test("Create snapshot calls api.createSnapshot", async () => {
    // eslint-disable-next-line @typescript-eslint/unbound-method -- jest.fn on mocked api
    const createSnapshot = api.createSnapshot as jest.Mock;
    renderSnapshots();

    const slot = await waitFor(() => screen.getByTestId("chain-header-slot"));
    fireEvent.click(within(slot).getByTestId("protected-btn-create-snapshot"));

    await waitFor(() => {
      expect(createSnapshot).toHaveBeenCalledWith("chain-1");
    });
    await waitFor(() => {
      expect(chainRefreshMock).toHaveBeenCalled();
    });
  });

  test("Revert to snapshot confirms, calls api, refresh, and navigates to graph", async () => {
    mockConfirmAndRun.mockImplementation(
      ({ onOk }: { onOk: () => void | Promise<void> }) => {
        void onOk();
      },
    );
    (api.getSnapshots as jest.Mock).mockResolvedValue([
      baseSnapshot("snap-rev", "snap-rev"),
    ]);

    renderSnapshots();

    await waitFor(() => {
      expect(screen.getByText("snap-rev")).toBeInTheDocument();
    });

    const row = screen.getByRole("row", { name: /snap-rev/i });
    fireEvent.click(within(row).getByTestId("snapshot-action-revert"));

    await waitFor(() => {
      expect(mockConfirmAndRun).toHaveBeenCalled();
    });
    await waitFor(() => {
      expect(api.revertToSnapshot).toHaveBeenCalledWith("chain-1", "snap-rev");
    });
    await waitFor(() => {
      expect(chainRefreshMock).toHaveBeenCalled();
    });
    expect(mockNavigate).toHaveBeenCalledWith("/chains/chain-1/graph");
  });

  test("Compare disabled unless exactly two rows selected", async () => {
    (api.getSnapshots as jest.Mock).mockResolvedValue([
      baseSnapshot("a", "snap-a"),
      baseSnapshot("b", "snap-b"),
    ]);

    renderSnapshots();

    await waitFor(() => {
      expect(screen.getByText("snap-a")).toBeInTheDocument();
    });

    const slot = await waitFor(() => screen.getByTestId("chain-header-slot"));
    const compareBtn = within(slot).getByTestId(
      "protected-btn-compare-selected-snapshots",
    );
    expect(compareBtn).toBeDisabled();

    const rowA = screen.getByRole("row", { name: /snap-a/i });
    fireEvent.click(within(rowA).getByRole("checkbox"));

    await waitFor(() => {
      expect(
        within(screen.getByTestId("chain-header-slot")).getByTestId(
          "protected-btn-compare-selected-snapshots",
        ),
      ).toBeDisabled();
    });

    const rowB = screen.getByRole("row", { name: /snap-b/i });
    fireEvent.click(within(rowB).getByRole("checkbox"));

    await waitFor(() => {
      expect(
        within(screen.getByTestId("chain-header-slot")).getByTestId(
          "protected-btn-compare-selected-snapshots",
        ),
      ).not.toBeDisabled();
    });
  });

  test("Delete disabled when no rows selected", async () => {
    (api.getSnapshots as jest.Mock).mockResolvedValue([
      baseSnapshot("x", "snap-x"),
    ]);

    renderSnapshots();

    await waitFor(() => {
      expect(screen.getByText("snap-x")).toBeInTheDocument();
    });

    const slot = await waitFor(() => screen.getByTestId("chain-header-slot"));
    expect(
      within(slot).getByTestId("protected-btn-delete-selected-snapshots"),
    ).toBeDisabled();
  });
});
