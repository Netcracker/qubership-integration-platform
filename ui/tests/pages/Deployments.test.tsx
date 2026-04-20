/**
 * @jest-environment jsdom
 *
 * Tests `Deployments` page chain header toolbar via full page render (toolbar is inlined).
 */

import React from "react";
import { screen, waitFor, fireEvent, within } from "@testing-library/react";
import "@testing-library/jest-dom";
import { Deployments } from "../../src/pages/Deployments.tsx";
import { renderPageWithChainHeader } from "../helpers/renderWithChainHeader.tsx";

const mockUseParams = jest.fn(() => ({ chainId: "chain-1" }));

jest.mock("react-router", () => ({
  useParams: () => mockUseParams(),
}));

const mockSetDeployments = jest.fn();
const mockRemoveDeployment = jest.fn();

jest.mock("../../src/hooks/useDeployments.tsx", () => ({
  useDeployments: () => ({
    isLoading: false,
    deployments: [],
    setDeployments: mockSetDeployments,
    removeDeployment: mockRemoveDeployment,
  }),
}));

jest.mock("../../src/hooks/useSnapshots.tsx", () => ({
  useSnapshots: () => ({
    isLoading: false,
    snapshots: [],
    setSnapshots: jest.fn(),
  }),
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
  LongActionButton: ({ children }: { children: React.ReactNode }) => (
    <button type="button">{children}</button>
  ),
}));

jest.mock("../../src/api/api.ts", () => ({
  api: {
    deleteDeployment: jest.fn(),
    createDeployment: jest.fn(),
  },
}));

function renderDeployments() {
  return renderPageWithChainHeader(<Deployments />);
}

describe("Deployments chain header toolbar", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockUseParams.mockReturnValue({ chainId: "chain-1" });
  });

  test("registers header toolbar with search and Create deployment", async () => {
    renderDeployments();

    const slot = await waitFor(() => screen.getByTestId("chain-header-slot"));
    expect(
      within(slot).getByPlaceholderText("Search deployments..."),
    ).toBeInTheDocument();
    expect(
      within(slot).getByTestId("protected-btn-create-deployment"),
    ).toBeInTheDocument();
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
    fireEvent.click(
      within(slot).getByTestId("protected-btn-create-deployment"),
    );

    expect(showModal).toHaveBeenCalledTimes(1);
  });
});
