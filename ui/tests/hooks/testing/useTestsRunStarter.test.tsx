/**
 * @jest-environment jsdom
 */
import React from "react";
import { describe, expect, it, beforeEach } from "@jest/globals";
import { act, renderHook } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { TestsRunSource } from "../../../src/api/apiTypes";

const mockStartTestsRun = jest.fn();

jest.mock("../../../src/api/api", () => ({
  api: {
    startTestsRun: (ids: string[], source?: TestsRunSource) =>
      mockStartTestsRun(ids, source),
  },
}));

const mockNotificationService = {
  requestFailed: jest.fn(),
  errorWithDetails: jest.fn(),
  info: jest.fn(),
  warning: jest.fn(),
};

jest.mock("../../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => mockNotificationService,
}));

import { useTestsRunStarter } from "../../../src/hooks/testing/useTestsRunStarter";

const wrapper = ({ children }: { children: React.ReactNode }) => (
  <MemoryRouter>{children}</MemoryRouter>
);

/**
 * Renders the starter over a fixed selection. The toolbar button is left out on
 * purpose: it carries a disabled attribute of its own, and the guard under test
 * is the one that holds when nothing is disabled yet.
 */
function renderStarter(collectTargetIds = () => Promise.resolve(["case-1"])) {
  return renderHook(() => useTestsRunStarter({ collectTargetIds }), {
    wrapper,
  });
}

describe("useTestsRunStarter", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockStartTestsRun.mockResolvedValue("run-7");
  });

  it("should start one run when a second call lands before the first answers", async () => {
    const { result } = renderStarter();

    await act(async () => {
      await Promise.all([result.current.startRun(), result.current.startRun()]);
    });

    expect(mockStartTestsRun).toHaveBeenCalledTimes(1);
    expect(mockStartTestsRun).toHaveBeenCalledWith(["case-1"], undefined);
  });

  it("should start the next run once the one before it has answered", async () => {
    const { result } = renderStarter();

    await act(async () => {
      await result.current.startRun();
    });
    await act(async () => {
      await result.current.startRun();
    });

    expect(mockStartTestsRun).toHaveBeenCalledTimes(2);
    expect(result.current.isStarting).toBe(false);
  });

  // A failed start leaves the action usable, so the guard is released on the way
  // out of a failure too.
  it("should start a run after one that failed", async () => {
    mockStartTestsRun.mockRejectedValueOnce(new Error("service is down"));
    const { result } = renderStarter();

    await act(async () => {
      await result.current.startRun();
    });
    expect(mockNotificationService.requestFailed).toHaveBeenCalledWith(
      "Failed to start a test run",
      expect.any(Error),
    );

    await act(async () => {
      await result.current.startRun();
    });

    expect(mockStartTestsRun).toHaveBeenCalledTimes(2);
  });

  it("should ask for nothing when the selection is empty", async () => {
    const { result } = renderStarter(() => Promise.resolve([]));

    await act(async () => {
      await result.current.startRun();
    });

    expect(mockStartTestsRun).not.toHaveBeenCalled();
    expect(result.current.isStarting).toBe(false);
  });
});
