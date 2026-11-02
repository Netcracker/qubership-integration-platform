/**
 * @jest-environment jsdom
 */
import "@testing-library/jest-dom";
import { act, renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { NotificationApiProvider } from "../../src/components/notifications/contexts/NotificationApiContext";
import {
  NotificationLogProvider,
  useNotificationLog,
} from "../../src/components/notifications/contexts/NotificationLogContext";
import { useNotificationService } from "../../src/hooks/useNotificationService";

jest.mock("../../src/Modals", () => ({
  useModalsContext: () => ({ showModal: jest.fn() }),
}));

const notificationApi = {
  error: jest.fn(),
  info: jest.fn(),
  warning: jest.fn(),
};

function wrapper({ children }: { children: ReactNode }) {
  return (
    <NotificationApiProvider api={notificationApi as never}>
      <NotificationLogProvider>{children}</NotificationLogProvider>
    </NotificationApiProvider>
  );
}

describe("useNotificationService", () => {
  it("should record and show an error notification with and without a description", () => {
    const { result } = renderHook(
      () => ({ service: useNotificationService(), log: useNotificationLog() }),
      { wrapper },
    );

    act(() => {
      result.current.service.error("Chains were missing", "Check the file");
      result.current.service.error("Import failed");
    });

    const withDescription = {
      type: "error",
      message: "Chains were missing",
      description: "Check the file",
    };
    expect(notificationApi.error).toHaveBeenNthCalledWith(1, withDescription);
    expect(notificationApi.error).toHaveBeenNthCalledWith(2, {
      type: "error",
      message: "Import failed",
      description: undefined,
    });
    expect(result.current.log.history).toHaveLength(2);
    expect(result.current.log.history[0]).toEqual(withDescription);
  });
});
