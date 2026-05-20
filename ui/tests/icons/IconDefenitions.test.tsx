/**
 * @jest-environment jsdom
 */
import React from "react";
import { render } from "@testing-library/react";
import "@testing-library/jest-dom";
import { CodeOutlined } from "@ant-design/icons";
import { commonIcons, elementIcons } from "../../src/icons/IconDefenitions";

describe("IconDefenitions", () => {
  it("commonIcons maps stable keys to components", () => {
    expect(commonIcons.code).toBe(CodeOutlined);
    expect(typeof commonIcons.logo).toBe("function");
  });

  it("elementIcons includes http-trigger mapping", () => {
    const Icon = elementIcons["http-trigger"];
    expect(Icon).toBeDefined();
    expect(React.isValidElement(<Icon />)).toBe(true);
  });

  it("renders logo icon without throwing", () => {
    const Logo = commonIcons.logo;
    const { container } = render(<Logo />);
    expect(container.querySelector("svg")).toBeTruthy();
  });
});
