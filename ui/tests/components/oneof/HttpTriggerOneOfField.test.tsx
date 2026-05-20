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
import type { JSONSchema7 } from "json-schema";
import type { FormContext } from "../../../src/components/modal/chain_element/ChainElementModificationContext";

const MockSchemaField = jest.fn(
  (props: { schema: RJSFSchema; [key: string]: unknown }) => (
    <div data-testid="schema-field">{JSON.stringify(props.schema)}</div>
  ),
);

import HttpTriggerOneOfField from "../../../src/components/modal/chain_element/field/oneof/HttpTriggerOneOfField";

type Props = FieldProps<Record<string, unknown>, RJSFSchema, FormContext>;

const httpTriggerSchema: RJSFSchema = {
  type: "object",
  oneOf: [
    {
      title: "Custom",
      type: "object",
      properties: {
        contextPath: { type: "string", title: "URI" },
        httpMethodRestrict: { type: "string", title: "HTTP Methods" },
      },
      required: ["contextPath"],
    },
    {
      title: "Implemented Service",
      type: "object",
      properties: {
        integrationSystemId: { type: "string", title: "Service ID" },
        integrationOperationId: { type: "string", title: "Operation ID" },
        integrationOperationPath: { type: "string", title: "Operation name" },
        systemType: { type: "string", title: "Service type" },
        httpMethodRestrict: { type: "string", title: "Operation method" },
      },
      required: [
        "integrationSystemId",
        "integrationOperationPath",
        "httpMethodRestrict",
      ],
    },
  ],
};

function makeProps(overrides: {
  formData?: Record<string, unknown>;
  fieldPathId?: { $id?: string; path?: string[] };
} = {}): Props {
  const {
    formData = {},
    fieldPathId = { $id: "root_properties", path: ["properties"] },
  } = overrides;
  return {
    name: "properties",
    formData,
    onChange: jest.fn(),
    disabled: false,
    readonly: false,
    schema: httpTriggerSchema,
    uiSchema: {},
    registry: {
      formContext: { elementType: "http-trigger" } as FormContext,
      fields: { SchemaField: MockSchemaField },
    } as unknown as Props["registry"],
    fieldPathId,
  } as unknown as Props;
}

/** Safe reader for nested JSON-Schema properties in test assertions. */
function props(schema: RJSFSchema): Record<string, JSONSchema7> {
  return (schema.properties ?? {}) as Record<string, JSONSchema7>;
}

beforeEach(() => {
  MockSchemaField.mockClear();
});

describe("HttpTriggerOneOfField", () => {
  it("renders dropdown with Custom and Implemented Service options", () => {
    render(<HttpTriggerOneOfField {...makeProps()} />);
    expect(screen.getByText("Custom")).toBeInTheDocument();
  });

  it("defaults to Custom when no integrationSystemId", () => {
    render(
      <HttpTriggerOneOfField
        {...makeProps({ formData: { contextPath: "/api/test" } })}
      />,
    );
    const passed = MockSchemaField.mock.calls[0][0].schema;
    expect(props(passed)).toHaveProperty("httpMethodRestrict");
    expect(props(passed)).toHaveProperty("contextPath");
  });

  it("defaults to Implemented Service when integrationSystemId is present", () => {
    render(
      <HttpTriggerOneOfField
        {...makeProps({
          formData: {
            integrationSystemId: "sys-1",
            integrationOperationPath: "/v1/chains",
          },
        })}
      />,
    );
    const passed = MockSchemaField.mock.calls[0][0].schema;
    expect(props(passed)).toHaveProperty("integrationSystemId");
    expect(props(passed)).not.toHaveProperty("httpMethodRestrict");
  });

  it("removes httpMethodRestrict from required for Implemented Service", () => {
    render(
      <HttpTriggerOneOfField
        {...makeProps({ formData: { integrationSystemId: "id-1" } })}
      />,
    );
    const passed = MockSchemaField.mock.calls[0][0].schema;
    expect(passed.required).not.toContain("httpMethodRestrict");
  });

  it("keeps httpMethodRestrict in schema for Custom", () => {
    render(
      <HttpTriggerOneOfField
        {...makeProps({ formData: { contextPath: "/test" } })}
      />,
    );
    const passed = MockSchemaField.mock.calls[0][0].schema;
    expect(props(passed)).toHaveProperty("httpMethodRestrict");
  });

  it("clears Implemented Service fields when switching to Custom", async () => {
    const baseProps = makeProps({
      formData: {
        integrationSystemId: "some-id",
        integrationSpecificationId: "spec-id",
        integrationSpecificationGroupId: "group-id",
        integrationOperationId: "op-id",
        integrationOperationPath: "/v1/test",
        systemType: "IMPLEMENTED",
        httpMethodRestrict: "GET",
        chunked: true,
      },
    });
    const { container } = render(<HttpTriggerOneOfField {...baseProps} />);

    const selectElement = container.querySelector(".ant-select-selector")!;
    fireEvent.mouseDown(selectElement);
    await waitFor(() => {
      const customOption = document.querySelector(
        '.ant-select-item[title="Custom"]',
      );
      expect(customOption).not.toBeNull();
      fireEvent.click(customOption!);
    });

    const onChange = baseProps.onChange as jest.Mock;
    expect(onChange).toHaveBeenCalledTimes(1);
    const [cleanedData] = onChange.mock.calls[0] as [
      Record<string, unknown>,
      ...unknown[],
    ];
    [
      "integrationSystemId",
      "integrationSpecificationId",
      "integrationSpecificationGroupId",
      "integrationOperationId",
      "integrationOperationPath",
      "systemType",
      "httpMethodRestrict",
    ].forEach((key) => expect(cleanedData).not.toHaveProperty(key));
    expect(cleanedData).toHaveProperty("chunked", true);
  });

  it("clears Custom fields when switching to Implemented Service", async () => {
    const baseProps = makeProps({
      formData: {
        contextPath: "/api/test",
        httpMethodRestrict: "POST,GET",
        chunked: true,
      },
    });
    const { container } = render(<HttpTriggerOneOfField {...baseProps} />);

    const selectElement = container.querySelector(".ant-select-selector")!;
    fireEvent.mouseDown(selectElement);
    await waitFor(() => {
      const option = document.querySelector(
        '.ant-select-item[title="Implemented Service"]',
      );
      expect(option).not.toBeNull();
      fireEvent.click(option!);
    });

    const [cleanedData] = (baseProps.onChange as jest.Mock).mock.calls[0] as [
      Record<string, unknown>,
      ...unknown[],
    ];
    expect(cleanedData).not.toHaveProperty("contextPath");
    expect(cleanedData).not.toHaveProperty("httpMethodRestrict");
    expect(cleanedData).toHaveProperty("chunked", true);
  });
});
