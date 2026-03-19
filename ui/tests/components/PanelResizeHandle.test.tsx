/**
 * @jest-environment jsdom
 */
import "@testing-library/jest-dom";
import { describe, it, expect, jest } from "@jest/globals";
import { render, fireEvent } from "@testing-library/react";
import { PanelResizeHandle } from "../../src/components/PanelResizeHandle";

describe("PanelResizeHandle", () => {
  it("renders resize button", () => {
    const { getByRole } = render(
      <PanelResizeHandle direction="left" onResize={jest.fn()} />,
    );
    expect(getByRole("button", { name: "Resize panel" })).toBeInTheDocument();
  });

  it("calls onResize when dragging right", () => {
    const onResize = jest.fn();
    const { getByRole } = render(
      <PanelResizeHandle direction="right" onResize={onResize} />,
    );

    fireEvent.mouseDown(getByRole("button", { name: "Resize panel" }), { clientX: 100 });
    fireEvent.mouseMove(document, { clientX: 90 });
    fireEvent.mouseUp(document);

    expect(onResize).toHaveBeenCalledWith(10);
  });

  it("calls onResizeEnd when drag ends", () => {
    const onResizeEnd = jest.fn();
    const { getByRole } = render(
      <PanelResizeHandle
        direction="left"
        onResize={jest.fn()}
        onResizeEnd={onResizeEnd}
      />,
    );

    fireEvent.mouseDown(getByRole("button", { name: "Resize panel" }), { clientX: 100 });
    fireEvent.mouseUp(document);

    expect(onResizeEnd).toHaveBeenCalledTimes(1);
  });

  describe("keyboard resize", () => {
    it("calls onResize with -10 when ArrowLeft is pressed and direction is left", () => {
      const onResize = jest.fn();
      const { getByRole } = render(
        <PanelResizeHandle direction="left" onResize={onResize} />,
      );
      const handle = getByRole("button", { name: "Resize panel" });
      handle.focus();

      fireEvent.keyDown(handle, { key: "ArrowLeft" });

      expect(onResize).toHaveBeenCalledTimes(1);
      expect(onResize).toHaveBeenCalledWith(-10);
    });

    it("calls onResize with 10 when ArrowLeft is pressed and direction is right", () => {
      const onResize = jest.fn();
      const { getByRole } = render(
        <PanelResizeHandle direction="right" onResize={onResize} />,
      );
      const handle = getByRole("button", { name: "Resize panel" });
      handle.focus();

      fireEvent.keyDown(handle, { key: "ArrowLeft" });

      expect(onResize).toHaveBeenCalledTimes(1);
      expect(onResize).toHaveBeenCalledWith(10);
    });

    it("calls onResize with 10 when ArrowRight is pressed and direction is left", () => {
      const onResize = jest.fn();
      const { getByRole } = render(
        <PanelResizeHandle direction="left" onResize={onResize} />,
      );
      const handle = getByRole("button", { name: "Resize panel" });
      handle.focus();

      fireEvent.keyDown(handle, { key: "ArrowRight" });

      expect(onResize).toHaveBeenCalledTimes(1);
      expect(onResize).toHaveBeenCalledWith(10);
    });

    it("calls onResize with -10 when ArrowRight is pressed and direction is right", () => {
      const onResize = jest.fn();
      const { getByRole } = render(
        <PanelResizeHandle direction="right" onResize={onResize} />,
      );
      const handle = getByRole("button", { name: "Resize panel" });
      handle.focus();

      fireEvent.keyDown(handle, { key: "ArrowRight" });

      expect(onResize).toHaveBeenCalledTimes(1);
      expect(onResize).toHaveBeenCalledWith(-10);
    });

    it("does not call onResize when other keys are pressed", () => {
      const onResize = jest.fn();
      const { getByRole } = render(
        <PanelResizeHandle direction="left" onResize={onResize} />,
      );
      const handle = getByRole("button", { name: "Resize panel" });
      handle.focus();

      fireEvent.keyDown(handle, { key: "Enter" });
      fireEvent.keyDown(handle, { key: "Space" });
      fireEvent.keyDown(handle, { key: "a" });

      expect(onResize).not.toHaveBeenCalled();
    });
  });
});
