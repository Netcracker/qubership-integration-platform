/**
 * @jest-environment jsdom
 */

import {
  describe,
  it,
  expect,
  beforeEach,
  afterEach,
  jest,
} from "@jest/globals";
import { fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { AccessControl, AccessControlType } from "../../../../src/api/apiTypes";

const mockShowModal = jest.fn();
const mockNavigate = jest.fn();

jest.mock("antd", () =>
  require("tests/helpers/antdMockWithLightweightTable").antdMockWithLightweightTable(),
);
jest.mock("../../../../src/Modals", () => ({
  useModalsContext: () => ({ showModal: mockShowModal }),
}));
jest.mock("react-router", () => ({
  useNavigate: () => mockNavigate,
}));
jest.mock("../../../../src/icons/IconProvider", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`} />
  ),
}));
jest.mock(
  "../../../../src/components/deployment_runtime_states/DeploymentsCumulativeState",
  () => ({
    DeploymentsCumulativeState: ({
      chainId,
      isNotificationEnabled,
    }: {
      chainId: string;
      isNotificationEnabled: boolean;
    }) => (
      <span data-testid="chain-status">
        {chainId}:{String(isNotificationEnabled)}
      </span>
    ),
    getCumulativeStatus: (statuses: Set<string>) =>
      statuses.has("DEPLOYED") ? "DEPLOYED" : "DRAFT",
  }),
);
jest.mock(
  "../../../../src/components/admin_tools/access-control/AbacAttributesPopUp",
  () => ({
    AbacAttributesPopUp: () => <div data-testid="abac-popup" />,
  }),
);

import { AccessControlSidebar } from "../../../../src/components/admin_tools/access-control/AccessControlSidebar";

function makeRecord(
  property: Record<string, unknown> = {},
  overrides: Partial<AccessControl> = {},
): AccessControl {
  return {
    elementId: "elem-1",
    elementName: "Element Name",
    chainId: "chain-1",
    chainName: "Test Chain",
    deploymentStatus: ["DEPLOYED"],
    unsavedChanges: false,
    modifiedWhen: 1700000000000,
    properties: {
      contextPath: "/test",
      externalRoute: true,
      privateRoute: false,
      accessControlType: AccessControlType.RBAC,
      roles: ["admin", "user"],
      ...property,
    } as unknown as AccessControl["properties"],
    ...overrides,
  };
}

function renderSidebar(
  value: AccessControl | null,
  open = true,
  onClose: () => void = jest.fn(),
  deployedChainIds = new Set<string>(),
) {
  return render(
    <AccessControlSidebar
      value={value}
      open={open}
      onClose={onClose}
      deployedChainIds={deployedChainIds}
    />,
  );
}

let pageContent: HTMLElement;

describe("AccessControlSidebar", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    pageContent = document.createElement("div");
    pageContent.className = "page-content";
    document.body.appendChild(pageContent);
  });

  afterEach(() => {
    pageContent.remove();
  });

  it("should render nothing when closed", () => {
    renderSidebar(makeRecord(), false);

    expect(screen.queryByText("Endpoint Details")).not.toBeInTheDocument();
  });

  it("should render the drawer inside the page content when open", () => {
    renderSidebar(makeRecord());

    expect(pageContent).toContainElement(screen.getByText("Endpoint Details"));
  });

  it("should render labeled details of the endpoint", () => {
    renderSidebar(makeRecord());

    for (const label of [
      "Endpoint",
      "Type",
      "Access Control Type",
      "Roles",
      "Attributes",
      "Chain",
      "Chain Status",
      "Chain ID",
    ]) {
      expect(screen.getByText(label)).toBeInTheDocument();
    }
    expect(screen.getByText("/test")).toHaveAttribute(
      "href",
      "/chains/chain-1/graph/elem-1",
    );
    expect(screen.getByText("External")).toBeInTheDocument();
    expect(screen.getByText("RBAC")).toBeInTheDocument();
    expect(screen.getByText("admin")).toBeInTheDocument();
    expect(screen.getByText("user")).toBeInTheDocument();
    expect(screen.getByText("Test Chain")).toBeInTheDocument();
    expect(screen.getByText("chain-1")).toBeInTheDocument();
  });

  it.each([
    [true, true, "External, Private"],
    [false, true, "Private"],
    [false, false, "Internal"],
  ])(
    "should show route type when external is %s and private is %s",
    (externalRoute, privateRoute, expected) => {
      renderSidebar(makeRecord({ externalRoute, privateRoute }));

      expect(screen.getByText(expected)).toBeInTheDocument();
    },
  );

  it("should show unsaved changes tag when a deployed record has unsaved changes", () => {
    renderSidebar(makeRecord({}, { unsavedChanges: true }));

    expect(screen.getByText("Unsaved Changes")).toBeInTheDocument();
  });

  it("should hide unsaved changes tag when the chain is not deployed", () => {
    renderSidebar(
      makeRecord({}, { unsavedChanges: true, deploymentStatus: [] }),
    );

    expect(screen.queryByText("Unsaved Changes")).not.toBeInTheDocument();
  });

  it("should open the attributes popup when access control type is ABAC", () => {
    renderSidebar(makeRecord({ accessControlType: AccessControlType.ABAC }));

    fireEvent.click(screen.getByText("Details"));

    expect(mockShowModal).toHaveBeenCalledTimes(1);
  });

  it("should not offer attribute details when access control type is RBAC", () => {
    renderSidebar(makeRecord());

    expect(screen.queryByText("Details")).not.toBeInTheDocument();
  });

  it("should navigate to the chain when the chain link is clicked", () => {
    renderSidebar(makeRecord());

    fireEvent.click(screen.getByText("Test Chain"));

    expect(mockNavigate).toHaveBeenCalledWith("/chains/chain-1");
  });

  it("should disable chain status notifications when the chain is deployed", () => {
    renderSidebar(makeRecord(), true, jest.fn(), new Set(["chain-1"]));

    expect(screen.getByTestId("chain-status")).toHaveTextContent(
      "chain-1:false",
    );
  });

  it("should show placeholders when no record is selected", () => {
    renderSidebar(null);

    expect(screen.getByText("Endpoint Details")).toBeInTheDocument();
    expect(screen.getByText("Internal")).toBeInTheDocument();
    expect(screen.getAllByText("—")).toHaveLength(7);
  });

  it("should call onClose when the close button is clicked", () => {
    const onClose = jest.fn();
    renderSidebar(makeRecord(), true, onClose);

    fireEvent.click(screen.getByRole("button", { name: /close/i }));

    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
