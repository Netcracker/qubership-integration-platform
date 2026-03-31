/**
 * @jest-environment jsdom
 */

const mockApi = {
  getSecuredVariables: jest.fn(),
  getSecuredVariablesForSecret: jest.fn(),
  createSecret: jest.fn(),
  createSecuredVariables: jest.fn(),
  updateSecuredVariables: jest.fn(),
  deleteSecuredVariables: jest.fn(),
  downloadHelmChart: jest.fn(),
};

jest.mock("../../../../src/api/api", () => ({
  api: mockApi,
}));

jest.mock("antd", () =>
  require("tests/helpers/antdMockWithLightweightTable").antdMockWithLightweightTable(),
);

import React from "react";
import "@testing-library/jest-dom";
import {
  render,
  screen,
  waitFor,
  fireEvent,
  within,
} from "@testing-library/react";
import { SecuredVariables } from "../../../../src/components/admin_tools/variables/SecuredVariables";
import { ProtectedButtonProps } from "../../../../src/permissions/ProtectedButton";
import * as downloadUtils from "../../../../src/misc/download-utils";

// Mock CSS modules
jest.mock(
  "../../../../src/components/admin_tools/variables/SecuredVariables.module.css",
  () => ({}),
);
jest.mock(
  "../../../../src/components/admin_tools/CommonStyle.module.css",
  () => ({}),
);

// Mock child components
jest.mock(
  "../../../../src/components/admin_tools/variables/VariablesTable",
  () => ({
    __esModule: true,
    default: jest.fn(
      ({
        variables,
        onSelectedChange,
        onAdd,
        onDelete,
        onStartEditing,
        onConfirmEdit,
        onCancelEditing,
        selectedKeys,
        editingKey,
        editingValue,
        onChangeEditingValue,
        isAddingNew,
      }) => (
        <div data-testid="variables-table">
          <div data-testid="variables-count">{variables?.length || 0}</div>
          <button
            data-testid="mock-add-variable"
            onClick={() => onAdd?.("mock-key", "mock-value")}
          >
            Add Variable
          </button>
          <button
            data-testid="mock-delete-variable"
            onClick={() => onDelete?.("mock-key")}
          >
            Delete Variable
          </button>
          <button
            data-testid="mock-edit-variable"
            onClick={() => onStartEditing?.("mock-key", "mock-value")}
          >
            Edit Variable
          </button>
          <button
            data-testid="mock-confirm-edit"
            onClick={() => onConfirmEdit?.("mock-key", "updated-value")}
          >
            Confirm Edit
          </button>
          <button data-testid="mock-cancel-edit" onClick={onCancelEditing}>
            Cancel Edit
          </button>
          <input
            data-testid="mock-edit-input"
            value={editingValue || ""}
            onChange={(e) => onChangeEditingValue?.(e.target.value)}
          />
          <div data-testid="selected-keys">{JSON.stringify(selectedKeys)}</div>
          <div data-testid="is-adding-new">{String(isAddingNew)}</div>
        </div>
      ),
    ),
  }),
);

// Mock utility modules
jest.mock("../../../../src/misc/download-utils", () => ({
  downloadFile: jest.fn(),
}));

jest.mock("../../../../src/icons/IconProvider", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`} />
  ),
}));

jest.mock("../../../../src/components/LongActionButton", () => ({
  LongActionButton: ({
    children,
    onSubmit,
  }: {
    children: React.ReactNode;
    onSubmit?: () => void;
  }) => (
    <button data-testid="long-action-button" onClick={onSubmit}>
      {children}
    </button>
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

jest.mock("../../../../src/permissions/Require", () => ({
  Require: ({ children, action, resource }: any) => <>{children}</>,
}));

// Mock hooks
const mockNotificationService = {
  requestFailed: jest.fn(),
};

jest.mock("../../../../src/hooks/useNotificationService", () => ({
  useNotificationService: jest.fn(() => mockNotificationService),
}));

const mockPermissions = {
  securedVariable: ["create", "read", "update", "delete"],
};
jest.mock("../../../../src/permissions/usePermissions", () => ({
  usePermissions: jest.fn(() => mockPermissions),
}));

jest.mock("../../../../src/permissions/funcs", () => ({
  hasPermissions: jest.fn((perms, required) => true),
}));

// Mock ResizeObserver for react-resizable
global.ResizeObserver = jest.fn().mockImplementation(() => ({
  observe: jest.fn(),
  unobserve: jest.fn(),
  disconnect: jest.fn(),
}));

describe("SecuredVariables Component", () => {
  beforeEach(() => {
    jest.clearAllMocks();

    // Default successful API responses
    mockApi.getSecuredVariables.mockResolvedValue({
      success: true,
      data: [
        {
          secretName: "default-secret",
          variables: [
            { key: "DB_PASSWORD", value: "secret123" },
            { key: "API_KEY", value: "key456" },
          ],
          isDefaultSecret: true,
        },
        {
          secretName: "app-secret",
          variables: [{ key: "TOKEN", value: "token789" }],
          isDefaultSecret: false,
        },
      ],
    });

    mockApi.getSecuredVariablesForSecret.mockResolvedValue({
      success: true,
      data: [
        { key: "DB_PASSWORD", value: "secret123" },
        { key: "API_KEY", value: "key456" },
      ],
    });

    mockApi.createSecret.mockResolvedValue({ success: true });
    mockApi.createSecuredVariables.mockResolvedValue({ success: true });
    mockApi.updateSecuredVariables.mockResolvedValue({ success: true });
    mockApi.deleteSecuredVariables.mockResolvedValue({ success: true });
    mockApi.downloadHelmChart.mockResolvedValue(
      new Blob(["test"], { type: "text/plain" }),
    );
  });

  describe("Initial Rendering", () => {
    it("renders the component header with title and icon", () => {
      render(<SecuredVariables />);

      expect(screen.getByTestId("icon-lock")).toBeInTheDocument();
      expect(screen.getByText("Secured Variables")).toBeInTheDocument();
    });

    it("loads and displays secrets on mount", async () => {
      render(<SecuredVariables />);

      await screen.findByText("default-secret");
      await screen.findByText("app-secret");
      expect(mockApi.getSecuredVariables).toHaveBeenCalledTimes(1);
    });

    it('marks the default secret with a "default" tag', async () => {
      render(<SecuredVariables />);

      await screen.findByText("default-secret");
      const defaultSecretRow = screen.getByText("default-secret").closest("tr");
      expect(
        within(defaultSecretRow!).getByText("default"),
      ).toBeInTheDocument();
    });

    it("displays variables table when secret row is expanded", async () => {
      render(<SecuredVariables />);

      await screen.findByText("default-secret");

      getExpandButtonOnDefaultSecret().click();

      await screen.findByTestId("variables-table");
      expect(screen.getByTestId("variables-count")).toHaveTextContent("2");
    });

    it("exports Helm chart for default secret via downloadHelmChart and downloadFile", async () => {
      render(<SecuredVariables />);
      await screen.findByText("default-secret");
      const row = screen.getByText("default-secret").closest("tr");
      expect(row).toBeTruthy();
      fireEvent.click(within(row!).getByTestId("long-action-button"));
      await waitFor(() => {
        expect(mockApi.downloadHelmChart).toHaveBeenCalledWith(
          "default-secret",
        );
        expect(downloadUtils.downloadFile).toHaveBeenCalled();
      });
    });

    it("enables adding new variable when Add variable is clicked on secret row", async () => {
      render(<SecuredVariables />);
      await screen.findByText("default-secret");
      fireEvent.click(getExpandButtonOnDefaultSecret());
      await screen.findByTestId("variables-table");
      const row = screen.getByText("default-secret").closest("tr");
      fireEvent.click(within(row!).getByTestId("Add variable"));
      await waitFor(() => {
        expect(screen.getByTestId("is-adding-new")).toHaveTextContent("true");
      });
    });
  });

  describe("Secret Management", () => {
    it('opens create secret modal when "Create Secret" button is clicked', async () => {
      render(<SecuredVariables />);

      expect(screen.getByText("Secured Variables")).toBeInTheDocument();

      const createButton = screen.getByTestId("Add secret");
      fireEvent.click(createButton);

      expect(
        await screen.findByPlaceholderText("e.g., my-secret"),
      ).toBeInTheDocument();
    });

    it("creates a new secret successfully", async () => {
      render(<SecuredVariables />);

      expect(screen.getByText("Secured Variables")).toBeInTheDocument();

      const createButton = screen.getByTestId("Add secret");
      fireEvent.click(createButton);

      const nameInput = await screen.findByPlaceholderText("e.g., my-secret");
      fireEvent.change(nameInput, { target: { value: "new-secret" } });
      fireEvent.click(screen.getByRole("button", { name: /save/i }));

      await waitFor(() => {
        expect(mockApi.createSecret).toHaveBeenCalledWith("new-secret");
        expect(mockNotificationService.requestFailed).not.toHaveBeenCalled();
      });
    });

    it("handles secret creation failure with notification", async () => {
      const consoleErrorSpy = jest
        .spyOn(console, "error")
        .mockImplementation(() => {});

      mockApi.createSecret.mockResolvedValue({
        success: false,
        error: { responseBody: { errorMessage: "Secret already exists" } },
      });

      try {
        render(<SecuredVariables />);

        expect(screen.getByText("Secured Variables")).toBeInTheDocument();

        const createButton = screen.getByTestId("Add secret");
        fireEvent.click(createButton);

        const nameInput = await screen.findByPlaceholderText("e.g., my-secret");
        fireEvent.change(nameInput, { target: { value: "duplicate-secret" } });
        fireEvent.click(screen.getByRole("button", { name: /save/i }));

        await waitFor(() =>
          expect(mockNotificationService.requestFailed).toHaveBeenCalledWith(
            "Secret already exists",
            null,
          ),
        );
      } finally {
        consoleErrorSpy.mockRestore();
      }
    });

    it("Cancel clears secret name when modal is reopened", async () => {
      render(<SecuredVariables />);

      await screen.findByText("Secured Variables");
      fireEvent.click(screen.getByTestId("Add secret"));

      const dialog = await screen.findByRole("dialog");
      const nameInput = within(dialog).getByPlaceholderText("e.g., my-secret");
      fireEvent.change(nameInput, { target: { value: "tmp-secret" } });
      fireEvent.click(
        within(dialog).getByRole("button", { name: /^cancel$/i }),
      );

      fireEvent.click(screen.getByTestId("Add secret"));
      const dialog2 = await screen.findByRole("dialog");
      expect(
        within(dialog2).getByPlaceholderText("e.g., my-secret"),
      ).toHaveValue("");
    });

    it("Modal close icon resets secret name when reopened", async () => {
      render(<SecuredVariables />);

      await screen.findByText("Secured Variables");
      fireEvent.click(screen.getByTestId("Add secret"));

      const dialog = await screen.findByRole("dialog");
      const nameInput = within(dialog).getByPlaceholderText("e.g., my-secret");
      fireEvent.change(nameInput, { target: { value: "x-secret" } });

      const closeIcon = dialog.querySelector(".ant-modal-close");
      expect(closeIcon).toBeTruthy();
      fireEvent.click(closeIcon as Element);

      fireEvent.click(screen.getByTestId("Add secret"));
      const dialog2 = await screen.findByRole("dialog");
      expect(
        within(dialog2).getByPlaceholderText("e.g., my-secret"),
      ).toHaveValue("");
    });
  });

  describe("Variable Management", () => {
    beforeEach(async () => {
      render(<SecuredVariables />);
      await screen.findByText("default-secret");

      const expandButton = getExpandButtonOnDefaultSecret();
      fireEvent.click(expandButton);
      await screen.findByTestId("variables-table");
    });

    it("adds a new variable to a secret", async () => {
      const addButton = screen.getByTestId("mock-add-variable");
      fireEvent.click(addButton);

      await waitFor(() => {
        expect(mockApi.createSecuredVariables).toHaveBeenCalledWith(
          "default-secret",
          [{ key: "mock-key", value: "mock-value" }],
        );
      });
    });

    it("edits an existing variable value", async () => {
      // Start editing
      const editButton = screen.getByTestId("mock-edit-variable");
      fireEvent.click(editButton);

      // Change value
      const input = screen.getByTestId("mock-edit-input");
      fireEvent.change(input, { target: { value: "updated-value" } });

      // Confirm edit
      const confirmButton = screen.getByTestId("mock-confirm-edit");
      fireEvent.click(confirmButton);

      await waitFor(() => {
        expect(mockApi.updateSecuredVariables).toHaveBeenCalledWith(
          "default-secret",
          [{ key: "mock-key", value: "updated-value" }],
        );
      });
    });

    it("deletes a variable", async () => {
      const deleteButton = screen.getByTestId("mock-delete-variable");
      fireEvent.click(deleteButton);

      await waitFor(() => {
        expect(mockApi.deleteSecuredVariables).toHaveBeenCalledWith(
          "default-secret",
          ["mock-key"],
        );
      });
    });

    it("handles variable operation failures with notifications", async () => {
      mockApi.createSecuredVariables.mockResolvedValue({
        success: false,
        error: { responseBody: { errorMessage: "Validation failed" } },
      });

      const addButton = screen.getByTestId("mock-add-variable");
      fireEvent.click(addButton);

      await waitFor(() => {
        expect(mockNotificationService.requestFailed).toHaveBeenCalledWith(
          "Validation failed",
          null,
        );
      });
    });
  });
});

function getExpandButtonOnDefaultSecret() {
  const row = screen.getByText("default-secret").closest("tr");
  if (!row) {
    throw new Error("default-secret row not found");
  }
  const fromAntCell = row.querySelector(
    ".ant-table-row-expand-icon-cell button",
  ) as HTMLButtonElement | null;
  if (fromAntCell) {
    return fromAntCell;
  }
  const icon =
    within(row).queryByTestId("icon-right") ??
    within(row).queryByTestId("icon-down");
  const btn = icon?.closest("button");
  if (!btn) {
    throw new Error("expand button not found for default-secret row");
  }
  return btn as HTMLButtonElement;
}
