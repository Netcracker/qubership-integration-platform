import type React from "react";
import { useCallback } from "react";
import { confirmAndRun } from "../../misc/confirm-utils.ts";
import { useNotificationService } from "../useNotificationService.tsx";

/** One destructive bulk action: what it asks before it runs, and what it runs. */
export type TestingBulkAction = {
  /** Confirmation title, such as "Delete Test Cases". */
  title: string;
  /** Confirmation body, given the phrase naming what the selection stands for. */
  content: (target: string) => string;
  run: (ids: string[]) => Promise<void>;
};

export type UseTestingBulkActionsOptions = {
  /** Plural noun a confirmation counts, such as "test cases". Carries no article. */
  entityName: string;
  /** Singular of it; only a confirmation over a selection of one reads it. */
  entityNameSingular?: string;
  /**
   * What a failure message names, when the noun alone does not read as one —
   * "Failed to export the validation errors". Defaults to `entityName`.
   */
  failureSubject?: string;
  selectedRowKeys: React.Key[];
  /** True when the selection reaches past the loaded page. */
  selectAllMatching?: boolean;
  /** Ids the selection stands for. Memoize it. */
  collectTargetIds: () => Promise<string[]>;
  /** Memoize it. */
  clearSelection: () => void;
  /** Memoize it. */
  refresh: () => void;
  /** Memoize it. */
  exportEntities: (ids: string[]) => Promise<void>;
  delete?: TestingBulkAction;
  cancel?: TestingBulkAction;
};

export type TestingBulkActions = {
  /** False while the selection is empty, which is when a bulk action has nothing to work on. */
  hasSelection: boolean;
  handleRefresh: () => void;
  handleExport: () => Promise<void>;
  /** Set only when a delete action was given. */
  handleDelete?: () => void;
  /** Set only when a cancel action was given. */
  handleCancel?: () => void;
};

/**
 * The toolbar actions every testing list offers over its selection: refresh,
 * export, and the destructive ones the list happens to have. They are handed out
 * separately, so a screen with nothing to delete takes the first two alone.
 *
 * The ids are collected at click time rather than read off the selection, which
 * is what lets a selection reaching past the loaded page cover the rows no
 * request has returned yet.
 */
export function useTestingBulkActions({
  entityName,
  entityNameSingular = entityName,
  failureSubject = entityName,
  selectedRowKeys,
  selectAllMatching = false,
  collectTargetIds,
  clearSelection,
  refresh,
  exportEntities,
  delete: deleteAction,
  cancel: cancelAction,
}: UseTestingBulkActionsOptions): TestingBulkActions {
  const notificationService = useNotificationService();
  const hasSelection = selectedRowKeys.length > 0;

  const handleRefresh = useCallback(() => {
    clearSelection();
    refresh();
  }, [clearSelection, refresh]);

  const handleExport = useCallback(async () => {
    if (!hasSelection) {
      return;
    }
    try {
      const ids = await collectTargetIds();
      if (ids.length > 0) {
        await exportEntities(ids);
      }
    } catch (error) {
      notificationService.requestFailed(
        `Failed to export ${failureSubject}`,
        error,
      );
    }
  }, [
    hasSelection,
    collectTargetIds,
    exportEntities,
    failureSubject,
    notificationService,
  ]);

  // A selection reaching past the loaded page has no count on this side, so the
  // phrase names the filters it was made under instead.
  const describeSelection = useCallback(() => {
    if (selectAllMatching) {
      return `all ${entityName} that match the filters`;
    }
    const count = selectedRowKeys.length;
    return `${count} ${count === 1 ? entityNameSingular : entityName}`;
  }, [selectAllMatching, selectedRowKeys, entityName, entityNameSingular]);

  const confirmAction = useCallback(
    (verb: string, action: TestingBulkAction) => {
      confirmAndRun({
        title: action.title,
        content: action.content(describeSelection()),
        onOk: async () => {
          try {
            const ids = await collectTargetIds();
            if (ids.length === 0) {
              return;
            }
            await action.run(ids);
            clearSelection();
            refresh();
          } catch (error) {
            notificationService.requestFailed(
              `Failed to ${verb} ${failureSubject}`,
              error,
            );
          }
        },
      });
    },
    [
      describeSelection,
      collectTargetIds,
      clearSelection,
      refresh,
      failureSubject,
      notificationService,
    ],
  );

  const handleDelete = useCallback(() => {
    if (deleteAction && hasSelection) {
      confirmAction("delete", deleteAction);
    }
  }, [deleteAction, hasSelection, confirmAction]);

  const handleCancel = useCallback(() => {
    if (cancelAction && hasSelection) {
      confirmAction("cancel", cancelAction);
    }
  }, [cancelAction, hasSelection, confirmAction]);

  return {
    hasSelection,
    handleRefresh,
    handleExport,
    handleDelete: deleteAction ? handleDelete : undefined,
    handleCancel: cancelAction ? handleCancel : undefined,
  };
}
