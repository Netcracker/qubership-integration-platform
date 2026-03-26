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
  ProtectedButton: ({ buttonProps, tooltipProps }: ProtectedButtonProps) => (
    <button data-testid={tooltipProps.title} {...buttonProps} />
  ),
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

      await waitFor(() => {
        expect(mockApi.getSecuredVariables).toHaveBeenCalledTimes(1);
        expect(screen.getByText("default-secret")).toBeInTheDocument();
        expect(screen.getByText("app-secret")).toBeInTheDocument();
      });
    });

    it('marks the default secret with a "default" tag', async () => {
      render(<SecuredVariables />);

      await waitFor(() => {
        const defaultSecretRow = screen
          .getByText("default-secret")
          .closest("tr");
        expect(
          within(defaultSecretRow!).getByText("default"),
        ).toBeInTheDocument();
      });
    });

    it("displays variables table when secret row is expanded", async () => {
      render(<SecuredVariables />);

      await waitFor(() => screen.getByText("default-secret"));

      const expandButton = getExpandButtonOnDefaultSecret();
      expandButton.click();

      await waitFor(() => {
        expect(screen.getByTestId("variables-table")).toBeInTheDocument();
        expect(screen.getByTestId("variables-count")).toHaveTextContent("2");
      });
    });
  });

  describe("Secret Management", () => {
    it('opens create secret modal when "Create Secret" button is clicked', async () => {
      render(<SecuredVariables />);

      await waitFor(() => screen.getByText("Secured Variables"));

      const createButton = screen.getByTestId("Add secret");
      fireEvent.click(createButton);

      await waitFor(() => {
        expect(screen.getByRole("textbox")).toBeInTheDocument();
      });
    });

    it("creates a new secret successfully", async () => {
      render(<SecuredVariables />);

      await waitFor(() => screen.getByText("Secured Variables"));

      const createButton = screen.getByTestId("Add secret");
      fireEvent.click(createButton);

      await waitFor(() => {
        const input = screen.getByRole("textbox");
        fireEvent.change(input, { target: { value: "new-secret" } });

        const submitButton = screen.getByRole("button", { name: /create/i });
        fireEvent.click(submitButton);
      });

      await waitFor(() => {
        expect(mockApi.createSecret).toHaveBeenCalledWith("new-secret");
        expect(mockNotificationService.requestFailed).not.toHaveBeenCalled();
      });
    });

    it("handles secret creation failure with notification", async () => {
      mockApi.createSecret.mockResolvedValue({
        success: false,
        error: { responseBody: { errorMessage: "Secret already exists" } },
      });

      render(<SecuredVariables />);

      await waitFor(() => screen.getByText("Secured Variables"));

      const createButton = screen.getByTestId("Add secret");
      fireEvent.click(createButton);

      await waitFor(() => {
        const input = screen.getByRole("textbox");
        fireEvent.change(input, { target: { value: "duplicate-secret" } });

        const submitButton = screen.getByRole("button", { name: /create/i });
        fireEvent.click(submitButton);
      });

      await waitFor(() => {
        expect(mockNotificationService.requestFailed).toHaveBeenCalledWith(
          "Secret already exists",
          null,
        );
      });
    });
  });

  describe("Variable Management", () => {
    beforeEach(async () => {
      render(<SecuredVariables />);
      await waitFor(() => screen.getByText("default-secret"));

      // Expand the default secret row
      const expandButton = getExpandButtonOnDefaultSecret();
      fireEvent.click(expandButton);
      await waitFor(() => screen.getByTestId("variables-table"));
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
  const allButtons = screen.getAllByRole("button");

  return allButtons.find((button) =>
    button.closest(".ant-table-row-expand-icon-cell"),
  )!;
}
