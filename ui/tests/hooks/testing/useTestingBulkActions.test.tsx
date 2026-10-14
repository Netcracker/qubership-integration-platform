/**
 * @jest-environment jsdom
 */
import { beforeEach, describe, expect, it } from "@jest/globals";
import { act, renderHook } from "@testing-library/react";

const mockConfirmAndRun = jest.fn();

const mockNotificationService = {
  requestFailed: jest.fn(),
  errorWithDetails: jest.fn(),
  info: jest.fn(),
  warning: jest.fn(),
};

jest.mock("../../../src/misc/confirm-utils.ts", () => ({
  // Called, not referenced: the factory runs while the hook module loads, before
  // this suite's own imports are initialized.
  confirmAndRun: (options: unknown) => mockConfirmAndRun(options),
}));

jest.mock("../../../src/hooks/useNotificationService.tsx", () => ({
  useNotificationService: () => mockNotificationService,
}));

import type {
  TestingBulkAction,
  UseTestingBulkActionsOptions,
} from "../../../src/hooks/testing/useTestingBulkActions.ts";
import { useTestingBulkActions } from "../../../src/hooks/testing/useTestingBulkActions.ts";

/** The confirmation the hook opened, as the dialog received it. */
type OpenedConfirm = {
  title: string;
  content: string;
  onOk: () => Promise<void>;
};

function lastConfirm(): OpenedConfirm {
  const calls = mockConfirmAndRun.mock.calls as unknown as [OpenedConfirm][];
  return calls[calls.length - 1][0];
}

const collectTargetIds = jest.fn();
const clearSelection = jest.fn();
const refresh = jest.fn();
const exportEntities = jest.fn();
const deleteEntities = jest.fn();
const cancelEntities = jest.fn();

const deleteAction: TestingBulkAction = {
  title: "Delete Test Cases",
  content: (target) => `Delete ${target}? This cannot be undone.`,
  run: (ids) => deleteEntities(ids) as Promise<void>,
};

const cancelAction: TestingBulkAction = {
  title: "Cancel Test Cases",
  content: (target) => `Cancel ${target}?`,
  run: (ids) => cancelEntities(ids) as Promise<void>,
};

function renderActions(overrides: Partial<UseTestingBulkActionsOptions> = {}) {
  return renderHook(() =>
    useTestingBulkActions({
      entityName: "test cases",
      entityNameSingular: "test case",
      selectedRowKeys: ["case-1"],
      collectTargetIds,
      clearSelection,
      refresh,
      exportEntities,
      ...overrides,
    }),
  );
}

describe("useTestingBulkActions", () => {
  beforeEach(() => {
    collectTargetIds.mockResolvedValue(["case-1"]);
    exportEntities.mockResolvedValue(undefined);
    deleteEntities.mockResolvedValue(undefined);
    cancelEntities.mockResolvedValue(undefined);
  });

  it("should drop the selection before reloading when the list is refreshed", () => {
    const { result } = renderActions();

    act(() => {
      result.current.handleRefresh();
    });

    expect(clearSelection).toHaveBeenCalled();
    expect(refresh).toHaveBeenCalled();
  });

  it("should hand out only the actions it was given", () => {
    const { result } = renderActions({ delete: deleteAction });

    expect(result.current.handleDelete).toBeDefined();
    expect(result.current.handleCancel).toBeUndefined();
  });

  // A screen with nothing to delete must not be handed a live delete handler.
  it("should hand out no destructive action when it was given neither", () => {
    const { result } = renderActions({ cancel: cancelAction });

    expect(result.current.handleDelete).toBeUndefined();
    expect(result.current.handleCancel).toBeDefined();
  });
});

describe("useTestingBulkActions on an empty selection", () => {
  beforeEach(() => {
    collectTargetIds.mockResolvedValue([]);
  });

  it("should ask for no ids when the export is asked for", async () => {
    const { result } = renderActions({ selectedRowKeys: [] });

    expect(result.current.hasSelection).toBe(false);
    await act(async () => {
      await result.current.handleExport();
    });

    expect(collectTargetIds).not.toHaveBeenCalled();
    expect(exportEntities).not.toHaveBeenCalled();
  });

  it("should open no confirmation when a destructive action is asked for", () => {
    const { result } = renderActions({
      selectedRowKeys: [],
      delete: deleteAction,
      cancel: cancelAction,
    });

    act(() => {
      result.current.handleDelete?.();
      result.current.handleCancel?.();
    });

    expect(mockConfirmAndRun).not.toHaveBeenCalled();
  });
});

describe("useTestingBulkActions on a selection past the loaded page", () => {
  const allMatching = ["case-1", "case-2", "case-3"];

  beforeEach(() => {
    collectTargetIds.mockResolvedValue(allMatching);
    exportEntities.mockResolvedValue(undefined);
    deleteEntities.mockResolvedValue(undefined);
  });

  it("should export the ids the selection resolves to", async () => {
    const { result } = renderActions({ selectAllMatching: true });

    await act(async () => {
      await result.current.handleExport();
    });

    expect(exportEntities).toHaveBeenCalledWith(allMatching);
  });

  it("should delete the ids the selection resolves to", async () => {
    const { result } = renderActions({
      selectAllMatching: true,
      delete: deleteAction,
    });

    act(() => {
      result.current.handleDelete?.();
    });
    await act(async () => {
      await lastConfirm().onOk();
    });

    expect(deleteEntities).toHaveBeenCalledWith(allMatching);
    expect(clearSelection).toHaveBeenCalled();
    expect(refresh).toHaveBeenCalled();
  });

  it("should export nothing when the selection resolves to no id", async () => {
    collectTargetIds.mockResolvedValue([]);
    const { result } = renderActions({ selectAllMatching: true });

    await act(async () => {
      await result.current.handleExport();
    });

    expect(collectTargetIds).toHaveBeenCalled();
    expect(exportEntities).not.toHaveBeenCalled();
  });

  it("should run nothing when the selection resolves to no id", async () => {
    collectTargetIds.mockResolvedValue([]);
    const { result } = renderActions({
      selectAllMatching: true,
      delete: deleteAction,
    });

    act(() => {
      result.current.handleDelete?.();
    });
    await act(async () => {
      await lastConfirm().onOk();
    });

    expect(deleteEntities).not.toHaveBeenCalled();
    expect(refresh).not.toHaveBeenCalled();
  });

  it("should notify when the action fails", async () => {
    deleteEntities.mockRejectedValue(new Error("no connection"));
    const { result } = renderActions({
      selectAllMatching: true,
      delete: deleteAction,
    });

    act(() => {
      result.current.handleDelete?.();
    });
    await act(async () => {
      await lastConfirm().onOk();
    });

    expect(mockNotificationService.requestFailed).toHaveBeenCalledWith(
      "Failed to delete test cases",
      expect.any(Error),
    );
  });
});

describe("useTestingBulkActions confirmation wording", () => {
  beforeEach(() => {
    collectTargetIds.mockResolvedValue(["case-1"]);
  });

  it("should name the filters when the selection reaches past the loaded page", () => {
    const { result } = renderActions({
      selectAllMatching: true,
      delete: deleteAction,
    });

    act(() => {
      result.current.handleDelete?.();
    });

    expect(lastConfirm().title).toBe("Delete Test Cases");
    expect(lastConfirm().content).toBe(
      "Delete all test cases that match the filters? This cannot be undone.",
    );
  });

  it("should count the rows when the selection names several", () => {
    const { result } = renderActions({
      selectedRowKeys: ["case-1", "case-2"],
      delete: deleteAction,
    });

    act(() => {
      result.current.handleDelete?.();
    });

    expect(lastConfirm().content).toBe(
      "Delete 2 test cases? This cannot be undone.",
    );
  });

  it("should count the rows in the singular when the selection names one", () => {
    const { result } = renderActions({ delete: deleteAction });

    act(() => {
      result.current.handleDelete?.();
    });

    expect(lastConfirm().content).toBe(
      "Delete 1 test case? This cannot be undone.",
    );
  });

  it("should keep each action's own wording when a list carries two", () => {
    const { result } = renderActions({
      selectedRowKeys: ["case-1", "case-2"],
      delete: deleteAction,
      cancel: cancelAction,
    });

    act(() => {
      result.current.handleCancel?.();
    });

    expect(lastConfirm().title).toBe("Cancel Test Cases");
    expect(lastConfirm().content).toBe("Cancel 2 test cases?");
  });
});
