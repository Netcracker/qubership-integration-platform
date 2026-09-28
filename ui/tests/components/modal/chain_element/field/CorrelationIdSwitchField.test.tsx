/**
 * @jest-environment jsdom
 */

import { fireEvent, render, screen } from "@testing-library/react";
import { describe, it, expect, jest } from "@jest/globals";
import { FieldProps } from "@rjsf/utils";
import CorrelationIdSwitchField from "../../../../../src/components/modal/chain_element/field/CorrelationIdSwitchField";

function renderSwitch(formData: boolean | undefined) {
  const onChange = jest.fn();
  render(
    <CorrelationIdSwitchField
      {...({
        formData,
        onChange,
        fieldPathId: { path: ["properties", "receiveCorrelationId"] },
        schema: { type: "boolean", title: "Receive correlation ID" },
      } as unknown as FieldProps<boolean>)}
    />,
  );
  return onChange;
}

describe("CorrelationIdSwitchField", () => {
  it("should clear the position and the name when the switch is turned off", () => {
    const onChange = renderSwitch(true);

    fireEvent.click(
      screen.getByRole("checkbox", { name: "Receive correlation ID" }),
    );

    expect(onChange.mock.calls).toEqual([
      [false, ["properties", "receiveCorrelationId"]],
      [undefined, ["properties", "correlationIdPosition"]],
      [undefined, ["properties", "correlationIdName"]],
    ]);
  });

  it("should change only the switch when it is turned on", () => {
    const onChange = renderSwitch(undefined);

    fireEvent.click(
      screen.getByRole("checkbox", { name: "Receive correlation ID" }),
    );

    expect(onChange.mock.calls).toEqual([
      [true, ["properties", "receiveCorrelationId"]],
    ]);
  });
});
