/**
 * @jest-environment jsdom
 */

import { render } from "@testing-library/react";
import { describe, it, expect, jest } from "@jest/globals";
import { FieldProps } from "@rjsf/utils";

const mockScript = jest.fn(({ value, mode, onChange }) => (
  // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
  <div data-testid="mock-script" data-value={value} data-mode={mode} />
));

jest.mock("../../../../../src/components/Script", () => ({
  __esModule: true,
  Script: mockScript,
}));

import { JsonAsStringField } from "../../../../../src/components/modal/chain_element/field/JsonAsStringField";

describe("JsonAsStringField", () => {
  beforeEach(() => {
    mockScript.mockClear();
  });

  it("passes formData as value and uses json mode", () => {
    const formData = '{"key":"value"}';
    const onChange = jest.fn();

    render(
      <JsonAsStringField
        {...({
          formData,
          onChange,
          fieldPathId: { path: ["properties"] },
        } as unknown as FieldProps)}
      />,
    );

    expect(mockScript).toHaveBeenCalledTimes(1);
    expect(mockScript).toHaveBeenCalledWith(
      expect.objectContaining({
        value: formData,
        mode: "json",
        onChange: expect.any(Function),
      }),
      expect.anything(),
    );
  });

  it("forwards Script onChange to the field onChange with fieldPathId.path", () => {
    const formData = '{"hello":"world"}';
    const onChange = jest.fn();
    const fieldPathId = { path: ["properties", "0"] };

    render(
      <JsonAsStringField
        {...({ formData, onChange, fieldPathId } as unknown as FieldProps)}
      />,
    );

    expect(mockScript).toHaveBeenCalledTimes(1);
    const calledProps = mockScript.mock.calls[0][0] as {
      onChange: (value: string, path?: string[]) => void;
    };

    calledProps.onChange('{"updated":true}', fieldPathId.path);

    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith('{"updated":true}', fieldPathId.path);
  });
});
