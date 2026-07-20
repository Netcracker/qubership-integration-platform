/**
 * @jest-environment jsdom
 */
import { describe, it, expect } from "@jest/globals";
import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import {
  MethodBadge,
  methodTagStyle,
} from "../../../../src/components/services/ui/MethodBadge";

describe("MethodBadge", () => {
  it("should color the badge by method when the method is known", () => {
    const style = methodTagStyle("GET");
    expect(style.background).toBe("#61affe");
    expect(style.color).toBe("#fff");
  });

  it("should fall back to a neutral color when the method is unknown", () => {
    const style = methodTagStyle("FOOBAR");
    expect(style.background).toBe("#d9d9d9");
  });

  it("should render an uppercase method tag when given a value", () => {
    render(<MethodBadge value="get" />);
    const tag = screen.getByText("GET");
    expect(tag).toBeInTheDocument();
    expect(tag).toHaveStyle({ background: "#61affe", color: "#fff" });
  });

  it("should render a neutral tag when the method has no known color", () => {
    render(<MethodBadge value="foobar" />);
    const tag = screen.getByText("FOOBAR");
    expect(tag).toHaveStyle({ background: "#d9d9d9" });
  });

  it("should apply a minimum width when the minWidth prop is provided", () => {
    render(<MethodBadge value="get" minWidth={80} />);
    expect(screen.getByText("GET")).toHaveStyle({ minWidth: "80px" });
  });

  it("should render a dash when no value is provided", () => {
    render(<MethodBadge value={undefined} />);
    expect(screen.getByText("-")).toBeInTheDocument();
  });
});
