/**
 * @jest-environment jsdom
 */
import { describe, it, expect, jest } from "@jest/globals";
import { render, screen, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import { FilterButton } from "../../../../src/components/table/filter/FilterButton";
import { FilterItem } from "../../../../src/components/table/filter/FilterItem";
import { StringFilterConditions } from "../../../../src/components/table/filter/filterTypes";

jest.mock("../../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`} />
  ),
}));

jest.mock(
  "../../../../src/components/table/filter/FilterButton.module.css",
  () => ({ wrapper: "wrapper" }),
);

describe("filter controls", () => {
  it("should name the icon-only Filters button when it renders", () => {
    const onClick = jest.fn();
    render(<FilterButton count={2} onClick={onClick} />);
    fireEvent.click(screen.getByRole("button", { name: "Filters" }));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it("should name the Column and Condition selects when a filter row renders", () => {
    render(
      <FilterItem
        state={{ id: "f1" }}
        filterColumns={[
          { id: "name", name: "Name", conditions: StringFilterConditions },
        ]}
        onRemove={jest.fn()}
        onDuplicate={jest.fn()}
        onValueChange={jest.fn()}
      />,
    );
    expect(
      screen.getByRole("combobox", { name: "Column" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("combobox", { name: "Condition" }),
    ).toBeInTheDocument();
  });
});
