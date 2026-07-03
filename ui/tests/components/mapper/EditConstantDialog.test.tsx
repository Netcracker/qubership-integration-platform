/**
 * @jest-environment jsdom
 */

import {
  render,
  screen,
  fireEvent,
  waitFor,
  act,
} from "@testing-library/react";
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

// Antd v6 mirrors form fields on a macro task. Flush a few macro-task rounds
// (each followed by React's effect flush) so the result is observed
// deterministically, instead of racing a waitFor wall-clock timeout.
async function settleForm(): Promise<void> {
  for (let i = 0; i < 3; i++) {
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
  }
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

    await settleForm();
    const valueInput = screen.getAllByRole("textbox")[1];
    expect(valueInput).toHaveValue("abc");
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

    await settleForm();
    expect(valueInput).toHaveValue("manual-value");
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

    await settleForm();
    expect(valueInput).toHaveValue("");
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
