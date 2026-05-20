/**
 * @jest-environment jsdom
 */
import React from "react";
import { act, render, screen, renderHook } from "@testing-library/react";
import "@testing-library/jest-dom";
import {
  ChainFullscreenContextProvider,
  useChainFullscreenContext,
} from "../../src/pages/ChainFullscreenContext";

describe("ChainFullscreenContext", () => {
  it("useChainFullscreenContext returns null outside provider", () => {
    const { result } = renderHook(() => useChainFullscreenContext());
    expect(result.current).toBeNull();
  });

  it("provides default fullscreen value of false", () => {
    const Consumer = () => {
      const ctx = useChainFullscreenContext();
      return <span data-testid="value">{String(ctx?.fullscreen)}</span>;
    };

    render(
      <ChainFullscreenContextProvider>
        <Consumer />
      </ChainFullscreenContextProvider>,
    );

    expect(screen.getByTestId("value")).toHaveTextContent("false");
  });

  it("toggleFullscreen switches fullscreen state", () => {
    const Consumer = () => {
      const ctx = useChainFullscreenContext();
      return (
        <>
          <span data-testid="value">{String(ctx?.fullscreen)}</span>
          <button
            data-testid="toggle"
            onClick={ctx?.toggleFullscreen}
            type="button"
          >
            toggle
          </button>
        </>
      );
    };

    render(
      <ChainFullscreenContextProvider>
        <Consumer />
      </ChainFullscreenContextProvider>,
    );

    expect(screen.getByTestId("value")).toHaveTextContent("false");

    act(() => {
      screen.getByTestId("toggle").click();
    });
    expect(screen.getByTestId("value")).toHaveTextContent("true");

    act(() => {
      screen.getByTestId("toggle").click();
    });
    expect(screen.getByTestId("value")).toHaveTextContent("false");
  });
});
