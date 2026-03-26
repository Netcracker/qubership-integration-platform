/**
 * @jest-environment jsdom
 */

import "@testing-library/jest-dom";
import { render, screen, fireEvent } from "@testing-library/react";
import { treeExpandIcon } from "../../../src/components/table/TreeExpandIcon";

jest.mock("../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name, ...props }: { name: string }) => (
    <span data-testid={`icon-${name}`} {...props} />
  ),
}));

type TestRecord = { id: string; name: string };

describe("treeExpandIcon", () => {
  const mockOnExpand = jest.fn();
  const mockRecord: TestRecord = { id: "1", name: "test" };

  const ExpandIcon = treeExpandIcon<TestRecord>();

  const renderIcon = (props: {
    expanded: boolean;
    expandable: boolean;
    record?: TestRecord;
  }) => {
    const element = ExpandIcon({
      prefixCls: "ant-table",
      expanded: props.expanded,
      expandable: props.expandable,
      record: props.record ?? mockRecord,
      onExpand: mockOnExpand,
    });
    return render(<>{element}</>);
  };

  beforeEach(() => {
    mockOnExpand.mockClear();
  });

  it("renders right icon when collapsed", () => {
    renderIcon({ expanded: false, expandable: true });
    expect(screen.getByTestId("icon-right")).toBeInTheDocument();
    expect(screen.queryByTestId("icon-down")).not.toBeInTheDocument();
  });

  it("renders down icon when expanded", () => {
    renderIcon({ expanded: true, expandable: true });
    expect(screen.getByTestId("icon-down")).toBeInTheDocument();
    expect(screen.queryByTestId("icon-right")).not.toBeInTheDocument();
  });

  it("renders spacer when not expandable", () => {
    const { container } = renderIcon({ expanded: false, expandable: false });
    expect(screen.queryByTestId("icon-right")).not.toBeInTheDocument();
    expect(screen.queryByTestId("icon-down")).not.toBeInTheDocument();
    const spacer = container.firstChild as HTMLElement;
    expect(spacer.tagName).toBe("SPAN");
  });

  it("calls onExpand with record on click", () => {
    renderIcon({ expanded: false, expandable: true });
    fireEvent.click(screen.getByRole("button"));
    expect(mockOnExpand).toHaveBeenCalledWith(mockRecord, expect.any(Object));
  });

  it("stops event propagation on click", () => {
    renderIcon({ expanded: false, expandable: true });
    const button = screen.getByRole("button");
    const event = new MouseEvent("click", { bubbles: true });
    const stopPropagation = jest.spyOn(event, "stopPropagation");
    button.dispatchEvent(event);
    expect(stopPropagation).toHaveBeenCalled();
  });

  it("returns a function", () => {
    expect(typeof treeExpandIcon()).toBe("function");
  });
});
