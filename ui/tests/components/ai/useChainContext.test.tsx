/** @jest-environment jsdom */
import { act, renderHook, waitFor } from "@testing-library/react";
import type { Chain } from "../../../src/api/apiTypes.ts";
import { useChainContext } from "../../../src/components/ai/useChainContext.ts";
import { publishCatalogChange } from "../../../src/misc/catalog-change-events.ts";
const mockGetChain = jest.fn();
const mockFailed = jest.fn();
jest.mock("../../../src/api/api.ts", () => ({
  api: { getChain: (...args: unknown[]) => mockGetChain(...args) },
}));
jest.mock("../../../src/hooks/useNotificationService.tsx", () => ({
  useNotificationService: () => ({ requestFailed: mockFailed }),
}));
const chain = (name: string) =>
  ({ id: "a", name, elements: [], dependencies: [] }) as unknown as Chain;
beforeEach(() => {
  jest.clearAllMocks();
  history.replaceState(null, "", "/chains/a/graph");
  mockGetChain.mockResolvedValue(chain("Initial"));
});
it("waits for pending reads before preparing the next Rocky request", async () => {
  const view = renderHook(() => useChainContext());
  await waitFor(() => expect(view.result.current?.chain.name).toBe("Initial"));
  let finish: (value: Chain) => void = () => undefined;
  mockGetChain.mockReturnValueOnce(
    new Promise<Chain>((resolve) => {
      finish = resolve;
    }),
  );
  await act(async () =>
    publishCatalogChange({ chainIds: ["a"], chainsListChanged: true }),
  );
  let settled = false;
  const fresh = view.result.current!.getFreshContext!().then((value) => {
    settled = true;
    return value;
  });
  await act(async () => {
    await Promise.resolve();
  });
  expect(settled).toBe(false);
  await act(async () => finish(chain("Latest")));
  expect((await fresh).compactSchema.chainName).toBe("Latest");
});
it("does not replace a newer catalog response with an older initial read", async () => {
  let finish: (value: Chain) => void = () => undefined;
  mockGetChain.mockReturnValueOnce(
    new Promise<Chain>((resolve) => {
      finish = resolve;
    }),
  );
  const view = renderHook(() => useChainContext());
  mockGetChain.mockResolvedValueOnce(chain("Latest"));
  await act(async () =>
    publishCatalogChange({ chainIds: ["a"], chainsListChanged: true }),
  );
  await waitFor(() => expect(view.result.current?.chain.name).toBe("Latest"));
  await act(async () => finish(chain("Old")));
  expect(view.result.current?.chain.name).toBe("Latest");
});
it("preserves the visible context but refuses to send stale data after a failed refresh", async () => {
  const view = renderHook(() => useChainContext());
  await waitFor(() => expect(view.result.current).not.toBeNull());
  const failure = new Error("offline");
  mockGetChain.mockRejectedValueOnce(failure);
  await act(async () =>
    publishCatalogChange({ chainIds: ["a"], chainsListChanged: true }),
  );
  await waitFor(() => expect(mockFailed).toHaveBeenCalled());
  expect(view.result.current?.chain.name).toBe("Initial");
  await expect(view.result.current!.getFreshContext!()).rejects.toBe(failure);
});

it("refreshes the visible context through its public refresh action", async () => {
  const view = renderHook(() => useChainContext());
  await waitFor(() => expect(view.result.current?.chain.name).toBe("Initial"));
  mockGetChain.mockResolvedValueOnce(chain("Refreshed"));
  await act(async () => {
    await view.result.current!.refresh();
  });
  expect(view.result.current?.compactSchema.chainName).toBe("Refreshed");
});

it("rejects a failed manual refresh instead of returning stale context", async () => {
  const view = renderHook(() => useChainContext());
  await waitFor(() => expect(view.result.current?.chain.name).toBe("Initial"));
  mockGetChain.mockRejectedValueOnce("offline");
  await act(async () => {
    await expect(view.result.current!.refresh()).rejects.toBe("offline");
  });
  await expect(view.result.current!.getFreshContext!()).rejects.toThrow(
    "offline",
  );
  expect(view.result.current?.chain.name).toBe("Initial");
});

it("retries a failed catalog refresh through the notification action", async () => {
  const view = renderHook(() => useChainContext());
  await waitFor(() => expect(view.result.current?.chain.name).toBe("Initial"));
  mockGetChain.mockRejectedValueOnce("offline");
  await act(async () =>
    publishCatalogChange({ chainIds: ["a"], chainsListChanged: false }),
  );
  await waitFor(() => expect(mockFailed).toHaveBeenCalled());
  mockGetChain.mockResolvedValueOnce(chain("Recovered"));
  await act(async () => {
    mockFailed.mock.calls[0][2]();
  });
  await waitFor(() =>
    expect(view.result.current?.chain.name).toBe("Recovered"),
  );
  expect((await view.result.current!.getFreshContext!()).chain.name).toBe(
    "Recovered",
  );
});

it("discards an old context after leaving the chain page", async () => {
  const view = renderHook(() => useChainContext());
  await waitFor(() => expect(view.result.current?.chain.name).toBe("Initial"));
  const previous = view.result.current!;
  await act(async () => {
    history.pushState(null, "", "/services");
  });
  expect(view.result.current).toBeNull();
  await expect(previous.getFreshContext!()).rejects.toThrow(
    "The open chain context is not available.",
  );
});
