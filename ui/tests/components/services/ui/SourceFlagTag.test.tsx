/**
 * @jest-environment jsdom
 */
import React from "react";
import { describe, it, expect, jest } from "@jest/globals";
import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import * as semanticColors from "../../../../src/theme/semanticColors";
import { SourceFlagTag } from "../../../../src/components/services/ui/SourceFlagTag";

describe("SourceFlagTag", () => {
  it("applies foregroundForBackground to tag style", () => {
    const spy = jest.spyOn(semanticColors, "foregroundForBackground");
    render(<SourceFlagTag source="http" toUpperCase />);
    expect(spy).toHaveBeenCalled();
    expect(screen.getByText("HTTP")).toBeInTheDocument();
    spy.mockRestore();
  });

  it("renders nothing when source missing", () => {
    const { container } = render(<SourceFlagTag />);
    expect(container.firstChild).toBeNull();
  });
});
