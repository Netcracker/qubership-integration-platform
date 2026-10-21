/**
 * @jest-environment jsdom
 */

import { describe, expect, it, jest } from "@jest/globals";
import "@testing-library/jest-dom";
import { fireEvent, render, screen } from "@testing-library/react";

jest.mock("../../../src/icons/IconProvider.tsx", () => {
  const react = jest.requireActual<typeof import("react")>("react");
  return {
    OverridableIcon: ({ name }: { name?: string }) =>
      react.createElement("span", { "data-icon": name }),
  };
});

import { AiComposerActions } from "../../../src/components/ai/AiComposerActions.tsx";

describe("AiComposerActions", () => {
  it("should show Send and hide Stop when the turn is idle", () => {
    const onSend = jest.fn();
    render(
      <AiComposerActions
        isTurnInFlight={false}
        onAttach={jest.fn()}
        onSend={onSend}
        onAbort={jest.fn()}
      />,
    );

    const send = screen.getByRole("button", { name: "Send" });
    expect(send).toBeEnabled();
    expect(
      screen.queryByRole("button", { name: "Stop" }),
    ).not.toBeInTheDocument();

    fireEvent.click(send);
    expect(onSend).toHaveBeenCalledTimes(1);
  });

  it("should show Stop and disable Send when the turn is in flight", () => {
    const onAbort = jest.fn();
    const onSend = jest.fn();
    render(
      <AiComposerActions
        isTurnInFlight
        onAttach={jest.fn()}
        onSend={onSend}
        onAbort={onAbort}
      />,
    );

    expect(screen.getByRole("button", { name: "Send" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Stop" }));
    expect(onAbort).toHaveBeenCalledTimes(1);
    expect(onSend).not.toHaveBeenCalled();
  });
});
