/** @jest-environment jsdom */
import { describe, it, expect, jest, afterEach } from "@jest/globals";
import { render } from "@testing-library/react";
import "@testing-library/jest-dom";
import { App, message as antdStaticMessage } from "antd";
import { AntdAppBridge, message, modal } from "../../src/misc/antd-app";

type AppApi = ReturnType<typeof App.useApp>;

// Captures the same context-aware api instances that AntdAppBridge registers,
// so the test can assert that the exported proxies route calls to them.
let capturedMessage: AppApi["message"] | null = null;
let capturedModal: AppApi["modal"] | null = null;

function Capture(): null {
  const app = App.useApp();
  capturedMessage = app.message;
  capturedModal = app.modal;
  return null;
}

describe("AntdAppBridge", () => {
  // Restore the antd static/context spies so their fake implementations do not
  // persist to any later test in this file (clearMocks resets calls, not impls).
  afterEach(() => {
    jest.restoreAllMocks();
  });

  it("should route the message and modal proxies to the App context api when mounted inside antd App", () => {
    render(
      <App>
        <Capture />
        <AntdAppBridge />
      </App>,
    );

    const contextMessage = capturedMessage;
    const contextModal = capturedModal;
    if (!contextMessage || !contextModal) {
      throw new Error("Expected the App context api to be captured");
    }

    const successSpy = jest
      .spyOn(contextMessage, "success")
      .mockImplementation((() => undefined) as never);
    const confirmSpy = jest
      .spyOn(contextModal, "confirm")
      .mockImplementation((() => ({ destroy() {}, update() {} })) as never);

    message.success("saved");
    modal.confirm({ title: "Delete?" });

    expect(successSpy).toHaveBeenCalledWith("saved");
    expect(confirmSpy).toHaveBeenCalledWith({ title: "Delete?" });
  });

  it("should fall back to the static message api when unmounted", () => {
    const { unmount } = render(
      <App>
        <Capture />
        <AntdAppBridge />
      </App>,
    );

    const contextMessage = capturedMessage;
    if (!contextMessage) {
      throw new Error("Expected the App context api to be captured");
    }

    const contextSuccess = jest
      .spyOn(contextMessage, "success")
      .mockImplementation((() => undefined) as never);
    const staticSuccess = jest
      .spyOn(antdStaticMessage, "success")
      .mockImplementation((() => undefined) as never);

    unmount();
    message.success("fallback");

    expect(contextSuccess).not.toHaveBeenCalled();
    expect(staticSuccess).toHaveBeenCalledWith("fallback");
  });
});
