/**
 * @jest-environment jsdom
 */
import { renderHook, waitFor, act } from "@testing-library/react";

const loadHttpTriggerAccessControl = jest.fn();
const updateHttpTriggerAccessControl = jest.fn();
const bulkDeployChainsAccessControl = jest.fn();

jest.mock("../../src/api/api", () => ({
  api: {
    loadHttpTriggerAccessControl: (...args: unknown[]) =>
      loadHttpTriggerAccessControl(...args),
    updateHttpTriggerAccessControl: (...args: unknown[]) =>
      updateHttpTriggerAccessControl(...args),
    bulkDeployChainsAccessControl: (...args: unknown[]) =>
      bulkDeployChainsAccessControl(...args),
  },
}));

// One object for every render: the real hook memoises it, and the fetch callback depends on it.
const notificationService = { info: jest.fn(), requestFailed: jest.fn() };
jest.mock("../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => notificationService,
}));

import { useAccessControl } from "../../src/hooks/useAccessControl";
import { EntityFilterModel } from "../../src/components/table/filter/filterTypes";

describe("useAccessControl", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    loadHttpTriggerAccessControl.mockResolvedValue({ offset: 0, roles: [] });
    updateHttpTriggerAccessControl.mockResolvedValue(undefined);
    bulkDeployChainsAccessControl.mockResolvedValue(undefined);
  });

  // A stable array: the default [] is a fresh reference on every render, and the hook refetches
  // on every change of it.
  const noFilters: EntityFilterModel[] = [];

  const ready = async () => {
    const { result } = renderHook(() => useAccessControl(noFilters));
    await waitFor(() => {
      expect(loadHttpTriggerAccessControl).toHaveBeenCalled();
    });
    return result;
  };

  it("passes the role batch straight through to the api", async () => {
    const result = await ready();
    const batch = [{ elementId: "elem-1", roles: ["reader"] }];

    await act(() => result.current.updateAccessControl(batch));

    expect(updateHttpTriggerAccessControl).toHaveBeenCalledWith(batch);
  });

  it("passes the chain ids straight through to the api", async () => {
    const result = await ready();

    await act(() => result.current.bulkDeployAccessControl(["chain-1"]));

    expect(bulkDeployChainsAccessControl.mock.calls[0][0]).toStrictEqual([
      "chain-1",
    ]);
  });

  it("lets a failed deploy reach the caller, which decides what to say", async () => {
    const result = await ready();
    const failure = new Error("engine unreachable");
    bulkDeployChainsAccessControl.mockRejectedValueOnce(failure);

    await expect(
      act(() => result.current.bulkDeployAccessControl(["chain-1"])),
    ).rejects.toThrow("engine unreachable");
  });
});
