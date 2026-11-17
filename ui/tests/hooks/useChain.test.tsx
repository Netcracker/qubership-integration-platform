/** @jest-environment jsdom */
import { act, renderHook, waitFor } from "@testing-library/react";
import { useChain } from "../../src/hooks/useChain.tsx";
import { RestApiError, type Chain } from "../../src/api/apiTypes.ts";
const mockGet = jest.fn();
const mockNotifications = { requestFailed: jest.fn() };
jest.mock("../../src/api/api.ts", () => ({
  api: { getChain: (...args: unknown[]) => mockGet(...args) },
}));
jest.mock("../../src/hooks/useNotificationService.tsx", () => ({
  useNotificationService: () => mockNotifications,
}));
const chain = (name: string) => ({ id: "a", name }) as Chain;
beforeEach(() => {
  jest.clearAllMocks();
});
it("preserves the current chain when a background read fails", async () => {
  mockGet.mockResolvedValueOnce(chain("Current"));
  const view = renderHook(() => useChain("a"));
  await waitFor(() => expect(view.result.current.chain?.name).toBe("Current"));
  mockGet.mockRejectedValueOnce(new Error("offline"));
  await act(async () => {
    await expect(view.result.current.getChain(true)).rejects.toThrow("offline");
  });
  expect(view.result.current.chain?.name).toBe("Current");
  expect(view.result.current.error).toBeNull();
});
it("does not overwrite fresh data with a late initial response", async () => {
  let finish: (value: Chain) => void = () => undefined;
  mockGet.mockReturnValueOnce(
    new Promise<Chain>((resolve) => {
      finish = resolve;
    }),
  );
  const view = renderHook(() => useChain("a"));
  mockGet.mockResolvedValueOnce(chain("New"));
  await act(async () => {
    view.result.current.setChain(await view.result.current.getChain(true));
  });
  await act(async () => finish(chain("Old")));
  expect(view.result.current.chain?.name).toBe("New");
});
it("toasts a load failure by default", async () => {
  const failure = new Error("Property file not found");
  mockGet.mockRejectedValueOnce(failure);
  const view = renderHook(() => useChain("a"));
  await waitFor(() => expect(view.result.current.error).not.toBeNull());
  expect(mockNotifications.requestFailed).toHaveBeenCalledWith(
    "Failed to load chain",
    failure,
  );
});
it("does not toast a load failure when the caller shows it (silentLoadError)", async () => {
  mockGet.mockRejectedValueOnce(new Error("Property file not found"));
  const view = renderHook(() => useChain("a", { silentLoadError: true }));
  await waitFor(() => expect(view.result.current.error).not.toBeNull());
  expect(view.result.current.error?.message).toBe("Property file not found");
  expect(mockNotifications.requestFailed).not.toHaveBeenCalled();
});
it("clears an initial missing-chain error after a successful background retry", async () => {
  mockGet.mockRejectedValueOnce(new RestApiError("missing", 404));
  const view = renderHook(() => useChain("a"));
  await waitFor(() => expect(view.result.current.error).not.toBeNull());
  mockGet.mockResolvedValueOnce(chain("Recovered"));
  await act(async () => {
    view.result.current.setChain(await view.result.current.getChain(true));
  });
  expect(view.result.current.chain?.name).toBe("Recovered");
  expect(view.result.current.error).toBeNull();
});
