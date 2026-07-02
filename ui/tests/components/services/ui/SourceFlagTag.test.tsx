/**
 * @jest-environment jsdom
 */
import { describe, it, expect } from "@jest/globals";
import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { SourceFlagTag } from "../../../../src/components/services/ui/SourceFlagTag";

describe("SourceFlagTag", () => {
  it("renders a protocol tag lowercase with its fixed color and white label", () => {
    render(<SourceFlagTag source="HTTP" kind="protocol" />);
    const tag = screen.getByText("http");
    expect(tag).toBeInTheDocument();
    expect(tag).toHaveClass("qip-solid-tag");
    expect(tag).toHaveStyle({ color: "#fff", background: "#0068ff" });
  });

  it("capitalizes a source flag and keeps a white label", () => {
    render(<SourceFlagTag source="manual" />);
    const tag = screen.getByText("Manual");
    expect(tag).toHaveClass("qip-solid-tag");
    expect(tag).toHaveStyle({ color: "#fff", background: "#0068ff" });
  });

  it("renders nothing when source missing", () => {
    const { container } = render(<SourceFlagTag />);
    expect(container.firstChild).toBeNull();
  });
});
