/**
 * @jest-environment jsdom
 */
import { describe, it, expect } from "@jest/globals";
import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import {
  HttpMethod,
  methodTagStyle,
} from "../../../../src/components/services/ui/HttpMethod";

describe("HttpMethod", () => {
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
    render(<HttpMethod value="get" />);
    const tag = screen.getByText("GET");
    expect(tag).toBeInTheDocument();
    expect(tag).toHaveStyle({ background: "#61affe", color: "#fff" });
  });

  it("should render a neutral tag when the method has no known color", () => {
    render(<HttpMethod value="foobar" />);
    const tag = screen.getByText("FOOBAR");
    expect(tag).toHaveStyle({ background: "#d9d9d9" });
  });

  it("should apply a fixed width when the width prop is provided", () => {
    render(<HttpMethod value="get" width={80} />);
    expect(screen.getByText("GET")).toHaveStyle({ width: "80px" });
  });

  it("should render a dash when no value is provided", () => {
    render(<HttpMethod value={undefined} />);
    expect(screen.getByText("-")).toBeInTheDocument();
  });
});
