/**
 * @jest-environment jsdom
 */
import { describe, it, expect, jest, beforeEach } from "@jest/globals";
import "@testing-library/jest-dom";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";

// --- API ---
const mockApi = {
  getCommonVariables: jest.fn<
    () => Promise<{
      success: boolean;
      data: { key: string; value: string }[];
    }>
  >(),
  createCommonVariable:
    jest.fn<(v: unknown) => Promise<{ success: boolean }>>(),
  updateCommonVariable:
    jest.fn<(v: unknown) => Promise<{ success: boolean }>>(),
  deleteCommonVariables: jest.fn<(keys: string[]) => Promise<boolean>>(),
  exportVariables: jest.fn<(keys: string[], flag: boolean) => Promise<Blob>>(),
};
jest.mock("../../../../src/api/api", () => ({ api: mockApi }));

// --- confirmAndRun: run the onOk callback immediately so line 115 executes ---
const mockConfirmAndRun = jest.fn<
  (opts: {
    title: unknown;
    content?: unknown;
    onOk: () => void | Promise<void>;
  }) => void
>(({ onOk }) => {
  void onOk();
});
jest.mock("../../../../src/misc/confirm-utils", () => ({
  confirmAndRun: mockConfirmAndRun,
}));

// --- context-aware message wrapper ---
const mockMessage = { success: jest.fn(), error: jest.fn() };
jest.mock("../../../../src/misc/antd-app", () => ({
  message: mockMessage,
  modal: { confirm: jest.fn() },
}));

// --- antd (real, but with a lightweight Table) ---
jest.mock("antd", () =>
  require("tests/helpers/antdMockWithLightweightTable").antdMockWithLightweightTable(),
);

// --- child components ---
jest.mock(
  "../../../../src/components/admin_tools/variables/VariablesTable",
  () => ({
    __esModule: true,
    default: ({
      variables,
      selectedKeys,
      onSelectedChange,
    }: {
      variables?: { key: string; value: string }[];
      selectedKeys?: string[];
      onSelectedChange?: (keys: string[]) => void;
    }) => (
      <div data-testid="variables-table">
        <div data-testid="variables-count">{variables?.length ?? 0}</div>
        {variables?.map((v) => (
          <div key={v.key} data-testid={`row-${v.key}`}>
            {v.key}
          </div>
        ))}
        <button
          data-testid="mock-select-rows"
          onClick={() => onSelectedChange?.(["ALPHA"])}
        >
          Select rows
        </button>
        <div data-testid="selected-keys">{JSON.stringify(selectedKeys)}</div>
      </div>
    ),
  }),
);

jest.mock(
  "../../../../src/components/admin_tools/variables/ImportVariablesModal",
  () => ({
    __esModule: true,
    default: () => <div data-testid="import-variables-modal" />,
  }),
);

jest.mock("../../../../src/icons/IconProvider", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`} />
  ),
}));

jest.mock("../../../../src/permissions/ProtectedButton", () => ({
  ProtectedButton: ({
    buttonProps,
    tooltipProps,
  }: {
    buttonProps: Record<string, unknown>;
    tooltipProps: { title?: unknown };
  }) => {
    const { iconName: _icon, icon: _iconNode, ...rest } = buttonProps;
    return (
      <button
        type="button"
        data-testid={String(tooltipProps.title)}
        {...rest}
      />
    );
  },
}));

// --- hooks / context ---
const mockNotificationService = {
  requestFailed: jest.fn(),
  info: jest.fn(),
  success: jest.fn(),
};
jest.mock("../../../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => mockNotificationService,
}));

jest.mock("../../../../src/permissions/usePermissions", () => ({
  usePermissions: () => ({
    commonVariable: ["create", "read", "update", "delete", "import", "export"],
  }),
}));

jest.mock("../../../../src/permissions/funcs", () => ({
  hasPermissions: () => true,
}));

jest.mock("../../../../src/Modals", () => ({
  useModalsContext: () => ({ showModal: jest.fn(), closeModal: jest.fn() }),
}));

jest.mock("../../../../src/misc/download-utils", () => ({
  downloadFile: jest.fn(),
}));

import { CommonVariables } from "../../../../src/components/admin_tools/variables/CommonVariables";

describe("CommonVariables", () => {
  beforeEach(() => {
    mockApi.getCommonVariables.mockResolvedValue({
      success: true,
      data: [
        { key: "ALPHA", value: "one" },
        { key: "BETA", value: "two" },
      ],
    });
    mockApi.createCommonVariable.mockResolvedValue({ success: true });
    mockApi.updateCommonVariable.mockResolvedValue({ success: true });
    mockApi.deleteCommonVariables.mockResolvedValue(true);
    mockApi.exportVariables.mockResolvedValue(new Blob(["x"]));
  });

  it("should render the header and variable rows when the API returns variables", async () => {
    render(<CommonVariables />);

    expect(screen.getByText("Common Variables")).toBeInTheDocument();
    expect(screen.getByTestId("icon-table")).toBeInTheDocument();

    await waitFor(() =>
      expect(screen.getByTestId("variables-count")).toHaveTextContent("2"),
    );
    expect(screen.getByTestId("row-ALPHA")).toBeInTheDocument();
    expect(mockApi.getCommonVariables).toHaveBeenCalledTimes(1);
  });

  it("should confirm and delete selected variables when the delete action is triggered", async () => {
    render(<CommonVariables />);

    await waitFor(() =>
      expect(screen.getByTestId("variables-count")).toHaveTextContent("2"),
    );

    // Select a row so the delete action is enabled.
    fireEvent.click(screen.getByTestId("mock-select-rows"));

    // Trigger the delete action -> executes confirmAndRun({...}) (line 115).
    fireEvent.click(screen.getByTestId("Delete selected variables"));

    expect(mockConfirmAndRun).toHaveBeenCalledTimes(1);
    expect(mockConfirmAndRun).toHaveBeenCalledWith(
      expect.objectContaining({
        title: expect.stringContaining("Delete 1 selected variable"),
        onOk: expect.any(Function),
      }),
    );

    // confirmAndRun runs onOk immediately -> onDeleteSelected. Wait for the
    // whole async chain (delete -> success toast -> variables reload) to settle
    // inside act(): the toast and the reload run AFTER the awaited delete, so
    // asserting them outside waitFor would race and leave setState unguarded.
    await waitFor(() =>
      expect(mockApi.deleteCommonVariables).toHaveBeenCalledWith(["ALPHA"]),
    );
    await waitFor(() =>
      expect(mockMessage.success).toHaveBeenCalledWith(
        "Deleted selected variables",
      ),
    );
    await waitFor(() =>
      expect(mockApi.getCommonVariables).toHaveBeenCalledTimes(2),
    );
  });
});
