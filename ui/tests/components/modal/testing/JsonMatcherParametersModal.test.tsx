/**
 * @jest-environment jsdom
 */

import React from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { JsonMatcherParametersModal } from "../../../../src/components/modal/testing/JsonMatcherParametersModal.tsx";

const closeContainingModal = jest.fn();

jest.mock("../../../../src/ModalContextProvider.tsx", () => ({
  useModalContext: () => ({ closeContainingModal }),
}));

jest.mock("../../../../src/components/Script.tsx", () => ({
  Script: ({
    value,
    onChange,
  }: {
    value: string;
    onChange?: (value: string) => void;
  }) => (
    <textarea
      data-testid="json-document"
      value={value}
      onChange={(event) => onChange?.(event.target.value)}
    />
  ),
}));

function renderModal(
  props: Partial<React.ComponentProps<typeof JsonMatcherParametersModal>> = {},
) {
  const onSubmit = jest.fn();
  render(
    <JsonMatcherParametersModal
      documentParameterName="schema"
      parameters={null}
      onSubmit={onSubmit}
      {...props}
    />,
  );
  return { onSubmit };
}

describe("JsonMatcherParametersModal", () => {
  test("should default the path to the document root", () => {
    renderModal();
    expect(screen.getByLabelText("Path")).toHaveValue("$");
  });

  test("should load the stored path and document", () => {
    renderModal({
      parameters: [
        { name: "path", value: "$.items" },
        { name: "schema", value: '{"type":"array"}' },
      ],
    });
    expect(screen.getByLabelText("Path")).toHaveValue("$.items");
    expect(screen.getByTestId("json-document")).toHaveValue('{"type":"array"}');
  });

  test("should write path and the document parameter of its type", () => {
    const { onSubmit } = renderModal({ documentParameterName: "sample" });

    fireEvent.change(screen.getByTestId("json-document"), {
      target: { value: '{"a":1}' },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(onSubmit).toHaveBeenCalledWith([
      { name: "path", value: "$" },
      { name: "sample", value: '{"a":1}' },
    ]);
    expect(closeContainingModal).toHaveBeenCalled();
  });

  test("should block saving without a path", () => {
    const { onSubmit } = renderModal();

    fireEvent.change(screen.getByLabelText("Path"), { target: { value: " " } });

    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  test("should close without writing on cancel", () => {
    const { onSubmit } = renderModal();

    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(onSubmit).not.toHaveBeenCalled();
    expect(closeContainingModal).toHaveBeenCalled();
  });
});
