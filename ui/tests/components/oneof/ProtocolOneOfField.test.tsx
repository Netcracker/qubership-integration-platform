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
import { render } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { FieldProps, RJSFSchema } from "@rjsf/utils";
import type { JSONSchema7 } from "json-schema";
import type { FormContext } from "../../../src/components/modal/chain_element/ChainElementModificationContext";

jest.mock("../../../src/misc/protocol-utils", () => ({
  normalizeProtocol: (val: string | undefined) => {
    if (!val) return undefined;
    const lower = val.toLowerCase();
    if (lower === "http/1.1" || lower === "http/2") return "http";
    return lower;
  },
  isGrpcProtocol: (val: string | undefined) =>
    typeof val === "string" && val.toLowerCase() === "grpc",
}));

const MockOriginalOneOfField = jest.fn((_props: unknown) => (
  <div data-testid="original-oneof" />
));

jest.mock("@rjsf/core", () => ({
  getDefaultRegistry: () => ({
    fields: { OneOfField: MockOriginalOneOfField },
  }),
}));

const MockSchemaField = jest.fn(
  (props: { schema: RJSFSchema; [key: string]: unknown }) => (
    <div data-testid="schema-field">{JSON.stringify(props.schema)}</div>
  ),
);

import ProtocolOneOfField from "../../../src/components/modal/chain_element/field/oneof/ProtocolOneOfField";

type Props = FieldProps<Record<string, unknown>, RJSFSchema, FormContext>;

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

function makeProps(overrides: {
  schema?: RJSFSchema;
  formContext?: Partial<FormContext>;
  formData?: Record<string, unknown>;
} = {}): Props {
  const {
    schema = serviceCallSchema,
    formContext = {},
    formData = {},
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
      formContext: { elementType: "service-call", ...formContext } as FormContext,
      fields: { SchemaField: MockSchemaField },
    } as unknown as Props["registry"],
    fieldPathId: { $id: "root_properties", path: ["properties"] },
  } as unknown as Props;
}

function props(schema: RJSFSchema): Record<string, JSONSchema7> {
  return (schema.properties ?? {}) as Record<string, JSONSchema7>;
}

function protoConst(
  schema: RJSFSchema,
): string | undefined {
  const p = props(schema).integrationOperationProtocolType;
  return typeof p === "object" ? (p as { const?: string }).const : undefined;
}

beforeEach(() => {
  MockSchemaField.mockClear();
});

describe("ProtocolOneOfField", () => {
  it("renders empty schema for gRPC", () => {
    render(
      <ProtocolOneOfField
        {...makeProps({
          formContext: { integrationOperationProtocolType: "grpc" },
        })}
      />,
    );
    expect(MockSchemaField.mock.calls[0][0].schema).toEqual({
      type: "object",
      properties: {},
    });
  });

  it("matches option by protocol const (amqp)", () => {
    render(
      <ProtocolOneOfField
        {...makeProps({
          formContext: { integrationOperationProtocolType: "amqp" },
        })}
      />,
    );
    expect(protoConst(MockSchemaField.mock.calls[0][0].schema)).toBe("amqp");
  });

  it("matches option by protocol const (kafka)", () => {
    render(
      <ProtocolOneOfField
        {...makeProps({
          formContext: { integrationOperationProtocolType: "kafka" },
        })}
      />,
    );
    expect(protoConst(MockSchemaField.mock.calls[0][0].schema)).toBe("kafka");
  });

  it("reads protocol from formData when not in formContext", () => {
    render(
      <ProtocolOneOfField
        {...makeProps({
          formContext: {},
          formData: { integrationOperationProtocolType: "kafka" },
        })}
      />,
    );
    expect(protoConst(MockSchemaField.mock.calls[0][0].schema)).toBe("kafka");
  });

  it("normalizes HTTP/1.1 to http", () => {
    render(
      <ProtocolOneOfField
        {...makeProps({
          formContext: { integrationOperationProtocolType: "HTTP/1.1" },
        })}
      />,
    );
    expect(protoConst(MockSchemaField.mock.calls[0][0].schema)).toBe("http");
  });

  it("matches option by enum", () => {
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
            integrationOperationProtocolType: { const: "graphql" },
            gqlProp: { type: "string" },
          },
        },
      ],
    };
    render(
      <ProtocolOneOfField
        {...makeProps({
          schema: enumSchema,
          formContext: { integrationOperationProtocolType: "kafka" },
        })}
      />,
    );
    expect(props(MockSchemaField.mock.calls[0][0].schema)).toHaveProperty(
      "asyncProp",
    );
  });

  it("falls back to option without const or enum when no protocol set", () => {
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
    render(
      <ProtocolOneOfField
        {...makeProps({
          schema: schemaWithFallback,
          formContext: { integrationOperationProtocolType: undefined },
        })}
      />,
    );
    expect(props(MockSchemaField.mock.calls[0][0].schema)).toHaveProperty(
      "fallbackField",
    );
  });

  it("falls back to original OneOfField when no option matches an unknown protocol", () => {
    render(
      <ProtocolOneOfField
        {...makeProps({
          formContext: { integrationOperationProtocolType: "unknown" },
        })}
      />,
    );
    expect(MockSchemaField).not.toHaveBeenCalled();
    expect(MockOriginalOneOfField).toHaveBeenCalledTimes(1);
  });
});
