/**
 * @jest-environment jsdom
 */
import { describe, it, expect, jest } from "@jest/globals";
import { render, fireEvent } from "@testing-library/react";
import { FullscreenButton } from "../../components/modal/FullscreenButton";

jest.mock("../../icons/IconProvider", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid="icon" data-icon={name} />
  ),
}));

describe("FullscreenButton", () => {
  it("renders fullscreen icon when not fullscreen", () => {
    const { getByTestId } = render(
      <FullscreenButton isFullscreen={false} onClick={jest.fn()} />,
    );
    expect(getByTestId("icon").getAttribute("data-icon")).toBe("fullscreen");
  });

  it("renders fullscreenExit icon when fullscreen", () => {
    const { getByTestId } = render(
      <FullscreenButton isFullscreen={true} onClick={jest.fn()} />,
    );
    expect(getByTestId("icon").getAttribute("data-icon")).toBe(
      "fullscreenExit",
    );
  });

  it("calls onClick when clicked", () => {
    const onClick = jest.fn();
    const { container } = render(
      <FullscreenButton isFullscreen={false} onClick={onClick} />,
    );
    fireEvent.click(container.querySelector("button")!);
    expect(onClick).toHaveBeenCalledTimes(1);
  });
});
