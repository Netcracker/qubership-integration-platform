/**
 * @jest-environment jsdom
 */
import { renderHook } from "@testing-library/react";

const loadHttpTriggerAccessControl = jest.fn();

jest.mock("../../src/api/api", () => ({
  api: {
    loadHttpTriggerAccessControl: (...args: unknown[]) =>
      loadHttpTriggerAccessControl(...args),
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
  });

  it("loads once for a filter array the caller holds steady", async () => {
    // The fetch callback depends on filters, so a caller that rebuilds the array on every render
    // makes the hook reload without end.
    const filters: EntityFilterModel[] = [];
    const { unmount } = renderHook(() => useAccessControl(filters));

    await new Promise((resolve) => setTimeout(resolve, 300));

    expect(loadHttpTriggerAccessControl).toHaveBeenCalledTimes(1);
    unmount();
  });
});
