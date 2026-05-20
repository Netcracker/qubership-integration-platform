/**
 * @jest-environment jsdom
 */

import React from "react";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import { DataTypes } from "../../../src/mapper/util/types.ts";
import type { Constant } from "../../../src/mapper/model/model.ts";
import { EditConstantDialog } from "../../../src/components/mapper/EditConstantDialog.tsx";

const mockCloseContainingModal = jest.fn();
const mockOnSubmit = jest.fn();

jest.mock("../../../src/ModalContextProvider.tsx", () => ({
  useModalContext: () => ({
    closeContainingModal: mockCloseContainingModal,
  }),
}));

jest.mock("../../../src/mapper/model/generators.ts", () => ({
  getGeneratorsForType: () => [
    { name: "generateUUID", title: "UUID" },
    { name: "currentDate", title: "Date" },
  ],
}));

jest.mock(
  "../../../src/components/mapper/transformation/parameters/FormatDateTimeParameters.tsx",
  () => ({
    TimestampFormatParameters: () => (
      <div data-testid="timestamp-params-stub" />
    ),
  }),
);

jest.mock("antd", () =>
  // eslint-disable-next-line @typescript-eslint/no-require-imports, @typescript-eslint/no-unsafe-call, @typescript-eslint/no-unsafe-member-access, @typescript-eslint/no-unsafe-return -- jest.mock hoisting
  require("tests/helpers/antdMockWithLightweightTable").antdMockWithLightweightTable(),
);

function givenConstant(
  overrides: Partial<Constant> & Pick<Constant, "id" | "name">,
): Constant {
  return {
    type: DataTypes.stringType(),
    valueSupplier: { kind: "given", value: overrides.name },
    ...overrides,
  };
}

describe("EditConstantDialog", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  test("typing name mirrors into value while fields stay in sync", async () => {
    const constant = givenConstant({
      id: "1",
      name: "",
      valueSupplier: { kind: "given", value: "" },
    });
    render(
      <EditConstantDialog
        title="Edit"
        constant={constant}
        onSubmit={mockOnSubmit}
      />,
    );

    const nameInput = screen.getByLabelText("Name");
    fireEvent.change(nameInput, { target: { value: "abc" } });

    await waitFor(() => {
      const valueInput = screen.getAllByRole("textbox")[1];
      expect(valueInput).toHaveValue("abc");
    });
  });

  test("after value diverges from name, changing name does not overwrite value", async () => {
    const constant = givenConstant({
      id: "1",
      name: "same",
      valueSupplier: { kind: "given", value: "same" },
    });
    render(
      <EditConstantDialog
        title="Edit"
        constant={constant}
        onSubmit={mockOnSubmit}
      />,
    );

    const nameInput = screen.getByLabelText("Name");
    const valueInput = screen.getAllByRole("textbox")[1];
    fireEvent.change(valueInput, { target: { value: "manual-value" } });
    fireEvent.change(nameInput, { target: { value: "renamed" } });

    await waitFor(() => {
      expect(valueInput).toHaveValue("manual-value");
    });
  });

  test("clearing value when name is non-empty does not refill value from name", async () => {
    const constant = givenConstant({
      id: "1",
      name: "keepname",
      valueSupplier: { kind: "given", value: "keepname" },
    });
    render(
      <EditConstantDialog
        title="Edit"
        constant={constant}
        onSubmit={mockOnSubmit}
      />,
    );

    const valueInput = screen.getAllByRole("textbox")[1];
    fireEvent.change(valueInput, { target: { value: "" } });

    await waitFor(() => {
      expect(valueInput).toHaveValue("");
    });
  });

  test("Generated toggles generator UI and submit sends generated supplier", async () => {
    const constant = givenConstant({
      id: "1",
      name: "c1",
      valueSupplier: { kind: "given", value: "c1" },
    });
    render(
      <EditConstantDialog
        title="Edit"
        constant={constant}
        onSubmit={mockOnSubmit}
      />,
    );

    fireEvent.click(screen.getByRole("checkbox", { name: /Generated/i }));

    await waitFor(() => {
      expect(screen.getByText("UUID")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole("button", { name: "Submit" }));

    await waitFor(() => {
      expect(mockOnSubmit).toHaveBeenCalled();
      const payload = mockOnSubmit.mock.calls[0][0] as {
        valueSupplier: { kind: string };
      };
      expect(payload.valueSupplier.kind).toBe("generated");
    });
    expect(mockCloseContainingModal).toHaveBeenCalled();
  });

  test("given value submit returns updated given supplier", async () => {
    const constant = givenConstant({
      id: "1",
      name: "n",
      valueSupplier: { kind: "given", value: "n" },
    });
    render(
      <EditConstantDialog
        title="Edit"
        constant={constant}
        onSubmit={mockOnSubmit}
      />,
    );

    const valueInput = screen.getAllByRole("textbox")[1];
    fireEvent.change(valueInput, { target: { value: "final" } });
    fireEvent.click(screen.getByRole("button", { name: "Submit" }));

    await waitFor(() => {
      expect(mockOnSubmit).toHaveBeenCalledWith(
        expect.objectContaining({
          valueSupplier: { kind: "given", value: "final" },
        }),
      );
    });
  });
});
