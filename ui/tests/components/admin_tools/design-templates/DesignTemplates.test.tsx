/**
 * @jest-environment jsdom
 */
import { describe, it, expect, jest } from "@jest/globals";

const mockApi = {
  getDetailedDesignTemplates: jest.fn<() => Promise<unknown>>(),
  getDetailedDesignTemplate: jest.fn<() => Promise<unknown>>(),
  deleteDetailedDesignTemplates: jest.fn<() => Promise<void>>(),
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
import { DesignTemplates } from "../../../../src/components/admin_tools/design-templates/DesignTemplates";
import { confirmAndRun } from "../../../../src/misc/confirm-utils";
import { ProtectedButtonProps } from "../../../../src/permissions/ProtectedButton";

// confirmAndRun runs its onOk immediately so the delete path executes.
jest.mock("../../../../src/misc/confirm-utils", () => ({
  confirmAndRun: jest.fn(({ onOk }: { onOk?: () => void | Promise<void> }) =>
    onOk?.(),
  ),
}));

// Avoid pulling the create-modal's antd Upload/Dragger deps into the render.
jest.mock(
  "../../../../src/components/admin_tools/design-templates/CreateDesignTemplateModal",
  () => ({
    CreateDesignTemplateModal: () => (
      <div data-testid="create-design-template-modal" />
    ),
  }),
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

const mockNotificationService = {
  requestFailed: jest.fn(),
};

jest.mock("../../../../src/hooks/useNotificationService", () => ({
  useNotificationService: jest.fn(() => mockNotificationService),
}));

jest.mock("../../../../src/Modals", () => ({
  useModalsContext: jest.fn(() => ({
    showModal: jest.fn(),
    closeModal: jest.fn(),
  })),
}));

const customTemplate = {
  id: "tpl-custom",
  name: "Custom Template",
  description: "",
  builtIn: false,
  createdWhen: 1700000000000,
};

const builtInTemplate = {
  id: "tpl-builtin",
  name: "Built-in Template",
  description: "",
  builtIn: true,
  createdWhen: 1700000001000,
};

describe("DesignTemplates Component", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockApi.getDetailedDesignTemplates.mockResolvedValue([
      customTemplate,
      builtInTemplate,
    ]);
    mockApi.deleteDetailedDesignTemplates.mockResolvedValue(undefined);
  });

  it("should render the templates table when templates are loaded", async () => {
    render(<DesignTemplates />);

    expect(screen.getByText("Design Templates")).toBeInTheDocument();
    expect(await screen.findByText("Custom Template")).toBeInTheDocument();
    expect(screen.getByText("Built-in Template")).toBeInTheDocument();
    expect(mockApi.getDetailedDesignTemplates).toHaveBeenCalledWith(false);
  });

  it("should call confirmAndRun when the delete action is triggered for a selected row", async () => {
    render(<DesignTemplates />);

    const row = (await screen.findByText("Custom Template")).closest("tr");
    expect(row).toBeTruthy();

    // Selecting a non-built-in row enables the delete action.
    fireEvent.click(within(row!).getByRole("checkbox"));

    const deleteButton = screen.getByTestId("Delete selected templates");
    await waitFor(() => expect(deleteButton).not.toBeDisabled());
    fireEvent.click(deleteButton);

    expect(confirmAndRun).toHaveBeenCalledTimes(1);
    await waitFor(() =>
      expect(mockApi.deleteDetailedDesignTemplates).toHaveBeenCalledWith([
        "tpl-custom",
      ]),
    );
    // handleDelete removes the row after the awaited delete resolves; wait for
    // the settled UI so that post-await setState runs inside act() and the
    // delete's real effect (row gone) is actually verified.
    await waitFor(() =>
      expect(screen.queryByText("Custom Template")).not.toBeInTheDocument(),
    );
  });
});
