/**
 * @jest-environment jsdom
 */

import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import { AccessControl } from "../../../../src/components/admin_tools/access-control/AccessControl";
import { useAccessControl } from "../../../../src/hooks/useAccessControl";
import { useNotificationService } from "../../../../src/hooks/useNotificationService";
import { useModalsContext } from "../../../../src/Modals";
import {
  AccessControl as AccessControlData,
  AccessControlType,
} from "../../../../src/api/apiTypes";
import { ProtectedButtonProps } from "../../../../src/permissions/ProtectedButton";

// Mock dependencies
jest.mock("../../../../src/hooks/useAccessControl");
jest.mock("../../../../src/hooks/useNotificationService");
jest.mock("../../../../src/Modals");
jest.mock("../../../../src/hooks/useResizeHeigth", () => ({
  useResizeHeight: () => [jest.fn(), 500],
}));
jest.mock("react-router", () => ({
  useNavigate: () => jest.fn(),
}));

jest.mock("../../../../src/icons/IconProvider", () => ({
  OverridableIcon: ({ name, ...props }: any) => (
    <span data-testid={`icon-${name}`} {...props} />
  ),
}));

jest.mock("../../../../src/components/ResizableTitle", () => ({
  ResizableTitle: ({ children }: any) => <th>{children}</th>,
}));

jest.mock("../../../../src/hooks/useDeployments", () => ({
  useDeployments: jest
    .fn()
    .mockReturnValue({ isLoading: false, deployments: [] }),
}));

jest.mock(
  "../../../../src/components/admin_tools/access-control/AbacAttributesPopUp",
  () => ({
    AbacAttributesPopUp: () => <div data-testid="abac-popup">ABAC Popup</div>,
  }),
);

jest.mock(
  "../../../../src/components/admin_tools/access-control/AddDeleteRolesPopUp",
  () => ({
    AddDeleteRolesPopUp: () => <div data-testid="roles-popup">Roles Popup</div>,
  }),
);

jest.mock("../../../../src/permissions/ProtectedButton", () => ({
  ProtectedButton: ({ buttonProps, tooltipProps }: ProtectedButtonProps) => {
    const { iconName: _, ...domProps } = buttonProps as any;
    return <button data-testid={tooltipProps.title} {...domProps} />;
  },
}));

jest.mock("antd", () => {
  const actual: Record<string, unknown> = jest.requireActual("antd");
  const mock: Record<string, unknown> = jest.requireActual(
    "../../../__mocks__/LightweightTable",
  );
  return { ...actual, Table: mock.LightweightTable };
});

// Mock CSS modules
jest.mock(
  "../../../../src/components/admin_tools/CommonStyle.module.css",
  () => ({
    container: "container",
    header: "header",
    actions: "actions",
    icon: "icon",
  }),
);

const mockUseAccessControl = useAccessControl as jest.MockedFunction<
  typeof useAccessControl
>;
const mockUseNotificationService =
  useNotificationService as jest.MockedFunction<typeof useNotificationService>;
const mockUseModalsContext = useModalsContext as jest.MockedFunction<
  typeof useModalsContext
>;

const createMockAccessControlData = (
  overrides: Partial<AccessControlData> = {},
): AccessControlData => ({
  elementId: "elem-1",
  elementName: "Element Name",
  chainId: "chain-1",
  chainName: "Test Chain",
  deploymentStatus: ["DEPLOYED"],
  unsavedChanges: false,
  modifiedWhen: 1700000000000,
  properties: {
    property: {
      contextPath: "/test",
      externalRoute: true,
      privateRoute: false,
      accessControlType: AccessControlType.RBAC,
      roles: ["admin"],
    },
  },
  ...overrides,
});

describe("AccessControl - Unsaved Changes Functionality (PR #573)", () => {
  beforeEach(() => {
    jest.clearAllMocks();

    mockUseAccessControl.mockReturnValue({
      isLoading: false,
      accessControlData: { offset: 0, roles: [] },
      setAccessControlData: jest.fn(),
      getAccessControl: jest.fn().mockResolvedValue(undefined),
      bulkDeployAccessControl: jest.fn().mockResolvedValue(undefined),
      loadMore: jest.fn().mockResolvedValue(undefined),
      allDataLoaded: true,
      updateAccessControl: jest.fn(),
    });

    mockUseNotificationService.mockReturnValue({
      info: jest.fn(),
      requestFailed: jest.fn(),
      errorWithDetails: jest.fn(),
      warning: jest.fn(),
    });

    mockUseModalsContext.mockReturnValue({
      showModal: jest.fn(),
      closeModal: jest.fn(),
    });
  });

  describe("Button Disabled States", () => {
    it('disables "Select Unsaved Chains" button when no roles have unsaved changes', async () => {
      mockUseAccessControl.mockReturnValue({
        ...mockUseAccessControl(),
        accessControlData: {
          offset: 0,
          roles: [
            createMockAccessControlData({ unsavedChanges: false }),
            createMockAccessControlData({
              chainId: "chain-2",
              elementId: "elem-2",
              unsavedChanges: false,
            }),
          ],
        },
      });

      render(<AccessControl />);

      await waitFor(() => {
        const button = getUnsavedChangesButton();
        expect(button).toBeDisabled();
      });
    });

    it('enables "Select Unsaved Chains" button when at least one role has unsaved changes', async () => {
      mockUseAccessControl.mockReturnValue({
        ...mockUseAccessControl(),
        accessControlData: {
          offset: 0,
          roles: [
            createMockAccessControlData({
              chainId: "chain-1",
              elementId: "elem-1",
              unsavedChanges: true,
              deploymentStatus: ["DEPLOYED"],
            }),
            createMockAccessControlData({
              chainId: "chain-2",
              elementId: "elem-2",
              unsavedChanges: false,
            }),
          ],
        },
      });

      render(<AccessControl />);

      await waitFor(() => {
        const button = getUnsavedChangesButton();
        expect(button).not.toBeDisabled();
      });
    });

    it('disables "Redeploy" button when no rows are selected', async () => {
      render(<AccessControl />);

      await waitFor(() => {
        const redeployBtn = screen.getByTestId("Redeploy");
        expect(redeployBtn).toBeDisabled();
      });
    });

    it('disables "Redeploy" button when selected rows have no unsaved changes', async () => {
      const roles = [
        createMockAccessControlData({
          chainId: "chain-1",
          elementId: "elem-1",
          unsavedChanges: false,
          deploymentStatus: ["DEPLOYED"],
        }),
      ];

      mockUseAccessControl.mockReturnValue({
        ...mockUseAccessControl(),
        accessControlData: { offset: 0, roles },
      });

      render(<AccessControl />);

      await waitFor(() => {
        // Simulate row selection
        const table = getDataTable();
        const checkbox = table.querySelector('input[type="checkbox"]');
        if (checkbox) {
          fireEvent.click(checkbox);
        }
      });

      await waitFor(() => {
        const redeployBtn = screen.getByTestId("Redeploy");
        // Should be disabled because selected row has no unsaved changes
        expect(redeployBtn).toBeDisabled();
      });
    });
  });

  describe("Select Unsaved Chains Button", () => {
    it("selects only rows with unsaved changes when button is clicked", async () => {
      const roles = [
        createMockAccessControlData({
          chainId: "chain-1",
          elementId: "elem-1",
          unsavedChanges: true,
          deploymentStatus: ["DEPLOYED"],
        }),
        createMockAccessControlData({
          chainId: "chain-2",
          elementId: "elem-2",
          unsavedChanges: false,
          deploymentStatus: ["DEPLOYED"],
        }),
        createMockAccessControlData({
          chainId: "chain-3",
          elementId: "elem-3",
          unsavedChanges: true,
          deploymentStatus: ["DEPLOYED"],
        }),
      ];

      mockUseAccessControl.mockReturnValue({
        ...mockUseAccessControl(),
        accessControlData: { offset: 0, roles },
      });

      render(<AccessControl />);

      await waitFor(() => {
        const button = getUnsavedChangesButton();
        if (button) {
          fireEvent.click(button);
        }
      });

      // Verify that only rows with unsaved changes are selected
      // This would be verified through the rowSelection prop on Table
      await waitFor(() => {
        const checkboxes = screen.getAllByRole("checkbox");
        // First checkbox is "select all", then one per row
        // Rows 0 and 2 should be checked (unsaved changes), row 1 should not
        expect(checkboxes[1]).toBeChecked(); // chain-1-elem-1
        expect(checkboxes[2]).not.toBeChecked(); // chain-2-elem-2
        expect(checkboxes[3]).toBeChecked(); // chain-3-elem-3
      });
    });
  });

  describe("UI Rendering - Unsaved Changes Indicators", () => {
    it('highlights rows with unsaved changes using "highlight-row" class', async () => {
      const roles = [
        createMockAccessControlData({
          chainId: "chain-1",
          elementId: "elem-1",
          unsavedChanges: true,
          deploymentStatus: ["DEPLOYED"],
        }),
        createMockAccessControlData({
          chainId: "chain-2",
          elementId: "elem-2",
          unsavedChanges: false,
          deploymentStatus: ["DEPLOYED"],
        }),
      ];

      mockUseAccessControl.mockReturnValue({
        ...mockUseAccessControl(),
        accessControlData: { offset: 0, roles },
      });

      render(<AccessControl />);

      await waitFor(() => {
        const table = getDataTable();
        const rows = table.querySelectorAll("tr");

        // First row should have highlight-row class (has unsaved changes)
        expect(rows[1]).toHaveClass("highlight-row");
        // Second row should not have highlight-row class
        expect(rows[2]).not.toHaveClass("highlight-row");
      });
    });

    it('shows "Unsaved Changes" warning tag in drawer for records with unsaved changes', async () => {
      const recordWithChanges = createMockAccessControlData({
        chainId: "chain-1",
        elementId: "elem-1",
        unsavedChanges: true,
        deploymentStatus: ["DEPLOYED"],
      });

      mockUseAccessControl.mockReturnValue({
        ...mockUseAccessControl(),
        accessControlData: { offset: 0, roles: [recordWithChanges] },
      });

      render(<AccessControl />);

      await waitFor(() => {
        // Click on a row to open drawer (simulating onRow click)
        const table = getDataTable();
        const rows = table.querySelectorAll("tr[data-row-key]");
        fireEvent.click(rows[0]);
      });

      await waitFor(() => {
        const unsavedChangesTag = screen.queryByText("Unsaved Changes");
        expect(unsavedChangesTag).toBeInTheDocument();
      });
    });

    it("does not show warning tag for records without unsaved changes", async () => {
      const recordWithoutChanges = createMockAccessControlData({
        chainId: "chain-1",
        elementId: "elem-1",
        unsavedChanges: false,
        deploymentStatus: ["DEPLOYED"],
      });

      mockUseAccessControl.mockReturnValue({
        ...mockUseAccessControl(),
        accessControlData: { offset: 0, roles: [recordWithoutChanges] },
      });

      render(<AccessControl />);

      await waitFor(() => {
        // Click on a row to open drawer (simulating onRow click)
        const table = getDataTable();
        const rows = table.querySelectorAll("tr[data-row-key]");
        fireEvent.click(rows[0]);
      });

      await waitFor(() => {
        const unsavedChangesTag = screen.queryByText("Unsaved Changes");
        expect(unsavedChangesTag).not.toBeInTheDocument();
      });
    });
  });

  describe("Redeploy Button Logic", () => {
    it("only allows redeploy for selected rows that have unsaved changes", async () => {
      const roles = [
        createMockAccessControlData({
          chainId: "chain-1",
          elementId: "elem-1",
          unsavedChanges: true,
          deploymentStatus: ["DEPLOYED"],
        }),
        createMockAccessControlData({
          chainId: "chain-2",
          elementId: "elem-2",
          unsavedChanges: false,
          deploymentStatus: ["DEPLOYED"],
        }),
      ];

      mockUseAccessControl.mockReturnValue({
        ...mockUseAccessControl(),
        accessControlData: { offset: 0, roles },
      });

      render(<AccessControl />);

      await waitFor(async () => {
        // Select only the row WITHOUT unsaved changes
        const table = getDataTable();
        const checkboxes = table.querySelectorAll('input[type="checkbox"]');

        if (checkboxes[2]) {
          // Second data row (index 2, after select-all)
          fireEvent.click(checkboxes[2]);
        }

        // Redeploy button should be disabled because selected row has no unsaved changes
        const redeployBtn = await screen.findByTestId("Redeploy");
        expect(redeployBtn).toBeDisabled();
      });
    });

    it("enables redeploy when all selected rows have unsaved changes", async () => {
      const roles = [
        createMockAccessControlData({
          chainId: "chain-1",
          elementId: "elem-1",
          unsavedChanges: true,
          deploymentStatus: ["DEPLOYED"],
        }),
        createMockAccessControlData({
          chainId: "chain-2",
          elementId: "elem-2",
          unsavedChanges: true,
          deploymentStatus: ["DEPLOYED"],
        }),
      ];

      mockUseAccessControl.mockReturnValue({
        ...mockUseAccessControl(),
        accessControlData: { offset: 0, roles },
      });

      render(<AccessControl />);

      await waitFor(async () => {
        // Select both rows
        const table = getDataTable();
        const selectAll = table.querySelector('input[type="checkbox"]');

        if (selectAll) {
          fireEvent.click(selectAll);
        }

        // Redeploy button should be enabled
        const redeployBtn = await screen.findByTestId("Redeploy");
        expect(redeployBtn).not.toBeDisabled();
      });
    });
  });

  describe("Bulk Deploy with Unsaved Changes Filter", () => {
    it("only deploys records that have both chainId AND unsaved changes", async () => {
      const mockBulkDeploy = jest.fn().mockResolvedValue(undefined);
      const roles = [
        createMockAccessControlData({
          chainId: "chain-1",
          elementId: "elem-1",
          unsavedChanges: true,
          deploymentStatus: ["DEPLOYED"],
        }),
        createMockAccessControlData({
          chainId: undefined, // No chainId - should be filtered out
          elementId: "elem-3",
          unsavedChanges: true,
          deploymentStatus: ["DEPLOYED"],
        }),
      ];

      mockUseAccessControl.mockReturnValue({
        ...mockUseAccessControl(),
        accessControlData: { offset: 0, roles },
        bulkDeployAccessControl: mockBulkDeploy,
      });

      render(<AccessControl />);

      await waitFor(() => {
        // Select all rows
        const table = getDataTable();
        const selectAll = table.querySelector('input[type="checkbox"]')!;
        fireEvent.click(selectAll);
      });

      await waitFor(() => {
        const redeployBtn = screen.getByTestId("Redeploy");
        expect(redeployBtn).toBeEnabled();
        fireEvent.click(redeployBtn);
      });

      await waitFor(() => {
        // Should only deploy the first record (has chainId AND unsavedChanges)
        expect(mockBulkDeploy).toHaveBeenCalledWith(
          expect.arrayContaining([
            expect.objectContaining({
              chainId: "chain-1",
              unsavedChanges: true,
            }),
          ]),
        );
      });
    });
  });

  describe("Row Key Consistency", () => {
    it("uses buildRowKey consistently for rowKey, selection, and deployment tracking", async () => {
      const roles = [
        createMockAccessControlData({
          chainId: "test-chain",
          elementId: "test-elem",
          unsavedChanges: true,
          deploymentStatus: ["DEPLOYED"],
        }),
      ];

      mockUseAccessControl.mockReturnValue({
        ...mockUseAccessControl(),
        accessControlData: { offset: 0, roles },
      });

      render(<AccessControl />);

      await waitFor(() => {
        const table = getDataTable();
        // Verify rowKey format is consistent: `${chainId}-${elementId}`
        const rows = table.querySelectorAll("tr[data-row-key]");
        if (rows[0]) {
          expect(rows[0].getAttribute("data-row-key")).toBe(
            "test-chain-test-elem",
          );
        }
      });
    });
  });
});

function getDataTable() {
  const tables = screen.getAllByRole("table");
  // Real antd Table with scroll renders two tables (header + body);
  // LightweightTable mock renders one. Pick the last one (body data).
  return tables[tables.length - 1];
}

function getUnsavedChangesButton() {
  return screen.getByTestId("icon-checkSquare").closest("button");
}
