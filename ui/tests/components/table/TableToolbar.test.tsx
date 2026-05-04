/**
 * @jest-environment jsdom
 */

import "@testing-library/jest-dom";
import React from "react";
import { render, screen } from "@testing-library/react";
import { TableToolbar } from "../../../src/components/table/TableToolbar.tsx";

jest.mock("../../../src/components/table/CompactSearch.tsx", () => ({
  CompactSearch: jest.fn(
    (props: {
      value: string;
      onChange: (v: string) => void;
      placeholder?: string;
      allowClear?: boolean;
      onClear?: () => void;
      onSearchConfirm?: (v: string) => void;
      className?: string;
      style?: React.CSSProperties;
    }) => (
      <input
        data-testid="compact-search-mock"
        data-classname={props.className ?? ""}
        data-style-width={
          props.style?.width === undefined ? "" : String(props.style.width)
        }
        value={props.value}
        placeholder={props.placeholder}
        onChange={(e) => props.onChange(e.target.value)}
      />
    ),
  ),
}));

import { CompactSearch } from "../../../src/components/table/CompactSearch.tsx";

const CompactSearchMock = jest.mocked(CompactSearch);

describe("TableToolbar", () => {
  beforeEach(() => {
    CompactSearchMock.mockClear();
  });

  it("renders search, column settings, and actions in order", () => {
    const onChange = jest.fn();
    render(
      <TableToolbar
        variant="admin"
        search={{
          value: "q",
          onChange,
          placeholder: "Find…",
          allowClear: true,
        }}
        columnSettingsButton={
          <button type="button" data-testid="col-settings">
            cols
          </button>
        }
        actions={<button type="button">action-a</button>}
      />,
    );

    const root = screen.getByTestId("compact-search-mock").parentElement;
    expect(root).not.toBeNull();
    const buttons = root!.querySelectorAll("button");
    expect(buttons[0]).toHaveAttribute("data-testid", "col-settings");
    expect(buttons[1]).toHaveTextContent("action-a");
  });

  it("passes search props through to CompactSearch including callbacks", () => {
    const onChange = jest.fn();
    const onClear = jest.fn();
    const onSearchConfirm = jest.fn();

    render(
      <TableToolbar
        variant="chain-tab"
        search={{
          value: "hello",
          onChange,
          onClear,
          onSearchConfirm,
          placeholder: "Search…",
          allowClear: true,
          className: "extra-search",
          style: { width: 200 },
        }}
      />,
    );

    expect(CompactSearchMock).toHaveBeenCalled();
    const firstCall = CompactSearchMock.mock.calls[0];
    expect(firstCall).toBeDefined();
    const call = firstCall[0];
    expect(call.value).toBe("hello");
    expect(call.placeholder).toBe("Search…");
    expect(call.allowClear).toBe(true);
    expect(call.onChange).toBe(onChange);
    expect(call.onClear).toBe(onClear);
    expect(call.onSearchConfirm).toBe(onSearchConfirm);
    expect(call.className).toContain("extra-search");

    const input = screen.getByTestId("compact-search-mock");
    expect(input).toHaveAttribute("data-style-width", "200");
  });

  it("supports legacy leading, middle, and trailing slots", () => {
    render(
      <TableToolbar
        leading={<span data-testid="lead">L</span>}
        middle={<span data-testid="mid">M</span>}
        search={{
          value: "",
          onChange: jest.fn(),
          placeholder: "S",
        }}
        trailing={<span data-testid="trail">T</span>}
      />,
    );

    const root = screen.getByTestId("compact-search-mock").parentElement;
    expect(root?.textContent).toContain("L");
    expect(root?.textContent).toContain("M");
    expect(root?.textContent).toContain("T");
    expect(screen.getByTestId("lead")).toBeInTheDocument();
    expect(screen.getByTestId("mid")).toBeInTheDocument();
    expect(screen.getByTestId("trail")).toBeInTheDocument();
  });

  it("forwards data-testid to root Flex", () => {
    render(
      <TableToolbar
        data-testid="main-toolbar"
        search={{
          value: "",
          onChange: jest.fn(),
          placeholder: "p",
        }}
      />,
    );
    expect(screen.getByTestId("main-toolbar")).toBeInTheDocument();
  });

  it("merges actionsClassName onto actions Flex", () => {
    const { container } = render(
      <TableToolbar
        variant="admin"
        search={{
          value: "",
          onChange: jest.fn(),
          placeholder: "p",
        }}
        actions={<span>a</span>}
        actionsClassName="custom-actions"
      />,
    );
    const withCustom = container.querySelector(".custom-actions");
    expect(withCustom).toBeInTheDocument();
  });
});
