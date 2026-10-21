/** @jest-environment jsdom */
import { act, renderHook, waitFor } from "@testing-library/react";
import { useCatalogRefresh } from "../../src/hooks/useCatalogRefresh.ts";
import { publishCatalogChange } from "../../src/misc/catalog-change-events.ts";

const signal = () =>
  publishCatalogChange({ chainIds: ["a"], chainsListChanged: true });
function deferred() {
  let resolve: () => void = () => undefined;
  const promise = new Promise<void>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

it("should merge queued signals and read again after an in-flight change", async () => {
  const gate = deferred();
  const load = jest.fn(async () => {
    if (load.mock.calls.length === 1) await gate.promise;
  });
  renderHook(() => useCatalogRefresh("a", () => true, load));
  await act(async () => {
    signal();
    signal();
  });
  expect(load).toHaveBeenCalledTimes(1);
  await act(async () => {
    signal();
    signal();
    gate.resolve();
  });
  await waitFor(() => expect(load).toHaveBeenCalledTimes(2));
});

it("should discard an old view response and remove its subscription on unmount", async () => {
  const gate = deferred();
  const applied: boolean[] = [];
  const load = jest.fn(async (isCurrent: () => boolean) => {
    await gate.promise;
    applied.push(isCurrent());
  });
  const view = renderHook(
    ({ key }) => useCatalogRefresh(key, () => true, load),
    { initialProps: { key: "a" } },
  );
  await act(async () => {
    signal();
  });
  view.rerender({ key: "b" });
  await act(async () => {
    gate.resolve();
  });
  expect(applied).toEqual([false]);
  view.unmount();
  await act(async () => {
    signal();
  });
  expect(load).toHaveBeenCalledTimes(1);
});

it("should retain pending changes until the local operation finishes", async () => {
  const load = jest.fn(async () => undefined);
  const view = renderHook(
    ({ blocked }) => useCatalogRefresh("a", () => true, load, blocked),
    {
      initialProps: { blocked: true },
    },
  );
  await act(async () => {
    signal();
    signal();
  });
  expect(load).not.toHaveBeenCalled();
  view.rerender({ blocked: false });
  await waitFor(() => expect(load).toHaveBeenCalledTimes(1));
});

it("should reread after a local operation starts during an existing read", async () => {
  const gate = deferred();
  const applied: boolean[] = [];
  const load = jest.fn(async (isCurrent: () => boolean) => {
    if (load.mock.calls.length === 1) await gate.promise;
    applied.push(isCurrent());
  });
  const view = renderHook(
    ({ blocked }) => useCatalogRefresh("a", () => true, load, blocked),
    {
      initialProps: { blocked: false },
    },
  );
  await act(async () => {
    signal();
  });
  view.rerender({ blocked: true });
  await act(async () => {
    gate.resolve();
  });
  expect(applied).toEqual([false]);
  view.rerender({ blocked: false });
  await waitFor(() => expect(applied).toEqual([false, true]));
});

it("should release the queue after a failed read and allow an explicit retry", async () => {
  const failure = new Error("read failed");
  const load = jest
    .fn<Promise<void>, []>()
    .mockRejectedValueOnce(failure)
    .mockResolvedValue(undefined);
  const view = renderHook(() => useCatalogRefresh("a", () => true, load));
  await act(async () => {
    await expect(view.result.current.refresh()).rejects.toBe(failure);
  });
  await act(async () => {
    await view.result.current.refresh();
  });
  expect(load).toHaveBeenCalledTimes(2);
});
