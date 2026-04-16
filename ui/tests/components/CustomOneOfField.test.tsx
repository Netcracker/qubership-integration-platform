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
import React from "react";
import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { FieldProps, RJSFSchema } from "@rjsf/utils";
import type { FormContext } from "../../src/components/modal/chain_element/ChainElementModificationContext";

jest.mock("../../src/misc/protocol-utils", () => ({
  normalizeProtocol: (val: string | undefined) =>
    val ? val.toLowerCase() : undefined,
}));

const MockOriginalOneOfField = jest.fn((_props: unknown) => (
  <div data-testid="original-oneof" />
));
const MockBeforeOneOfField = jest.fn((_props: unknown) => (
  <div data-testid="before-oneof" />
));
const MockHttpTriggerOneOfField = jest.fn((_props: unknown) => (
  <div data-testid="http-trigger-oneof" />
));
const MockProtocolOneOfField = jest.fn(
  (_props: unknown): React.ReactNode => null,
);

jest.mock("@rjsf/core", () => ({
  getDefaultRegistry: () => ({
    fields: { OneOfField: MockOriginalOneOfField },
  }),
}));

jest.mock(
  "../../src/components/modal/chain_element/field/oneof/BeforeOneOfField",
  () => ({
    __esModule: true,
    default: (p: unknown) => MockBeforeOneOfField(p),
  }),
);

jest.mock(
  "../../src/components/modal/chain_element/field/oneof/HttpTriggerOneOfField",
  () => ({
    __esModule: true,
    default: (p: unknown) => MockHttpTriggerOneOfField(p),
  }),
);

jest.mock(
  "../../src/components/modal/chain_element/field/oneof/ProtocolOneOfField",
  () => ({
    __esModule: true,
    default: (p: unknown) => MockProtocolOneOfField(p),
  }),
);

import CustomOneOfField from "../../src/components/modal/chain_element/field/CustomOneOfField";

type Props = FieldProps<Record<string, unknown>, RJSFSchema, FormContext>;

const oneOfSchema: RJSFSchema = {
  type: "object",
  oneOf: [
    { type: "object", properties: { a: { type: "string" } } },
    { type: "object", properties: { b: { type: "string" } } },
  ],
};

function makeProps(overrides: {
  elementType?: string;
  schema?: RJSFSchema;
  fieldPathId?: { $id?: string; path?: string[] };
}): Props {
  const {
    elementType = "service-call",
    schema = oneOfSchema,
    fieldPathId = { $id: "root_properties", path: ["properties"] },
  } = overrides;
  return {
    name: "properties",
    formData: {},
    onChange: jest.fn(),
    disabled: false,
    readonly: false,
    schema,
    uiSchema: {},
    registry: {
      formContext: { elementType } as FormContext,
      fields: {
        SchemaField: jest.fn(() => <div />),
      },
    } as unknown as Props["registry"],
    fieldPathId,
  } as unknown as Props;
}

beforeEach(() => {
  MockOriginalOneOfField.mockClear();
  MockBeforeOneOfField.mockClear();
  MockHttpTriggerOneOfField.mockClear();
  MockProtocolOneOfField.mockReset();
  MockProtocolOneOfField.mockImplementation(() => null);
});

describe("CustomOneOfField routing", () => {
  it("routes root_properties_before to BeforeOneOfField", () => {
    render(
      <CustomOneOfField
        {...makeProps({
          fieldPathId: {
            $id: "root_properties_before",
            path: ["properties", "before"],
          },
        })}
      />,
    );
    expect(MockBeforeOneOfField).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId("before-oneof")).toBeInTheDocument();
  });

  it("routes root_properties of http-trigger to HttpTriggerOneOfField", () => {
    render(
      <CustomOneOfField
        {...makeProps({ elementType: "http-trigger" })}
      />,
    );
    expect(MockHttpTriggerOneOfField).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId("http-trigger-oneof")).toBeInTheDocument();
  });

  it("delegates to ProtocolOneOfField for non-http-trigger root_properties", () => {
    MockProtocolOneOfField.mockImplementation(() => (
      <div data-testid="protocol-oneof" />
    ));
    render(<CustomOneOfField {...makeProps({})} />);
    expect(MockProtocolOneOfField).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId("protocol-oneof")).toBeInTheDocument();
    expect(MockOriginalOneOfField).not.toHaveBeenCalled();
  });

  it("falls back to Original OneOfField when schema has no oneOf", () => {
    render(
      <CustomOneOfField
        {...makeProps({ schema: { type: "object" } })}
      />,
    );
    expect(MockOriginalOneOfField).toHaveBeenCalledTimes(1);
  });

  it("falls back to Original OneOfField for an unrelated fieldPathId", () => {
    render(
      <CustomOneOfField
        {...makeProps({
          fieldPathId: { $id: "root_other", path: ["other"] },
        })}
      />,
    );
    expect(MockOriginalOneOfField).toHaveBeenCalledTimes(1);
    expect(MockBeforeOneOfField).not.toHaveBeenCalled();
    expect(MockHttpTriggerOneOfField).not.toHaveBeenCalled();
    expect(MockProtocolOneOfField).not.toHaveBeenCalled();
  });
});
