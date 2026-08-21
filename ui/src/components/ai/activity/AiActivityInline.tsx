import { LoadingOutlined } from "@ant-design/icons";
import React, { useEffect, useMemo, useState } from "react";
import type { ActivityStepPayload } from "./activityTypes.ts";
import {
  buildActivitySummary,
  resolveActivityVisualKind,
  resolveDisplayedActivityStatus,
  visualKindBadgeLabel,
} from "./activitySummary.ts";

export interface AiActivityInlineProps {
  rows: ActivityStepPayload[];
  /** When true, parents start collapsed to the one-liner summary. */
  collapsed?: boolean;
  /** Optional turn duration for the live summary line. */
  durationMs?: number;
  /** Forced summary text (persisted); otherwise computed from rows. */
  summary?: string;
}

function statusIcon(status: ActivityStepPayload["status"]): React.ReactNode {
  switch (status) {
    case "running":
      return <LoadingOutlined className="ai-activity-inline__spinner" spin />;
    case "completed":
      return "✓";
    case "error":
      return "✕";
    case "cancelled":
      return "—";
    default: {
      const _exhaustive: never = status;
      return _exhaustive;
    }
  }
}

function statusClass(status: ActivityStepPayload["status"]): string {
  return `ai-activity-inline__row--${status}`;
}

function isTurnComplete(rows: ActivityStepPayload[]): boolean {
  if (rows.length === 0) return false;
  return rows.every((row) => row.status !== "running");
}

export const AiActivityInline: React.FC<AiActivityInlineProps> = ({
  rows,
  collapsed: collapsedProp,
  durationMs,
  summary: summaryProp,
}) => {
  const turnComplete = isTurnComplete(rows);
  const preferCollapsed = collapsedProp ?? turnComplete;
  const [detailsOpen, setDetailsOpen] = useState(!preferCollapsed);
  const [openParents, setOpenParents] = useState<Record<string, boolean>>({});

  useEffect(() => {
    if (preferCollapsed) {
      setDetailsOpen(false);
      setOpenParents({});
    } else {
      setDetailsOpen(true);
    }
  }, [preferCollapsed]);

  const { childRowsByParent, rootRows, orphanChildren } = useMemo(() => {
    const childRowsByParent = new Map<string, ActivityStepPayload[]>();
    const rootRows: ActivityStepPayload[] = [];
    const ids = new Set(rows.map((row) => row.id));

    for (const row of rows) {
      if (row.parentId && ids.has(row.parentId)) {
        const siblings = childRowsByParent.get(row.parentId) ?? [];
        siblings.push(row);
        childRowsByParent.set(row.parentId, siblings);
      } else if (!row.parentId) {
        rootRows.push(row);
      }
    }

    const orphanChildren = rows.filter(
      (row) => Boolean(row.parentId) && !ids.has(row.parentId as string),
    );

    return { childRowsByParent, rootRows, orphanChildren };
  }, [rows]);

  const summary = summaryProp ?? buildActivitySummary(rows, durationMs);

  if (rows.length === 0) {
    return null;
  }

  const isParentOpen = (id: string, hasChildren: boolean): boolean => {
    if (!hasChildren) return false;
    if (id in openParents) return openParents[id];
    // Live (expanded) mode: parents with children open by default.
    return !preferCollapsed;
  };

  const toggleParent = (id: string, hasChildren: boolean) => {
    if (!detailsOpen) {
      setDetailsOpen(true);
      setOpenParents({ [id]: true });
      return;
    }
    if (!hasChildren) return;
    setOpenParents((prev) => ({
      ...prev,
      [id]: !isParentOpen(id, true),
    }));
  };

  const renderLeaf = (row: ActivityStepPayload, nested: boolean) => {
    const visual = resolveActivityVisualKind(row.kind);
    const status = resolveDisplayedActivityStatus(row, rows);
    return (
      <div
        key={row.id}
        className={[
          "ai-activity-inline__row",
          nested ? "ai-activity-inline__row--child" : "ai-activity-inline__row--parent",
          statusClass(status),
          `ai-activity-inline__row--${visual}`,
        ].join(" ")}
      >
        {!nested ? (
          <span className="ai-activity-inline__chevron-spacer" aria-hidden />
        ) : null}
        <span className="ai-activity-inline__icon" aria-hidden>
          {statusIcon(status)}
        </span>
        <span className="ai-activity-inline__label">{row.label ?? row.id}</span>
        <span
          className={`ai-activity-inline__badge ai-activity-inline__badge--${visual}`}
        >
          {visualKindBadgeLabel(visual)}
        </span>
      </div>
    );
  };

  const renderParent = (row: ActivityStepPayload) => {
    const visual = resolveActivityVisualKind(row.kind);
    const status = resolveDisplayedActivityStatus(row, rows);
    const children = childRowsByParent.get(row.id) ?? [];
    const hasChildren = children.length > 0;
    const open = detailsOpen && isParentOpen(row.id, hasChildren);
    const canToggle = hasChildren;
    const parentRowClass = [
      "ai-activity-inline__row",
      "ai-activity-inline__row--parent",
      statusClass(status),
      `ai-activity-inline__row--${visual}`,
    ].join(" ");
    const parentRowBody = (
      <>
        {canToggle ? (
          <span
            className={`ai-activity-inline__chevron${open ? " ai-activity-inline__chevron--open" : ""}`}
            aria-hidden
          >
            ▸
          </span>
        ) : (
          <span className="ai-activity-inline__chevron-spacer" aria-hidden />
        )}
        <span className="ai-activity-inline__icon" aria-hidden>
          {statusIcon(status)}
        </span>
        <span className="ai-activity-inline__label">{row.label ?? row.id}</span>
        <span
          className={`ai-activity-inline__badge ai-activity-inline__badge--${visual}`}
        >
          {visualKindBadgeLabel(visual)}
        </span>
      </>
    );

    return (
      <div key={row.id} className="ai-activity-inline__card">
        {canToggle ? (
          <button
            type="button"
            className={parentRowClass}
            onClick={() => toggleParent(row.id, hasChildren)}
            aria-expanded={open}
          >
            {parentRowBody}
          </button>
        ) : (
          <div className={parentRowClass}>{parentRowBody}</div>
        )}
        {open ? (
          <div className="ai-activity-inline__children">
            {children.map((child) => renderLeaf(child, true))}
          </div>
        ) : null}
      </div>
    );
  };

  if (preferCollapsed && !detailsOpen) {
    return (
      <div className="ai-activity-inline ai-activity-inline--collapsed">
        <button
          type="button"
          className="ai-activity-inline__summary"
          onClick={() => {
            setDetailsOpen(true);
            const first = rootRows[0]?.id;
            setOpenParents(first ? { [first]: true } : {});
          }}
          aria-expanded={false}
        >
          <span className="ai-activity-inline__chevron" aria-hidden>
            ▸
          </span>
          <span className="ai-activity-inline__summary-text">{summary}</span>
        </button>
      </div>
    );
  }

  return (
    <div className="ai-activity-inline">
      {preferCollapsed ? (
        <button
          type="button"
          className="ai-activity-inline__summary ai-activity-inline__summary--expanded"
          onClick={() => {
            setDetailsOpen(false);
            setOpenParents({});
          }}
          aria-expanded
        >
          <span
            className="ai-activity-inline__chevron ai-activity-inline__chevron--open"
            aria-hidden
          >
            ▸
          </span>
          <span className="ai-activity-inline__summary-text">{summary}</span>
        </button>
      ) : null}
      <div className="ai-activity-inline__timeline">
        {rootRows.map((row) => {
          const children = childRowsByParent.get(row.id) ?? [];
          if (children.length > 0 || row.kind !== "tool") {
            return renderParent(row);
          }
          return renderLeaf(row, false);
        })}
        {orphanChildren.map((row) => renderLeaf(row, true))}
      </div>
    </div>
  );
};
