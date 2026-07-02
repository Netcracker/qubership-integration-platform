/**
 * @jest-environment jsdom
 */
import { describe, it, expect, jest } from "@jest/globals";
import "@testing-library/jest-dom";
import { render, screen, fireEvent } from "@testing-library/react";

const mockApi = {
  importVariablesPreview: jest.fn(),
  importVariables: jest.fn(),
};

jest.mock("../../../../src/api/api", () => ({
  api: mockApi,
}));

jest.mock("antd", () =>
  require("tests/helpers/antdMockWithLightweightTable").antdMockWithLightweightTable(),
);

jest.mock("../../../../src/misc/antd-app", () => ({
  message: {
    warning: jest.fn(),
    success: jest.fn(),
    error: jest.fn(),
  },
  modal: {},
}));

jest.mock("../../../../src/ModalContextProvider", () => ({
  useModalContext: () => ({ closeContainingModal: jest.fn() }),
}));

const mockNotificationService = {
  requestFailed: jest.fn(),
  errorWithDetails: jest.fn(),
};

jest.mock("../../../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => mockNotificationService,
}));

jest.mock("../../../../src/icons/IconProvider", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`} />
  ),
}));

import ImportVariablesModal from "../../../../src/components/admin_tools/variables/ImportVariablesModal";
import { message } from "../../../../src/misc/antd-app";

describe("ImportVariablesModal", () => {
  it("should render the import dialog when mounted", () => {
    render(<ImportVariablesModal />);

    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(screen.getByText("Import variables")).toBeInTheDocument();
  });

  it("should render the upload control and action buttons when mounted", () => {
    render(<ImportVariablesModal />);

    expect(
      screen.getByText("Drag a YAML file or click to choose"),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Preview" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Import" })).toBeInTheDocument();
  });

  it("should warn and skip the preview request when Preview is clicked without a file", () => {
    render(<ImportVariablesModal />);

    fireEvent.click(screen.getByRole("button", { name: "Preview" }));

    expect(message.warning).toHaveBeenCalledWith("Choose a file first");
    expect(mockApi.importVariablesPreview).not.toHaveBeenCalled();
  });
});
