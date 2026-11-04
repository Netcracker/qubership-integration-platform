/**
 * @jest-environment jsdom
 */
import { renderHook, waitFor } from "@testing-library/react";
import {
  setAiServiceEnabled,
  useIsAiServiceAvailable,
} from "../../src/ai/appConfig";
import { configure } from "../../src/appConfig";

describe("useIsAiServiceAvailable", () => {
  const fetchMock = jest.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    globalThis.fetch = fetchMock;
  });

  afterEach(() => {
    setAiServiceEnabled(true);
  });

  test("should report the service available when its health check answers ok", async () => {
    fetchMock.mockResolvedValue({ ok: true });

    const { result } = renderHook(() => useIsAiServiceAvailable());

    await waitFor(() => expect(result.current).toBe(true));
    expect(fetchMock).toHaveBeenCalledWith(
      `${window.location.origin}/q/health`,
      expect.anything(),
    );
  });

  test("should send no health check when the configuration turns the service off", () => {
    fetchMock.mockResolvedValue({ ok: true });
    configure({ aiServiceEnabled: false });

    const { result } = renderHook(() => useIsAiServiceAvailable());

    expect(fetchMock).not.toHaveBeenCalled();
    expect(result.current).toBe(false);
  });
});
