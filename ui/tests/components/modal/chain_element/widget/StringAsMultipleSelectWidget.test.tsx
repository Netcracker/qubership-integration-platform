/**
 * @jest-environment jsdom
 */
import { describe, it, expect, jest } from "@jest/globals";
import { render, screen, fireEvent, createEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { WidgetProps } from "@rjsf/utils";
import StringAsMultipleSelectWidget from "../../../../../src/components/modal/chain_element/widget/StringAsMultipleSelectWidget";
import {
  openSelect,
  querySelectOption,
} from "../../../../helpers/antdSelect.ts";

// The widget only reads value/name/onChange; cast a partial to keep props minimal.
const makeProps = (overrides: Partial<WidgetProps>): WidgetProps =>
  ({
    id: "root_field",
    name: "field",
    value: "",
    onChange: jest.fn(),
    options: {},
    schema: { type: "string" },
    ...overrides,
  }) as unknown as WidgetProps;

describe("StringAsMultipleSelectWidget", () => {
  it("should render uppercased method tags and colored options when name is httpMethodRestrict", () => {
    const { container } = render(
      <StringAsMultipleSelectWidget
        {...makeProps({ name: "httpMethodRestrict", value: "get,post" })}
      />,
    );

    // tagRender uppercases each selected value into a qip-method-tag.
    const tags = container.querySelectorAll(".qip-method-tag");
    expect(tags).toHaveLength(2);
    expect(screen.getByText("GET")).toBeInTheDocument();
    expect(screen.getByText("POST")).toBeInTheDocument();

    // Opening the dropdown runs optionRender for every method option.
    openSelect(container);
    const putOption = querySelectOption("PUT");
    expect(putOption).not.toBeNull();
    expect(putOption).toHaveTextContent("PUT");
  });

  it("should prevent default and stop propagation when a method tag receives mousedown", () => {
    const { container } = render(
      <StringAsMultipleSelectWidget
        {...makeProps({ name: "httpMethodRestrict", value: "get" })}
      />,
    );

    const tag = container.querySelector(".qip-method-tag");
    expect(tag).not.toBeNull();

    const mouseDown = createEvent.mouseDown(tag as Element);
    const stopPropagation = jest.spyOn(mouseDown, "stopPropagation");
    fireEvent(tag as Element, mouseDown);
    expect(mouseDown.defaultPrevented).toBe(true);
    // stopPropagation keeps the tag's mousedown from bubbling to the Select
    // and toggling the dropdown open.
    expect(stopPropagation).toHaveBeenCalled();
  });

  it("should call onChange with the comma-joined selection when a method option is picked", () => {
    const onChange = jest.fn();
    const { container } = render(
      <StringAsMultipleSelectWidget
        {...makeProps({
          name: "httpMethodRestrict",
          value: "get",
          onChange,
        })}
      />,
    );

    openSelect(container);
    const postOption = querySelectOption("POST");
    expect(postOption).not.toBeNull();
    fireEvent.click(postOption as Element);

    expect(onChange).toHaveBeenCalledWith("get,POST");
  });

  it("should render no options and no method tags when name is not httpMethodRestrict", () => {
    const { container } = render(
      <StringAsMultipleSelectWidget
        {...makeProps({ name: "someOtherField", value: "alpha,beta" })}
      />,
    );

    // Non-method branch: options = [] and the plain tag renderer is used.
    expect(container.querySelector(".qip-method-tag")).toBeNull();
    expect(screen.getByText("alpha")).toBeInTheDocument();

    openSelect(container);
    expect(querySelectOption("GET")).toBeNull();
  });
});
