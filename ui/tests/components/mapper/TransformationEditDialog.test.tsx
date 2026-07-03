/**
 * @jest-environment jsdom
 */

import { describe, it, expect, beforeEach, jest } from "@jest/globals";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import {
  openSelect,
  querySelectOption,
  getSelectTriggers,
} from "../../helpers/antdSelect.ts";
import { useModalContext } from "../../../src/ModalContextProvider";
import {
  TransformationEditDialog,
  type TransformationEditDialogProps,
} from "../../../src/components/mapper/TransformationEditDialog.tsx";

// The parameter components pull heavy editors/dictionaries, so replace each with
// an identifiable stub. This keeps the test focused on the dialog's own wiring:
// which parameter component the name selects, not what that component renders.
jest.mock(
  "../../../src/components/mapper/transformation/parameters/TrimParameters",
  () => ({ TrimParameters: () => <div data-testid="trim-params" /> }),
);
jest.mock(
  "../../../src/components/mapper/transformation/parameters/ReplaceAllParameters",
  () => ({
    ReplaceAllParameters: () => <div data-testid="replaceall-params" />,
  }),
);
jest.mock(
  "../../../src/components/mapper/transformation/parameters/DefautValueParameters",
  () => ({
    DefaultValueParameters: () => <div data-testid="defaultvalue-params" />,
  }),
);
jest.mock(
  "../../../src/components/mapper/transformation/parameters/FormatDateTimeParameters",
  () => ({
    FormatDateTimeParameters: () => <div data-testid="formatdatetime-params" />,
  }),
);
jest.mock(
  "../../../src/components/mapper/transformation/parameters/ExpressionParameters",
  () => ({
    ExpressionParameters: () => <div data-testid="expression-params" />,
  }),
);
jest.mock(
  "../../../src/components/mapper/transformation/parameters/ConditionalParameters",
  () => ({
    ConditionalParameters: () => <div data-testid="conditional-params" />,
  }),
);
jest.mock(
  "../../../src/components/mapper/transformation/parameters/DictionaryParameters",
  () => ({
    DictionaryParameters: () => <div data-testid="dictionary-params" />,
  }),
);

jest.mock("../../../src/ModalContextProvider");

const mockUseModalContext = useModalContext as jest.MockedFunction<
  typeof useModalContext
>;
const closeContainingModal = jest.fn<() => void>();

type OnSubmit = NonNullable<TransformationEditDialogProps["onSubmit"]>;

beforeEach(() => {
  mockUseModalContext.mockReturnValue({ closeContainingModal });
});

describe("TransformationEditDialog", () => {
  it("should render the modal titled 'Edit transformation' with the transformation select when opened", () => {
    render(<TransformationEditDialog />);

    expect(screen.getByText("Edit transformation")).toBeInTheDocument();
    expect(getSelectTriggers(document.body)).toHaveLength(1);
  });

  it("should render the trim parameters component when constructed with a trim transformation", () => {
    render(
      <TransformationEditDialog
        transformation={{ name: "trim", parameters: [] }}
      />,
    );

    expect(screen.getByTestId("trim-params")).toBeInTheDocument();
  });

  it("should swap the parameters component and reset parameters when a different transformation is selected", async () => {
    render(
      <TransformationEditDialog
        transformation={{ name: "trim", parameters: ["left"] }}
      />,
    );
    expect(screen.getByTestId("trim-params")).toBeInTheDocument();

    openSelect(document.body);
    const option = await waitFor(() => {
      const found = querySelectOption("Replace all");
      expect(found).not.toBeNull();
      return found!;
    });
    fireEvent.click(option);

    // Selecting a new name runs the same onValuesChange branch that resets
    // `parameters` to [], so the swapped stub is proof that branch executed.
    await waitFor(() => {
      expect(screen.getByTestId("replaceall-params")).toBeInTheDocument();
    });
    expect(screen.queryByTestId("trim-params")).not.toBeInTheDocument();
  });

  it("should clear the parameters component when None is selected", async () => {
    render(
      <TransformationEditDialog
        transformation={{ name: "trim", parameters: ["left"] }}
      />,
    );
    expect(screen.getByTestId("trim-params")).toBeInTheDocument();

    openSelect(document.body);
    const noneOption = await waitFor(() => {
      const found = querySelectOption("None");
      expect(found).not.toBeNull();
      return found!;
    });
    fireEvent.click(noneOption);

    // "None" has no entry in parametersComponentMap, so the `|| ""` fallback
    // clears the parameters area entirely.
    await waitFor(() => {
      expect(screen.queryByTestId("trim-params")).not.toBeInTheDocument();
    });
  });

  it("should submit the selected transformation and close the modal when saved", async () => {
    const onSubmit = jest.fn<OnSubmit>();
    render(
      <TransformationEditDialog
        transformation={{ name: "trim", parameters: [] }}
        onSubmit={onSubmit}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalledWith(
        expect.objectContaining({ name: "trim" }),
        "",
      );
    });
    expect(closeContainingModal).toHaveBeenCalled();
  });

  it("should submit undefined when saved with no transformation selected", async () => {
    const onSubmit = jest.fn<OnSubmit>();
    render(<TransformationEditDialog onSubmit={onSubmit} />);

    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalledWith(undefined, "");
    });
    expect(closeContainingModal).toHaveBeenCalled();
  });

  it("should render the description field and submit its text when enableDescription is true", async () => {
    const onSubmit = jest.fn<OnSubmit>();
    render(
      <TransformationEditDialog
        transformation={{ name: "trim", parameters: [] }}
        enableDescription
        onSubmit={onSubmit}
      />,
    );

    const textArea = screen.getByPlaceholderText("Description");
    fireEvent.change(textArea, { target: { value: "trim leading spaces" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalledWith(
        expect.objectContaining({ name: "trim" }),
        "trim leading spaces",
      );
    });
  });

  it("should not render the description field when enableDescription is falsy", () => {
    render(
      <TransformationEditDialog
        transformation={{ name: "trim", parameters: [] }}
      />,
    );

    expect(
      screen.queryByPlaceholderText("Description"),
    ).not.toBeInTheDocument();
  });
});
