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
import type { FormContext } from "../../src/components/modal/chain_element/ChainElementModificationContext";

// --- Mocks ---

const MockSchemaField = jest.fn(
  (props: { schema: RJSFSchema; [key: string]: unknown }) => (
    <div data-testid="schema-field">{JSON.stringify(props.schema)}</div>
  ),
);

const MockOriginalOneOfField = jest.fn(() => (
  <div data-testid="original-oneof-field" />
));

jest.mock("@rjsf/core", () => ({
  getDefaultRegistry: () => ({
    fields: {
      OneOfField: MockOriginalOneOfField,
    },
  }),
}));

jest.mock("../../src/misc/protocol-utils", () => ({
  normalizeProtocol: (val: string | undefined) => {
    if (!val) return undefined;
    const lower = val.toLowerCase();
    if (lower === "http/1.1" || lower === "http/2") return "http";
    return lower;
  },
  isHttpProtocol: (val: string) => val === "http" || val === "soap",
}));

import CustomOneOfField from "../../src/components/modal/chain_element/field/CustomOneOfField";

// --- Helpers ---

type Props = FieldProps<Record<string, unknown>, RJSFSchema, FormContext>;

function makeProps(overrides: {
  elementType?: string;
  formData?: Record<string, unknown>;
  schema?: RJSFSchema;
  formContext?: Partial<FormContext>;
  fieldPathId?: { $id?: string; path?: string[] };
}): Props {
  const {
    elementType = "service-call",
    formData = {},
    schema = { type: "object" },
    formContext = {},
    fieldPathId = { $id: "root_properties", path: ["properties"] },
  } = overrides;

  return {
    name: "properties",
    formData,
    onChange: jest.fn(),
    disabled: false,
    readonly: false,
    schema,
    uiSchema: {},
    registry: {
      formContext: {
        elementType,
        ...formContext,
      } as FormContext,
      fields: {
        SchemaField: MockSchemaField,
      },
    } as Props["registry"],
    fieldPathId,
  } as Props;
}

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

const serviceCallSchema: RJSFSchema = {
  type: "object",
  oneOf: [
    {
      type: "object",
      properties: {
        integrationOperationProtocolType: { const: "http" },
        integrationOperationPath: { type: "string" },
      },
    },
    {
      type: "object",
      properties: {
        integrationOperationProtocolType: { const: "amqp" },
        exchange: { type: "string" },
      },
    },
    {
      type: "object",
      properties: {
        integrationOperationProtocolType: { const: "kafka" },
        topic: { type: "string" },
      },
    },
  ],
};

beforeEach(() => {
  MockSchemaField.mockClear();
  MockOriginalOneOfField.mockClear();
});

// ============================================================
// HttpTriggerOneOfField
// ============================================================

describe("HttpTriggerOneOfField", () => {
  it("renders dropdown with Custom and Implemented Service options", () => {
    const props = makeProps({
      elementType: "http-trigger",
      schema: httpTriggerSchema,
    });
    render(<CustomOneOfField {...props} />);

    expect(screen.getByText("Custom")).toBeDefined();
  });

  it("defaults to Custom (index 0) when no integrationSystemId", () => {
    const props = makeProps({
      elementType: "http-trigger",
      schema: httpTriggerSchema,
      formData: { contextPath: "/api/test" },
    });
    render(<CustomOneOfField {...props} />);

    const schemaFieldCall = MockSchemaField.mock.calls[0][0];
    // Custom schema includes httpMethodRestrict
    expect(schemaFieldCall.schema.properties).toHaveProperty(
      "httpMethodRestrict",
    );
    expect(schemaFieldCall.schema.properties).toHaveProperty("contextPath");
  });

  it("defaults to Implemented Service (index 1) when integrationSystemId present", () => {
    const props = makeProps({
      elementType: "http-trigger",
      schema: httpTriggerSchema,
      formData: {
        integrationSystemId: "some-id",
        integrationOperationPath: "/v1/chains",
      },
    });
    render(<CustomOneOfField {...props} />);

    const schemaFieldCall = MockSchemaField.mock.calls[0][0];
    // Implemented Service schema should NOT include httpMethodRestrict
    expect(schemaFieldCall.schema.properties).not.toHaveProperty(
      "httpMethodRestrict",
    );
    expect(schemaFieldCall.schema.properties).toHaveProperty(
      "integrationSystemId",
    );
  });

  it("hides httpMethodRestrict from schema for Implemented Service", () => {
    const props = makeProps({
      elementType: "http-trigger",
      schema: httpTriggerSchema,
      formData: { integrationSystemId: "id-1" },
    });
    render(<CustomOneOfField {...props} />);

    const schemaFieldCall = MockSchemaField.mock.calls[0][0];
    expect(schemaFieldCall.schema.properties).not.toHaveProperty(
      "httpMethodRestrict",
    );
  });

  it("removes httpMethodRestrict from required for Implemented Service", () => {
    const props = makeProps({
      elementType: "http-trigger",
      schema: httpTriggerSchema,
      formData: { integrationSystemId: "id-1" },
    });
    render(<CustomOneOfField {...props} />);

    const schemaFieldCall = MockSchemaField.mock.calls[0][0];
    expect(schemaFieldCall.schema.required).not.toContain("httpMethodRestrict");
  });

  it("keeps httpMethodRestrict in schema for Custom", () => {
    const props = makeProps({
      elementType: "http-trigger",
      schema: httpTriggerSchema,
      formData: { contextPath: "/test" },
    });
    render(<CustomOneOfField {...props} />);

    const schemaFieldCall = MockSchemaField.mock.calls[0][0];
    expect(schemaFieldCall.schema.properties).toHaveProperty(
      "httpMethodRestrict",
    );
  });

  it("clears Implemented Service fields when switching to Custom", async () => {
    const props = makeProps({
      elementType: "http-trigger",
      schema: httpTriggerSchema,
      formData: {
        integrationSystemId: "some-id",
        integrationSpecificationId: "spec-id",
        integrationSpecificationGroupId: "group-id",
        integrationOperationId: "op-id",
        integrationOperationPath: "/v1/test",
        systemType: "IMPLEMENTED",
        httpMethodRestrict: "GET",
        chunked: true, // field from outside oneOf — should be preserved
      },
    });
    const { container } = render(<CustomOneOfField {...props} />);

    // Open dropdown and select "Custom" (index 0)
    const selectElement = container.querySelector(".ant-select-selector")!;
    fireEvent.mouseDown(selectElement);

    await waitFor(() => {
      const customOption = document.querySelector(
        '.ant-select-item[title="Custom"]',
      );
      expect(customOption).not.toBeNull();
      fireEvent.click(customOption!);
    });

    const onChange = props.onChange as jest.Mock;
    expect(onChange).toHaveBeenCalledTimes(1);

    const cleanedData = onChange.mock.calls[0][0];
    // Implemented Service fields should be removed
    expect(cleanedData).not.toHaveProperty("integrationSystemId");
    expect(cleanedData).not.toHaveProperty("integrationSpecificationId");
    expect(cleanedData).not.toHaveProperty("integrationSpecificationGroupId");
    expect(cleanedData).not.toHaveProperty("integrationOperationId");
    expect(cleanedData).not.toHaveProperty("integrationOperationPath");
    expect(cleanedData).not.toHaveProperty("systemType");
    // httpMethodRestrict should be cleared on switch
    expect(cleanedData).not.toHaveProperty("httpMethodRestrict");
    // Non-oneOf fields should be preserved
    expect(cleanedData).toHaveProperty("chunked", true);
  });

  it("clears Custom fields when switching to Implemented Service", async () => {
    const props = makeProps({
      elementType: "http-trigger",
      schema: httpTriggerSchema,
      formData: {
        contextPath: "/api/test",
        httpMethodRestrict: "POST,GET",
        chunked: true,
      },
    });
    const { container } = render(<CustomOneOfField {...props} />);

    const selectElement = container.querySelector(".ant-select-selector")!;
    fireEvent.mouseDown(selectElement);

    await waitFor(() => {
      const implOption = document.querySelector(
        '.ant-select-item[title="Implemented Service"]',
      );
      expect(implOption).not.toBeNull();
      fireEvent.click(implOption!);
    });

    const onChange = props.onChange as jest.Mock;
    expect(onChange).toHaveBeenCalledTimes(1);

    const cleanedData = onChange.mock.calls[0][0];
    expect(cleanedData).not.toHaveProperty("contextPath");
    expect(cleanedData).not.toHaveProperty("httpMethodRestrict");
    // Non-oneOf fields preserved
    expect(cleanedData).toHaveProperty("chunked", true);
  });

  it("does not render original OneOfField for http-trigger", () => {
    const props = makeProps({
      elementType: "http-trigger",
      schema: httpTriggerSchema,
    });
    render(<CustomOneOfField {...props} />);

    expect(MockOriginalOneOfField).not.toHaveBeenCalled();
  });
});

// ============================================================
// CustomOneOfField (non-http-trigger elements)
// ============================================================

describe("CustomOneOfField — protocol-based matching", () => {
  it("renders empty schema for gRPC protocol", () => {
    const props = makeProps({
      schema: serviceCallSchema,
      formContext: { integrationOperationProtocolType: "grpc" },
    });
    render(<CustomOneOfField {...props} />);

    const schemaFieldCall = MockSchemaField.mock.calls[0][0];
    expect(schemaFieldCall.schema).toEqual({
      type: "object",
      properties: {},
    });
  });

  it("matches option by protocol type const", () => {
    const props = makeProps({
      schema: serviceCallSchema,
      formContext: { integrationOperationProtocolType: "amqp" },
    });
    render(<CustomOneOfField {...props} />);

    const schemaFieldCall = MockSchemaField.mock.calls[0][0];
    expect(schemaFieldCall.schema.properties).toHaveProperty(
      "integrationOperationProtocolType",
    );
    expect(
      schemaFieldCall.schema.properties.integrationOperationProtocolType.const,
    ).toBe("amqp");
  });

  it("matches kafka option", () => {
    const props = makeProps({
      schema: serviceCallSchema,
      formContext: { integrationOperationProtocolType: "kafka" },
    });
    render(<CustomOneOfField {...props} />);

    const schemaFieldCall = MockSchemaField.mock.calls[0][0];
    expect(
      schemaFieldCall.schema.properties.integrationOperationProtocolType.const,
    ).toBe("kafka");
  });

  it("matches HTTP option for http protocol", () => {
    const props = makeProps({
      schema: serviceCallSchema,
      formContext: { integrationOperationProtocolType: "http" },
    });
    render(<CustomOneOfField {...props} />);

    const schemaFieldCall = MockSchemaField.mock.calls[0][0];
    expect(
      schemaFieldCall.schema.properties.integrationOperationProtocolType.const,
    ).toBe("http");
  });

  it("falls back to option without protocolConst when no protocol set", () => {
    const schemaWithFallback: RJSFSchema = {
      type: "object",
      oneOf: [
        {
          type: "object",
          properties: {
            integrationOperationProtocolType: { const: "http" },
          },
        },
        {
          type: "object",
          properties: {
            fallbackField: { type: "string" },
          },
        },
      ],
    };

    const props = makeProps({
      schema: schemaWithFallback,
      formContext: { integrationOperationProtocolType: undefined },
    });
    render(<CustomOneOfField {...props} />);

    const schemaFieldCall = MockSchemaField.mock.calls[0][0];
    expect(schemaFieldCall.schema.properties).toHaveProperty("fallbackField");
  });

  it("reads protocol from formData when not in formContext", () => {
    const props = makeProps({
      schema: serviceCallSchema,
      formContext: {},
      formData: { integrationOperationProtocolType: "kafka" },
    });
    render(<CustomOneOfField {...props} />);

    const schemaFieldCall = MockSchemaField.mock.calls[0][0];
    expect(
      schemaFieldCall.schema.properties.integrationOperationProtocolType.const,
    ).toBe("kafka");
  });

  it("matches option by enum (kafka/amqp variant)", () => {
    const enumSchema: RJSFSchema = {
      type: "object",
      oneOf: [
        {
          type: "object",
          properties: {
            integrationOperationProtocolType: { enum: ["kafka", "amqp"] },
            asyncProp: { type: "string" },
          },
        },
        {
          type: "object",
          properties: {
            integrationOperationProtocolType: { enum: ["http", "soap"] },
            httpProp: { type: "string" },
          },
        },
        {
          type: "object",
          properties: {
            integrationOperationProtocolType: { const: "graphql" },
            gqlProp: { type: "string" },
          },
        },
      ],
    };

    const props = makeProps({
      schema: enumSchema,
      formContext: { integrationOperationProtocolType: "kafka" },
    });
    render(<CustomOneOfField {...props} />);

    const schemaFieldCall = MockSchemaField.mock.calls[0][0];
    expect(schemaFieldCall.schema.properties).toHaveProperty("asyncProp");
    expect(
      schemaFieldCall.schema.properties.integrationOperationProtocolType.enum,
    ).toEqual(["kafka", "amqp"]);
  });

  it("matches option by enum (http/soap variant)", () => {
    const enumSchema: RJSFSchema = {
      type: "object",
      oneOf: [
        {
          type: "object",
          properties: {
            integrationOperationProtocolType: { enum: ["kafka", "amqp"] },
            asyncProp: { type: "string" },
          },
        },
        {
          type: "object",
          properties: {
            integrationOperationProtocolType: { enum: ["http", "soap"] },
            httpProp: { type: "string" },
          },
        },
      ],
    };

    const props = makeProps({
      schema: enumSchema,
      formContext: { integrationOperationProtocolType: "soap" },
    });
    render(<CustomOneOfField {...props} />);

    const schemaFieldCall = MockSchemaField.mock.calls[0][0];
    expect(schemaFieldCall.schema.properties).toHaveProperty("httpProp");
  });

  it("matches const option when enum options also exist", () => {
    const mixedSchema: RJSFSchema = {
      type: "object",
      oneOf: [
        {
          type: "object",
          properties: {
            integrationOperationProtocolType: { enum: ["kafka", "amqp"] },
            asyncProp: { type: "string" },
          },
        },
        {
          type: "object",
          properties: {
            integrationOperationProtocolType: { const: "graphql" },
            gqlProp: { type: "string" },
          },
        },
      ],
    };

    const props = makeProps({
      schema: mixedSchema,
      formContext: { integrationOperationProtocolType: "graphql" },
    });
    render(<CustomOneOfField {...props} />);

    const schemaFieldCall = MockSchemaField.mock.calls[0][0];
    expect(schemaFieldCall.schema.properties).toHaveProperty("gqlProp");
  });

  it("falls back to option without const or enum when no protocol set", () => {
    const schemaWithFallback: RJSFSchema = {
      type: "object",
      oneOf: [
        {
          type: "object",
          properties: {
            integrationOperationProtocolType: { enum: ["kafka", "amqp"] },
          },
        },
        {
          type: "object",
          properties: {
            fallbackField: { type: "string" },
          },
        },
      ],
    };

    const props = makeProps({
      schema: schemaWithFallback,
      formContext: { integrationOperationProtocolType: undefined },
    });
    render(<CustomOneOfField {...props} />);

    const schemaFieldCall = MockSchemaField.mock.calls[0][0];
    expect(schemaFieldCall.schema.properties).toHaveProperty("fallbackField");
  });

  it("does not render original OneOfField when protocol matches", () => {
    const props = makeProps({
      schema: serviceCallSchema,
      formContext: { integrationOperationProtocolType: "http" },
    });
    render(<CustomOneOfField {...props} />);

    expect(MockOriginalOneOfField).not.toHaveBeenCalled();
  });
});

// ============================================================
// Fallback to OriginalOneOfField
// ============================================================

describe("CustomOneOfField — fallback", () => {
  it("uses original OneOfField when fieldPathId is not root_properties", () => {
    const props = makeProps({
      schema: serviceCallSchema,
      fieldPathId: { $id: "root_other" },
    });
    render(<CustomOneOfField {...props} />);

    expect(MockOriginalOneOfField).toHaveBeenCalled();
    expect(MockSchemaField).not.toHaveBeenCalled();
  });

  it("uses original OneOfField when schema has no oneOf", () => {
    const props = makeProps({
      schema: { type: "object", properties: { foo: { type: "string" } } },
    });
    render(<CustomOneOfField {...props} />);

    expect(MockOriginalOneOfField).toHaveBeenCalled();
  });

  it("uses original OneOfField when no matching protocol found", () => {
    const props = makeProps({
      schema: serviceCallSchema,
      formContext: { integrationOperationProtocolType: "unknown-protocol" },
    });
    render(<CustomOneOfField {...props} />);

    expect(MockOriginalOneOfField).toHaveBeenCalled();
  });
});
