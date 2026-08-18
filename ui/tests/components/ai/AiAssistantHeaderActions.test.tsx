/**
 * @jest-environment jsdom
 */

import { describe, expect, it, jest } from "@jest/globals";
import "@testing-library/jest-dom";
import { fireEvent, render, screen } from "@testing-library/react";

jest.mock("../../../src/misc/confirm-utils", () => ({
  confirmAndRun: jest.fn(({ onOk }: { onOk?: () => void }) => onOk?.()),
}));

jest.mock("../../../src/icons/IconProvider.tsx", () => {
  const react = jest.requireActual<typeof import("react")>("react");
  return {
    OverridableIcon: ({ name }: { name?: string }) =>
      react.createElement("span", { "data-icon": name }),
  };
});

import { confirmAndRun } from "../../../src/misc/confirm-utils";
import { AiAssistantHeaderActions } from "../../../src/components/ai/AiAssistantHeaderActions.tsx";

const confirmAndRunMock = confirmAndRun as jest.MockedFunction<
  typeof confirmAndRun
>;

describe("AiAssistantHeaderActions", () => {
  it("should call confirmAndRun with Clear this chat? when Clear is clicked", () => {
    const onClearChat = jest.fn();
    render(
      <AiAssistantHeaderActions
        onNewChat={jest.fn()}
        onClearChat={onClearChat}
        clearDisabled={false}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Clear" }));

    expect(confirmAndRunMock).toHaveBeenCalledWith(
      expect.objectContaining({
        title: "Clear this chat?",
        okText: "Clear",
      }),
    );
    expect(onClearChat).toHaveBeenCalledTimes(1);
  });

  it("should disable Clear when the turn is in flight", () => {
    render(
      <AiAssistantHeaderActions
        onNewChat={jest.fn()}
        onClearChat={jest.fn()}
        clearDisabled
      />,
    );

    expect(screen.getByRole("button", { name: "Clear" })).toBeDisabled();
  });
});
