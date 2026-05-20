/**
 * @jest-environment jsdom
 */

Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: jest.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: jest.fn(),
    removeListener: jest.fn(),
    addEventListener: jest.fn(),
    removeEventListener: jest.fn(),
    dispatchEvent: jest.fn(),
  })),
});

import { describe, it, expect, beforeEach } from "@jest/globals";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { FieldProps, RJSFSchema } from "@rjsf/utils";
import type { FormContext } from "../../../src/components/modal/chain_element/ChainElementModificationContext";
import type { OneOfOption } from "../../../src/components/modal/chain_element/field/oneof/oneof-utils";

const MockSchemaField = jest.fn(
  (props: { schema: RJSFSchema; [key: string]: unknown }) => (
    <div data-testid="schema-field">{JSON.stringify(props.schema)}</div>
  ),
);

import OneOfSelectField from "../../../src/components/modal/chain_element/field/oneof/OneOfSelectField";

type Props = FieldProps<Record<string, unknown>, RJSFSchema, FormContext>;

const OPTIONS: OneOfOption[] = [
  { title: "Alpha", properties: { foo: { type: "string" } } },
  { title: "Beta", properties: { bar: { type: "string" } } },
  { properties: { baz: { type: "string" } } }, // untitled — default label
];

function makeBaseProps(overrides: Partial<Props> = {}): Props {
  return {
    name: "root",
    formData: {},
    onChange: jest.fn(),
    disabled: false,
    readonly: false,
    schema: { type: "object" } as RJSFSchema,
    uiSchema: {},
    registry: {
      formContext: {} as FormContext,
      fields: { SchemaField: MockSchemaField },
    } as unknown as Props["registry"],
    fieldPathId: { $id: "root", path: [] },
    ...overrides,
  } as Props;
}

beforeEach(() => {
  MockSchemaField.mockClear();
});

describe("OneOfSelectField", () => {
  it("renders dropdown labels with titles, falling back to defaults", () => {
    render(
      <OneOfSelectField
        {...makeBaseProps()}
        options={OPTIONS}
        selectedIndex={0}
        onSwitch={jest.fn()}
      />,
    );
    expect(screen.getByText("Alpha")).toBeInTheDocument();
  });

  it("passes selected option's schema to SchemaField", () => {
    render(
      <OneOfSelectField
        {...makeBaseProps()}
        options={OPTIONS}
        selectedIndex={1}
        onSwitch={jest.fn()}
      />,
    );
    const schemaFieldCall = MockSchemaField.mock.calls[0][0];
    expect(schemaFieldCall.schema.properties).toHaveProperty("bar");
  });

  it("applies transformSchema when provided", () => {
    const transform = jest.fn(
      (_opt: OneOfOption, idx: number) =>
        ({
          type: "object",
          properties: { transformed: { type: "string", title: `#${idx}` } },
        }) as RJSFSchema,
    );
    render(
      <OneOfSelectField
        {...makeBaseProps()}
        options={OPTIONS}
        selectedIndex={2}
        onSwitch={jest.fn()}
        transformSchema={transform}
      />,
    );
    expect(transform).toHaveBeenCalledWith(OPTIONS[2], 2);
    const schemaFieldCall = MockSchemaField.mock.calls[0][0];
    expect(schemaFieldCall.schema.properties).toHaveProperty("transformed");
  });

  it("falls back to empty object schema when selectedIndex is out of range", () => {
    render(
      <OneOfSelectField
        {...makeBaseProps()}
        options={OPTIONS}
        selectedIndex={99}
        onSwitch={jest.fn()}
      />,
    );
    const schemaFieldCall = MockSchemaField.mock.calls[0][0];
    expect(schemaFieldCall.schema).toEqual({ type: "object", properties: {} });
  });

  it("calls onSwitch with the new index when the user picks an option", async () => {
    const onSwitch = jest.fn();
    const { container } = render(
      <OneOfSelectField
        {...makeBaseProps()}
        options={OPTIONS}
        selectedIndex={0}
        onSwitch={onSwitch}
      />,
    );
    const selectElement = container.querySelector(".ant-select-selector")!;
    fireEvent.mouseDown(selectElement);
    await waitFor(() => {
      const option = document.querySelector('.ant-select-item[title="Beta"]');
      expect(option).not.toBeNull();
      fireEvent.click(option!);
    });
    expect(onSwitch).toHaveBeenCalledWith(1, expect.anything());
  });
});
