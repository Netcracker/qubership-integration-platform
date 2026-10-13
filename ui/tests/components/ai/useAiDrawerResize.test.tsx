/** @jest-environment jsdom */
import { fireEvent, render, screen } from "@testing-library/react";
import {
  DRAWER_WIDTH_STORAGE_KEY,
  useAiDrawerResize,
} from "../../../src/components/ai/useAiDrawerResize.ts";

it("should resize only the OpenCode panel and preserve Rocky's saved width", () => {
  localStorage.setItem(DRAWER_WIDTH_STORAGE_KEY, "600");
  jest.spyOn(window, "requestAnimationFrame").mockImplementation((callback) => {
    callback(0);
    return 1;
  });
  function Panels() {
    const { onResizeMouseDown } = useAiDrawerResize(
      true,
      "opencode-assistant-drawer-width",
    );
    return (
      <>
        <div className="ai-assistant-drawer">
          <div data-testid="rocky" className="ant-drawer-content-wrapper" />
        </div>
        <div className="ai-assistant-drawer opencode-assistant-drawer">
          <div data-testid="opencode" className="ant-drawer-content-wrapper">
            <button onMouseDown={onResizeMouseDown}>Resize</button>
          </div>
        </div>
      </>
    );
  }
  render(<Panels />);
  jest
    .spyOn(screen.getByTestId("opencode"), "getBoundingClientRect")
    .mockReturnValue({ right: 1000 } as DOMRect);
  fireEvent.mouseDown(screen.getByRole("button", { name: "Resize" }));
  fireEvent.mouseMove(document, { clientX: 300 });
  fireEvent.mouseUp(document);
  expect(screen.getByTestId("opencode").style.width).toBe("700px");
  expect(screen.getByTestId("rocky").style.width).toBe("");
  expect(localStorage.getItem(DRAWER_WIDTH_STORAGE_KEY)).toBe("600");
  expect(localStorage.getItem("opencode-assistant-drawer-width")).toBe("700");
  jest.restoreAllMocks();
});

it("should resize Rocky when the OpenCode panel was mounted first", () => {
  jest.spyOn(window, "requestAnimationFrame").mockImplementation((callback) => {
    callback(0);
    return 1;
  });
  function Panels() {
    const { onResizeMouseDown } = useAiDrawerResize(true);
    return (
      <>
        <div className="ai-assistant-drawer opencode-assistant-drawer">
          <div data-testid="opencode" className="ant-drawer-content-wrapper" />
        </div>
        <div className="ai-assistant-drawer">
          <div data-testid="rocky" className="ant-drawer-content-wrapper">
            <button onMouseDown={onResizeMouseDown}>Resize Rocky</button>
          </div>
        </div>
      </>
    );
  }
  render(<Panels />);
  jest
    .spyOn(screen.getByTestId("rocky"), "getBoundingClientRect")
    .mockReturnValue({ right: 1000 } as DOMRect);
  fireEvent.mouseDown(screen.getByRole("button", { name: "Resize Rocky" }));
  fireEvent.mouseMove(document, { clientX: 300 });
  fireEvent.mouseUp(document);
  expect(screen.getByTestId("rocky").style.width).toBe("700px");
  expect(screen.getByTestId("opencode").style.width).toBe("");
  jest.restoreAllMocks();
});
