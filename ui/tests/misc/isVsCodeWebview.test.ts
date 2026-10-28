/** @jest-environment jsdom */
import { isVsCodeWebview } from "../../src/misc/isVsCodeWebview";

type WebviewWindow = Window & { acquireVsCodeApi?: unknown };

describe("isVsCodeWebview", () => {
  afterEach(() => {
    delete (window as WebviewWindow).acquireVsCodeApi;
  });

  it("should return true when acquireVsCodeApi is defined, though the page is served over http", () => {
    (window as WebviewWindow).acquireVsCodeApi = jest.fn();

    expect(window.location.protocol).toBe("http:");
    expect(isVsCodeWebview()).toBe(true);
  });

  it("should return false when acquireVsCodeApi is not defined", () => {
    expect(isVsCodeWebview()).toBe(false);
  });

  it("should return false when acquireVsCodeApi is not a function", () => {
    (window as WebviewWindow).acquireVsCodeApi = {};

    expect(isVsCodeWebview()).toBe(false);
  });
});
