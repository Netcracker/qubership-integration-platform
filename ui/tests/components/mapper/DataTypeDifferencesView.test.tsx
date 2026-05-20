/**
 * @jest-environment jsdom
 */

import { render, screen } from "@testing-library/react";
import { DataTypeDifferencesView } from "../../../src/components/mapper/DataTypeDifferencesView";
import { Difference } from "../../../src/mapper/util/compare";
import "@testing-library/jest-dom";

describe("DataTypeDifferencesView", () => {
  it("renders added field correctly", () => {
    const difference: Difference = {
      path: ["root", "property"],
      first: null,
      second: {
        type: { name: "string" },
        definitions: [],
      },
    };
    render(<DataTypeDifferencesView differences={[difference]} />);
    // Check content
    expect(screen.getByText("root.property")).toBeInTheDocument();
    expect(screen.getByText("Added")).toBeInTheDocument();
  });

  it("renders changed field correctly", () => {
    const difference: Difference = {
      path: ["root", "property"],
      first: { type: { name: "string" }, definitions: [] },
      second: { type: { name: "number" }, definitions: [] },
      details: {
        feature: "type",
        first: "string",
        second: "number",
      },
    };
    render(<DataTypeDifferencesView differences={[difference]} />);
    expect(screen.getByText("root.property")).toBeInTheDocument();
    expect(screen.getByText("Changed")).toBeInTheDocument();
    expect(screen.getByText("type: string → number")).toBeInTheDocument();
  });

  it("renders removed field correctly", () => {
    const difference: Difference = {
      path: ["root", "property"],
      first: { type: { name: "string" }, definitions: [] },
      second: null,
    };
    render(<DataTypeDifferencesView differences={[difference]} />);
    expect(screen.getByText("root.property")).toBeInTheDocument();
    expect(screen.getByText("Removed")).toBeInTheDocument();
  });

  it("renders multiple differences", () => {
    const diff1: Difference = {
      path: ["root", "property"],
      first: null,
      second: {
        type: { name: "string" },
        definitions: [],
      },
      details: {
        feature: "type",
        first: "string",
        second: "number",
      },
    };
    const diff2: Difference = {
      path: ["another", "path"],
      first: { type: { name: "string" }, definitions: [] },
      second: null,
      details: { feature: "removed", first: "old", second: "new" },
    };
    render(<DataTypeDifferencesView differences={[diff1, diff2]} />);
    expect(screen.getByText("root.property")).toBeInTheDocument();
    expect(screen.getByText("another.path")).toBeInTheDocument();
    expect(screen.getAllByText("Added")).toHaveLength(1);
    expect(screen.getByText("Removed")).toBeInTheDocument();
  });
});
