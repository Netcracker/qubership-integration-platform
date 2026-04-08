/**
 * @jest-environment jsdom
 */
import React from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";

jest.mock("react-resizable/css/styles.css", () => ({}));

jest.mock("../../../src/components/table/ResizableTitle.tsx", () => ({
  ResizableTitle: React.forwardRef<
    HTMLTableCellElement,
    React.ThHTMLAttributes<HTMLTableCellElement>
  >((props, ref) => {
    const {
      onResize: _onResize,
      onResizeStop: _onResizeStop,
      minResizeWidth: _minResizeWidth,
      resizeHandleZIndex: _resizeHandleZIndex,
      ...rest
    } = props as React.ThHTMLAttributes<HTMLTableCellElement> & {
      onResize?: unknown;
      onResizeStop?: unknown;
      minResizeWidth?: unknown;
      resizeHandleZIndex?: unknown;
    };
    return <th ref={ref} {...rest} />;
  }),
}));

jest.mock("antd", () => {
  const {
    antdMockWithLightweightTable,
  } = require("tests/helpers/antdMockWithLightweightTable");
  return antdMockWithLightweightTable();
});

import { KeyValuePropertiesTable } from "../../../src/components/dev_tools/KeyValuePropertiesTable";

describe("KeyValuePropertiesTable", () => {
  it("renders parameters header, count badge and rows", () => {
    render(
      <KeyValuePropertiesTable
        rows={[
          { key: "beta", value: "2" },
          { key: "alpha", value: "1" },
        ]}
      />,
    );

    expect(screen.getByText("Parameters")).toBeInTheDocument();
    expect(screen.getByText("2")).toBeInTheDocument();

    fireEvent.click(screen.getByText("Parameters"));

    expect(screen.getByText("Key")).toBeInTheDocument();
    expect(screen.getByText("Value")).toBeInTheDocument();
    expect(screen.getByText("alpha")).toBeInTheDocument();
    expect(screen.getByText("beta")).toBeInTheDocument();
  });
});
