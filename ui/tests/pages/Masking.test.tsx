/**
 * @jest-environment jsdom
 *
 * Tests `Masking` page chain header toolbar via full page render (toolbar is inlined).
 */

import React from "react";
import { screen, waitFor, fireEvent, within } from "@testing-library/react";
import "@testing-library/jest-dom";
import { api } from "../../src/api/api.ts";
import { Masking } from "../../src/pages/Masking.tsx";
import { renderPageWithChainHeader } from "../helpers/renderWithChainHeader.tsx";

const mockUseParams = jest.fn(() => ({ chainId: "chain-1" }));

jest.mock("react-router", () => ({
  useParams: () => mockUseParams(),
}));

jest.mock("../../src/api/api.ts", () => ({
  api: {
    getMaskedFields: jest.fn(),
    updateMaskedField: jest.fn(),
    createMaskedField: jest.fn(),
    deleteMaskedFields: jest.fn(),
  },
}));

jest.mock("../../src/api/rest/vscodeExtensionApi.ts", () => ({
  isVsCode: false,
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

function renderMasking() {
  return renderPageWithChainHeader(<Masking />);
}

describe("Masking chain header toolbar", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockUseParams.mockReturnValue({ chainId: "chain-1" });
    (api.getMaskedFields as jest.Mock).mockResolvedValue([]);
    (api.createMaskedField as jest.Mock).mockResolvedValue({
      id: "new-1",
      name: "",
    });
    (api.deleteMaskedFields as jest.Mock).mockResolvedValue(undefined);
  });

  test("registers header toolbar with search, Delete, Create", async () => {
    renderMasking();

    const slot = await waitFor(() => screen.getByTestId("chain-header-slot"));
    expect(
      within(slot).getByPlaceholderText("Search masked fields..."),
    ).toBeInTheDocument();
    expect(
      within(slot).getByTestId("protected-btn-delete-selected-masked-fields"),
    ).toBeInTheDocument();
    expect(
      within(slot).getByTestId("protected-btn-add-new-masked-field"),
    ).toBeInTheDocument();
  });

  test("Add new masked field calls api.createMaskedField", async () => {
    // eslint-disable-next-line @typescript-eslint/unbound-method -- jest.fn on mocked api
    const createMaskedField = api.createMaskedField as jest.Mock;
    renderMasking();

    const slot = await waitFor(() => screen.getByTestId("chain-header-slot"));
    fireEvent.click(
      within(slot).getByTestId("protected-btn-add-new-masked-field"),
    );

    await waitFor(() => {
      expect(createMaskedField).toHaveBeenCalledWith("chain-1", {
        name: "",
      });
    });
  });

  test("Delete disabled when no rows selected", async () => {
    renderMasking();

    const slot = await waitFor(() => screen.getByTestId("chain-header-slot"));
    expect(
      within(slot).getByTestId("protected-btn-delete-selected-masked-fields"),
    ).toBeDisabled();
  });

  test("Delete enabled after row selection; click calls api.deleteMaskedFields", async () => {
    // eslint-disable-next-line @typescript-eslint/unbound-method -- jest.fn on mocked api
    const deleteMaskedFields = api.deleteMaskedFields as jest.Mock;
    (api.getMaskedFields as jest.Mock).mockResolvedValue([
      { id: "id-1", name: "field-a" },
    ]);

    renderMasking();

    await waitFor(() => {
      expect(screen.getByText("field-a")).toBeInTheDocument();
    });

    const slot = await waitFor(() => screen.getByTestId("chain-header-slot"));
    expect(
      within(slot).getByTestId("protected-btn-delete-selected-masked-fields"),
    ).toBeDisabled();

    const row = screen.getByRole("row", { name: /field-a/i });
    fireEvent.click(within(row).getByRole("checkbox"));

    await waitFor(() => {
      expect(
        within(screen.getByTestId("chain-header-slot")).getByTestId(
          "protected-btn-delete-selected-masked-fields",
        ),
      ).not.toBeDisabled();
    });

    fireEvent.click(
      within(screen.getByTestId("chain-header-slot")).getByTestId(
        "protected-btn-delete-selected-masked-fields",
      ),
    );

    await waitFor(() => {
      expect(deleteMaskedFields).toHaveBeenCalledWith("chain-1", ["id-1"]);
    });
  });
});
