/**
 * @jest-environment jsdom
 */

import "@testing-library/jest-dom";
import React from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import { ChainHeaderActions } from "../../src/pages/ChainHeaderActionsContext.tsx";
import { ChainHeaderActionsTestSlot } from "../helpers/renderWithChainHeader.tsx";

function SearchTab() {
  const [value, setValue] = React.useState("");
  return (
    <ChainHeaderActions>
      <input
        aria-label="search"
        value={value}
        onChange={(event) => setValue(event.target.value)}
      />
    </ChainHeaderActions>
  );
}

describe("ChainHeaderActions", () => {
  it("should render its children into the header slot", () => {
    render(
      <ChainHeaderActionsTestSlot>
        <ChainHeaderActions>
          <span data-testid="action">action</span>
        </ChainHeaderActions>
      </ChainHeaderActionsTestSlot>,
    );

    expect(screen.getByTestId("chain-header-slot")).toContainElement(
      screen.getByTestId("action"),
    );
  });

  it("should render nothing when outside a chain page", () => {
    render(
      <ChainHeaderActions>
        <span data-testid="action">action</span>
      </ChainHeaderActions>,
    );

    expect(screen.queryByTestId("action")).not.toBeInTheDocument();
  });

  // Issue #1119: the header received the toolbar one render after the tab, so
  // React wrote the previous value back into a header input after each
  // keystroke, and the browser moved the caret to the end.
  it("should not write a stale value when a header input changes", () => {
    render(
      <ChainHeaderActionsTestSlot>
        <SearchTab />
      </ChainHeaderActionsTestSlot>,
    );
    const input = screen.getByLabelText<HTMLInputElement>("search");
    const writes: string[] = [];
    // React tracks the value through a property on the node; record what it writes.
    const tracked = Object.getOwnPropertyDescriptor(input, "value")!;
    Object.defineProperty(input, "value", {
      configurable: true,
      get: tracked.get,
      set(value: string) {
        writes.push(value);
        tracked.set!.call(this, value);
      },
    });

    fireEvent.change(input, { target: { value: "a" } });

    expect(input).toHaveValue("a");
    expect(writes).not.toContain("");
  });
});
