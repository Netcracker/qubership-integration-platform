/**
 * @jest-environment jsdom
 */

import { render, screen } from "@testing-library/react";
import { describe, it, expect, jest } from "@jest/globals";
import { FieldProps } from "@rjsf/utils";

const mockScript = jest.fn(
  (_props: {
    value?: string;
    mode?: string;
    onChange?: (value: string) => void;
  }) => <div data-testid="mock-script" />,
);

jest.mock("../../../../../src/components/Script", () => ({
  __esModule: true,
  Script: mockScript,
}));

// DescriptionTooltipFieldTemplate pulls antd + @rjsf/utils at runtime
// (incl. the ESM-only @x0k/json-schema-merge), same reason
// ChainElementModification.test.tsx mocks field components instead.
jest.mock(
  "../../../../../src/components/modal/chain_element/DescriptionTooltipFieldTemplate",
  () => ({
    __esModule: true,
    DescriptionTooltipIcon: () => <span data-testid="tooltip-icon" />,
  }),
);

import JsonField from "../../../../../src/components/modal/chain_element/field/JsonField";

describe("JsonField", () => {
  beforeEach(() => {
    mockScript.mockClear();
  });

  it("should pass string formData to the editor and forward edits with the field path", () => {
    const formData = '{"type":"object"}';
    const onChange = jest.fn();
    const fieldPathId = { path: ["properties", "validationSchema"] };

    render(
      <JsonField
        {...({
          formData,
          onChange,
          fieldPathId,
          schema: {},
          uiSchema: {},
        } as unknown as FieldProps)}
      />,
    );

    expect(mockScript).toHaveBeenCalledTimes(1);
    expect(mockScript).toHaveBeenCalledWith(
      expect.objectContaining({
        value: formData,
        mode: "json",
      }),
      expect.anything(),
    );

    const calledProps = mockScript.mock.calls[0][0] as unknown as {
      onChange: (value: string) => void;
    };
    expect(calledProps.onChange).toEqual(expect.any(Function));

    calledProps.onChange('{"type":"object"}');

    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith(
      '{"type":"object"}',
      fieldPathId.path,
    );
  });

  it("should show the schema title when the title is set", () => {
    render(
      <JsonField
        {...({
          formData: "{}",
          onChange: jest.fn(),
          fieldPathId: { path: ["properties", "validationSchema"] },
          schema: { title: "Scheme" },
          uiSchema: {},
        } as unknown as FieldProps)}
      />,
    );

    expect(screen.getByText("Scheme")).toBeDefined();
  });
});
