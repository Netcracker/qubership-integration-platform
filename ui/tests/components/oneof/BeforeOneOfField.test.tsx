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

const MockSchemaField = jest.fn(
  (props: { schema: RJSFSchema; [key: string]: unknown }) => (
    <div data-testid="schema-field">{JSON.stringify(props.schema)}</div>
  ),
);

import BeforeOneOfField from "../../../src/components/modal/chain_element/field/oneof/BeforeOneOfField";

type Props = FieldProps<Record<string, unknown>, RJSFSchema, FormContext>;

const beforeSchema: RJSFSchema = {
  type: "object",
  oneOf: [
    {
      title: "None",
      type: "object",
      properties: { type: { anyOf: [{ const: "none" }, { not: {} }] } },
    },
    {
      title: "Mapper",
      type: "object",
      properties: {
        type: { const: "mapper-2" },
        mappingDescription: { type: "object" },
      },
      required: ["type"],
    },
    {
      title: "Scripting",
      type: "object",
      properties: {
        type: { const: "script" },
        script: { type: "string" },
      },
      required: ["type"],
    },
  ],
};

function makeProps(overrides: { formData?: Record<string, unknown> } = {}): Props {
  const { formData = {} } = overrides;
  return {
    name: "before",
    formData,
    onChange: jest.fn(),
    disabled: false,
    readonly: false,
    schema: beforeSchema,
    uiSchema: {},
    registry: {
      formContext: {} as FormContext,
      fields: { SchemaField: MockSchemaField },
    } as unknown as Props["registry"],
    fieldPathId: {
      $id: "root_properties_before",
      path: ["properties", "before"],
    },
  } as unknown as Props;
}

beforeEach(() => {
  MockSchemaField.mockClear();
});

describe("BeforeOneOfField", () => {
  describe("initial selection", () => {
    it("picks None when formData is empty", () => {
      render(<BeforeOneOfField {...makeProps()} />);
      expect(screen.getByText("None")).toBeInTheDocument();
    });

    it("picks Mapper by explicit type discriminator", () => {
      render(
        <BeforeOneOfField
          {...makeProps({ formData: { type: "mapper-2" } })}
        />,
      );
      const passed = MockSchemaField.mock.calls[0][0].schema;
      expect(passed.title).toBe("Mapper");
    });

    it("picks Scripting by explicit type discriminator", () => {
      render(
        <BeforeOneOfField {...makeProps({ formData: { type: "script" } })} />,
      );
      expect(MockSchemaField.mock.calls[0][0].schema.title).toBe("Scripting");
    });

    it("infers Mapper when mappingDescription is present but type is missing", () => {
      render(
        <BeforeOneOfField
          {...makeProps({
            formData: { mappingDescription: { source: {} } },
          })}
        />,
      );
      expect(MockSchemaField.mock.calls[0][0].schema.title).toBe("Mapper");
    });

    it("infers Scripting when script is present but type is missing", () => {
      render(
        <BeforeOneOfField
          {...makeProps({ formData: { script: "return null;" } })}
        />,
      );
      expect(MockSchemaField.mock.calls[0][0].schema.title).toBe("Scripting");
    });
  });

  describe("type injection on mount", () => {
    it("persists inferred type when type is missing", () => {
      const p = makeProps({
        formData: { mappingDescription: { source: {} } },
      });
      render(<BeforeOneOfField {...p} />);
      expect(p.onChange).toHaveBeenCalledTimes(1);
      const [data] = (p.onChange as jest.Mock).mock.calls[0] as [
        Record<string, unknown>,
        ...unknown[],
      ];
      expect(data).toMatchObject({
        type: "mapper-2",
        mappingDescription: { source: {} },
      });
    });

    it("does not call onChange when type already matches inferred", () => {
      const p = makeProps({
        formData: { type: "mapper-2", mappingDescription: {} },
      });
      render(<BeforeOneOfField {...p} />);
      expect(p.onChange).not.toHaveBeenCalled();
    });

    it("does not call onChange for None (no inferred type)", () => {
      const p = makeProps({ formData: {} });
      render(<BeforeOneOfField {...p} />);
      expect(p.onChange).not.toHaveBeenCalled();
    });
  });

  describe("switching options", () => {
    async function switchTo(container: HTMLElement, title: string) {
      const selectEl = container.querySelector(".ant-select-selector")!;
      fireEvent.mouseDown(selectEl);
      await waitFor(() => {
        const option = document.querySelector(
          `.ant-select-item[title="${title}"]`,
        );
        expect(option).not.toBeNull();
        fireEvent.click(option!);
      });
    }

    it("clears mappingDescription and sets type when switching from Mapper to Scripting", async () => {
      const p = makeProps({
        formData: {
          type: "mapper-2",
          mappingDescription: { source: {} },
          extra: "keep",
        },
      });
      const { container } = render(<BeforeOneOfField {...p} />);
      (p.onChange as jest.Mock).mockClear();

      await switchTo(container, "Scripting");

      expect(p.onChange).toHaveBeenCalledTimes(1);
      const [data] = (p.onChange as jest.Mock).mock.calls[0] as [
        Record<string, unknown>,
        ...unknown[],
      ];
      expect(data).toMatchObject({ type: "script", extra: "keep" });
      expect(data).not.toHaveProperty("mappingDescription");
    });

    it("removes type when switching to None", async () => {
      const p = makeProps({
        formData: {
          type: "mapper-2",
          mappingDescription: { source: {} },
        },
      });
      const { container } = render(<BeforeOneOfField {...p} />);
      (p.onChange as jest.Mock).mockClear();

      await switchTo(container, "None");

      const [data] = (p.onChange as jest.Mock).mock.calls[0] as [
        Record<string, unknown>,
        ...unknown[],
      ];
      expect(data).not.toHaveProperty("type");
      expect(data).not.toHaveProperty("mappingDescription");
    });

    it("clears script-related fields when leaving Scripting", async () => {
      const p = makeProps({
        formData: {
          type: "script",
          script: "return null;",
          exportFileExtension: "groovy",
          propertiesToExportInSeparateFile: "script",
          propertiesFilename: "handler",
        },
      });
      const { container } = render(<BeforeOneOfField {...p} />);
      (p.onChange as jest.Mock).mockClear();

      await switchTo(container, "None");

      const [data] = (p.onChange as jest.Mock).mock.calls[0] as [
        Record<string, unknown>,
        ...unknown[],
      ];
      [
        "script",
        "exportFileExtension",
        "propertiesToExportInSeparateFile",
        "propertiesFilename",
        "type",
      ].forEach((k) => expect(data).not.toHaveProperty(k));
    });
  });
});
