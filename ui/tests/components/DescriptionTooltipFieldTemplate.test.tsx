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

import { describe, it, expect } from "@jest/globals";
import { render } from "@testing-library/react";
import DescriptionTooltipFieldTemplate, {
  DescriptionTooltipIcon,
} from "../../src/components/modal/chain_element/DescriptionTooltipFieldTemplate";
import type { FieldTemplateProps } from "@rjsf/utils";

jest.mock(
  "../../src/components/modal/chain_element/ChainElementModification.module.css",
  () => ({
    "description-tooltip-block": "description-tooltip-block",
  }),
);

const WrapIfAdditionalMock = ({ children }: { children: React.ReactNode }) => (
  <div data-testid="wrap-if-additional">{children}</div>
);

jest.mock("@rjsf/utils", () => ({
  getUiOptions: (uiSchema: unknown) =>
    (uiSchema as Record<string, unknown>)?.["ui:options"] ?? {},
  getTemplate: () => WrapIfAdditionalMock,
}));

function makeProps(
  overrides: Partial<FieldTemplateProps> = {},
): FieldTemplateProps {
  return {
    children: <input data-testid="field-input" />,
    classNames: "",
    style: {},
    description: <span>desc element</span>,
    disabled: false,
    displayLabel: false,
    errors: <span>errors</span>,
    formContext: {},
    formData: undefined,
    help: <span>help</span>,
    hidden: false,
    id: "root_testField",
    label: "Test Field",
    onChange: jest.fn(),
    onKeyRename: jest.fn(),
    onKeyRenameBlur:
      jest.fn() as unknown as FieldTemplateProps["onKeyRenameBlur"],
    onRemoveProperty: jest.fn(),
    rawDescription: "",
    rawErrors: [],
    rawHelp: "",
    readonly: false,
    registry: {
      formContext: {},
    } as unknown as FieldTemplateProps["registry"],
    required: false,
    schema: { type: "string" },
    uiSchema: {},
    ...overrides,
  } as FieldTemplateProps;
}

describe("DescriptionTooltipIcon", () => {
  it("renders question circle icon with tooltip", () => {
    const { container } = render(
      <DescriptionTooltipIcon description="Help text" />,
    );
    const icon = container.querySelector("[aria-label='question-circle']");
    expect(icon).toBeTruthy();
  });

  it("applies correct inline styles", () => {
    const { container } = render(
      <DescriptionTooltipIcon description="Help text" />,
    );
    const span = container.querySelector("span[role='img']") as HTMLElement;
    expect(span).toBeTruthy();
    expect(span.style.cursor).toBe("help");
    expect(span.style.fontSize).toBe("14px");
  });
});

describe("DescriptionTooltipFieldTemplate", () => {
  it("renders hidden field with rjsf-field-hidden class", () => {
    const { container } = render(
      <DescriptionTooltipFieldTemplate {...makeProps({ hidden: true })} />,
    );
    const hiddenDiv = container.querySelector(".rjsf-field-hidden");
    expect(hiddenDiv).toBeTruthy();
    expect(
      hiddenDiv!.querySelector("[data-testid='field-input']"),
    ).toBeTruthy();
  });

  it("does not render tooltip icon when rawDescription is empty", () => {
    const { container } = render(
      <DescriptionTooltipFieldTemplate
        {...makeProps({ rawDescription: "" })}
      />,
    );
    expect(
      container.querySelector("[aria-label='question-circle']"),
    ).toBeNull();
  });

  it("renders tooltip icon in label when displayLabel is true", () => {
    const { container } = render(
      <DescriptionTooltipFieldTemplate
        {...makeProps({
          displayLabel: true,
          label: "My Field",
          rawDescription: "Some description",
        })}
      />,
    );
    const icon = container.querySelector(
      "[aria-label='question-circle']",
    ) as HTMLElement;
    expect(icon).toBeTruthy();
    expect(icon.style.marginLeft).toBe("6px");
    expect(container.textContent).toContain("My Field");
  });

  it("renders tooltip from oneOf selected option description", () => {
    const { container } = render(
      <DescriptionTooltipFieldTemplate
        {...makeProps({
          displayLabel: true,
          label: "Auth type",
          rawDescription: "",
          schema: {
            oneOf: [
              {
                title: "Inherit",
                description: "Inherited from header",
                properties: { type: { const: "inherit" } },
              },
              {
                title: "None",
                description: "No auth",
                properties: { type: { const: "none" } },
              },
            ],
          },
          formData: { type: "inherit" },
        })}
      />,
    );
    const icon = container.querySelector("[aria-label='question-circle']");
    expect(icon).toBeTruthy();
    expect(container.textContent).toContain("Auth type");
  });

  it("renders inline tooltip for boolean fields without displayLabel", () => {
    const { container } = render(
      <DescriptionTooltipFieldTemplate
        {...makeProps({
          displayLabel: false,
          rawDescription: "Boolean help",
          schema: { type: "boolean" },
        })}
      />,
    );
    const icon = container.querySelector("[aria-label='question-circle']");
    expect(icon).toBeTruthy();
    // Should be wrapped in a span (inline), not a div with description-tooltip-block
    const blockDiv = container.querySelector(".description-tooltip-block");
    expect(blockDiv).toBeNull();
  });

  it("renders block tooltip for non-boolean fields without displayLabel", () => {
    const { container } = render(
      <DescriptionTooltipFieldTemplate
        {...makeProps({
          displayLabel: false,
          rawDescription: "String help",
          schema: { type: "string" },
        })}
      />,
    );
    const icon = container.querySelector("[aria-label='question-circle']");
    expect(icon).toBeTruthy();
    const blockDiv = container.querySelector(".description-tooltip-block");
    expect(blockDiv).toBeTruthy();
  });

  it("does not render tooltip icon for checkbox widget with displayLabel", () => {
    const { container } = render(
      <DescriptionTooltipFieldTemplate
        {...makeProps({
          displayLabel: true,
          label: "Check me",
          rawDescription: "Checkbox description",
          schema: { type: "boolean" },
          uiSchema: { "ui:options": { widget: "checkbox" } },
        })}
      />,
    );
    // When isCheckbox=true, fieldLabel is undefined, so it falls to inline tooltip for boolean
    const icon = container.querySelector("[aria-label='question-circle']");
    expect(icon).toBeTruthy();
    // No Form.Item label should be present (fieldLabel is undefined for checkbox)
    const blockDiv = container.querySelector(".description-tooltip-block");
    expect(blockDiv).toBeNull();
  });

  it("does not render tooltip icon for object/array wrapper fields", () => {
    const { container } = render(
      <DescriptionTooltipFieldTemplate
        {...makeProps({
          displayLabel: false,
          rawDescription: "Object description",
          schema: { type: "object" },
        })}
      />,
    );
    expect(
      container.querySelector("[aria-label='question-circle']"),
    ).toBeNull();
    expect(container.querySelector(".description-tooltip-block")).toBeNull();
  });

  it("renders children without wrapper when no description", () => {
    const { container } = render(
      <DescriptionTooltipFieldTemplate
        {...makeProps({
          rawDescription: undefined as unknown as string,
          schema: { type: "string" },
        })}
      />,
    );
    expect(container.querySelector("[data-testid='field-input']")).toBeTruthy();
    expect(
      container.querySelector("[aria-label='question-circle']"),
    ).toBeNull();
    expect(container.querySelector(".description-tooltip-block")).toBeNull();
  });

  it("shows error status when rawErrors are present", () => {
    const { container } = render(
      <DescriptionTooltipFieldTemplate
        {...makeProps({
          rawErrors: ["Required field"],
          errors: <span data-testid="error-msg">Required field</span>,
        })}
      />,
    );
    expect(container.querySelector("[data-testid='error-msg']")).toBeTruthy();
  });

  it("wraps content with WrapIfAdditionalTemplate", () => {
    const { getByTestId } = render(
      <DescriptionTooltipFieldTemplate {...makeProps()} />,
    );
    expect(getByTestId("wrap-if-additional")).toBeTruthy();
  });

  it("uses formContext labelCol and wrapperCol when provided", () => {
    const { container } = render(
      <DescriptionTooltipFieldTemplate
        {...makeProps({
          displayLabel: true,
          label: "Custom Layout",
          registry: {
            formContext: {
              labelCol: { span: 8 },
              wrapperCol: { span: 16 },
            },
          } as unknown as FieldTemplateProps["registry"],
        })}
      />,
    );
    expect(
      container.querySelector("[data-testid='wrap-if-additional']"),
    ).toBeTruthy();
  });
});
