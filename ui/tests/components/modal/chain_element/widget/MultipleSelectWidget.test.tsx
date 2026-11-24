/**
 * @jest-environment jsdom
 */
import type { WidgetProps } from "@rjsf/utils";
import { fireEvent, render, screen } from "@testing-library/react";
import { INITIAL_UI_SCHEMA } from "../../../../../src/components/modal/chain_element/ChainElementModificationConstants";
import MultipleSelectWidget from "../../../../../src/components/modal/chain_element/widget/MultipleSelectWidget";
import { openSelect, querySelectOption } from "../../../../helpers/antdSelect";

const contentTypesOptions =
  INITIAL_UI_SCHEMA.properties.allowedContentTypes["ui:options"];

const makeProps = (overrides: Partial<WidgetProps>): WidgetProps =>
  ({
    id: "root_properties_allowedContentTypes",
    name: "allowedContentTypes",
    label: "Allowed Content Types",
    value: ["text/plain"],
    onChange: jest.fn(),
    schema: { type: "array", items: { type: "string" } },
    options: contentTypesOptions,
    ...overrides,
  }) as WidgetProps;

it("should select a suggested content type with Enter when the search matches it", () => {
  const onChange = jest.fn();
  const { container } = render(
    <MultipleSelectWidget {...makeProps({ onChange })} />,
  );

  openSelect(container);
  const input = screen.getByRole("combobox", { name: "Allowed Content Types" });
  fireEvent.change(input, { target: { value: "application/j" } });
  expect(querySelectOption("application/json")).toBeInTheDocument();
  fireEvent.keyDown(input, { key: "Enter", keyCode: 13 });

  expect(onChange).toHaveBeenCalledWith(["text/plain", "application/json"]);
});

it("should add a custom content type when no suggestion matches", () => {
  const onChange = jest.fn();
  const { container } = render(
    <MultipleSelectWidget {...makeProps({ onChange })} />,
  );
  const customType = "application/vnd.example+json";

  openSelect(container);
  const input = screen.getByRole("combobox", { name: "Allowed Content Types" });
  fireEvent.change(input, { target: { value: customType } });
  fireEvent.keyDown(input, { key: "Enter", keyCode: 13 });

  expect(onChange).toHaveBeenCalledWith(["text/plain", customType]);
});

it("should offer the schema enum when retryable error codes have no suggestions", () => {
  const onChange = jest.fn();
  const { container } = render(
    <MultipleSelectWidget
      {...makeProps({
        id: "root_properties_synchronousPullRetryableCodes",
        name: "synchronousPullRetryableCodes",
        label: "Retryable error codes",
        value: ["ABORTED"],
        options: {},
        schema: { type: "array", items: { enum: ["ABORTED", "UNAVAILABLE"] } },
        onChange,
      })}
    />,
  );

  openSelect(container);
  const unavailable = querySelectOption("UNAVAILABLE");
  expect(unavailable).toBeInTheDocument();
  fireEvent.click(unavailable!);

  expect(onChange).toHaveBeenCalledWith(["ABORTED", "UNAVAILABLE"]);
});
