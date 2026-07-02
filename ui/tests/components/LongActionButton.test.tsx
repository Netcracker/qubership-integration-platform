/**
 * @jest-environment jsdom
 */
import React from "react";
import { describe, it, expect, jest } from "@jest/globals";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import { LongActionButton } from "../../src/components/LongActionButton";

describe("LongActionButton", () => {
  it("should call onSubmit when clicked", () => {
    const onSubmit = jest.fn(() => undefined);
    render(<LongActionButton onSubmit={onSubmit}>Run</LongActionButton>);
    fireEvent.click(screen.getByRole("button"));
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it("should show loading while pending and clear it when the promise settles", async () => {
    let resolvePending!: () => void;
    const pending = new Promise<void>((resolve) => {
      resolvePending = resolve;
    });
    const onSubmit = jest.fn(() => pending);

    render(<LongActionButton onSubmit={onSubmit}>Run</LongActionButton>);
    const button = screen.getByRole("button");

    fireEvent.click(button);
    expect(button).toHaveClass("ant-btn-loading");

    resolvePending();
    await waitFor(() => expect(button).not.toHaveClass("ant-btn-loading"));
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it("should not stay in loading when onSubmit is synchronous", () => {
    const onSubmit = jest.fn(() => undefined);
    render(<LongActionButton onSubmit={onSubmit}>Run</LongActionButton>);
    const button = screen.getByRole("button");

    fireEvent.click(button);
    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(button).not.toHaveClass("ant-btn-loading");
  });

  it("should clear loading when onSubmit throws synchronously", () => {
    const onSubmit = jest.fn(() => {
      throw new Error("boom");
    });
    render(<LongActionButton onSubmit={onSubmit}>Run</LongActionButton>);
    const button = screen.getByRole("button");

    expect(() => fireEvent.click(button)).not.toThrow();
    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(button).not.toHaveClass("ant-btn-loading");
  });

  it("should forward the ref to the underlying button element", () => {
    const ref = React.createRef<HTMLButtonElement>();
    render(
      <LongActionButton ref={ref} onSubmit={jest.fn(() => undefined)}>
        Run
      </LongActionButton>,
    );
    expect(ref.current).toBeInstanceOf(HTMLButtonElement);
    expect(ref.current).toBe(screen.getByRole("button"));
  });

  it("should expose a displayName for the forwarded component", () => {
    expect(LongActionButton.displayName).toBe("LongActionButton");
  });
});
