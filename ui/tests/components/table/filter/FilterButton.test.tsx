/**
 * @jest-environment jsdom
 */
import { describe, it, expect, jest } from "@jest/globals";
import { render, screen, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import { FilterButton } from "../../../../src/components/table/filter/FilterButton";

jest.mock("../../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`} />
  ),
}));

jest.mock(
  "../../../../src/components/table/filter/FilterButton.module.css",
  () => ({ wrapper: "wrapper" }),
);

describe("FilterButton", () => {
  it("should name the icon-only Filters button when it renders", () => {
    const onClick = jest.fn();
    render(<FilterButton count={2} onClick={onClick} />);
    fireEvent.click(screen.getByRole("button", { name: "Filters" }));
    expect(onClick).toHaveBeenCalledTimes(1);
  });
});
