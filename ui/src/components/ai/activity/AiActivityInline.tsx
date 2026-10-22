import { LoadingOutlined } from "@ant-design/icons";
import React, { useEffect, useMemo, useState } from "react";
import type { ActivityStepPayload } from "./activityTypes.ts";
import {
  buildActivitySummary,
  resolveActivityVisualKind,
  resolveDisplayedActivityStatus,
  resolveErrorRecoveryParentId,
  shouldShowErrorRecoveryPass,
  shouldShowTurnContinuation,
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
  /** True while the chat turn is still in flight (Send stays unavailable). */
  inFlight?: boolean;
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

function hasRunningLlmStep(rows: ActivityStepPayload[]): boolean {
  return rows.some((row) => row.kind === "llm" && row.status === "running");
}

const LLM_WAIT_HINT_MS = 8000;
const LLM_WAIT_HINT = "Rocky is still working. No action is needed.";
const TURN_CONTINUATION_LABEL = "Thinking";
const ERROR_RECOVERY_LABEL = "Taking another pass";

function ThinkingDots(): React.ReactNode {
  return (
    <span className="ai-thinking-dots">
      <span className="ai-thinking-dot ai-thinking-dot--1">.</span>
      <span className="ai-thinking-dot ai-thinking-dot--2">.</span>
      <span className="ai-thinking-dot ai-thinking-dot--3">.</span>
    </span>
  );
}

export const AiActivityInline: React.FC<AiActivityInlineProps> = ({
  rows,
  collapsed: collapsedProp,
  durationMs,
  summary: summaryProp,
  inFlight = false,
}) => {
  const turnComplete = isTurnComplete(rows);
  const preferCollapsed = collapsedProp ?? turnComplete;
  const [detailsOpen, setDetailsOpen] = useState(!preferCollapsed);
  const [openParents, setOpenParents] = useState<Record<string, boolean>>({});
  const [showLlmWaitHint, setShowLlmWaitHint] = useState(false);
  const llmWaiting = hasRunningLlmStep(rows);

  useEffect(() => {
    if (preferCollapsed) {
      setDetailsOpen(false);
      setOpenParents({});
    } else {
      setDetailsOpen(true);
    }
  }, [preferCollapsed]);

  useEffect(() => {
    if (!llmWaiting) {
      setShowLlmWaitHint(false);
      return;
    }
    const timer = window.setTimeout(() => {
      setShowLlmWaitHint(true);
    }, LLM_WAIT_HINT_MS);
    return () => window.clearTimeout(timer);
  }, [llmWaiting]);

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
  const showContinuation = shouldShowTurnContinuation(rows, inFlight);
  const showErrorPass = shouldShowErrorRecoveryPass(rows, inFlight);
  const errorRecoveryParentId = showErrorPass
    ? resolveErrorRecoveryParentId(rows)
    : undefined;

  if (rows.length === 0) {
    return null;
  }

  const isParentOpen = (
    row: ActivityStepPayload,
    hasChildren: boolean,
  ): boolean => {
    if (!hasChildren) return false;
    if (row.id in openParents) return openParents[row.id];
    if (preferCollapsed) return false;
    if (resolveDisplayedActivityStatus(row, rows, inFlight) === "running") {
      return true;
    }
    const rowIndex = rows.findIndex((candidate) => candidate.id === row.id);
    if (rowIndex < 0) {
      return false;
    }
    const laterParentStarted = rows
      .slice(rowIndex + 1)
      .some(
        (candidate) =>
          candidate.kind === "skill" || candidate.kind === "pipeline",
      );
    return !laterParentStarted;
  };

  const toggleParent = (row: ActivityStepPayload, hasChildren: boolean) => {
    if (!detailsOpen) {
      setDetailsOpen(true);
      setOpenParents({ [row.id]: true });
      return;
    }
    if (!hasChildren) return;
    setOpenParents((prev) => ({
      ...prev,
      [row.id]: !isParentOpen(row, true),
    }));
  };

  const renderCopy = (
    row: ActivityStepPayload,
    status: ActivityStepPayload["status"],
  ) => {
    const showHint =
      showLlmWaitHint && row.kind === "llm" && status === "running";
    const showPassDots = row.kind === "llm" && status === "running";
    return (
      <span className="ai-activity-inline__copy">
        <span className="ai-activity-inline__label">
          {row.label ?? row.id}
          {showPassDots ? <ThinkingDots /> : null}
        </span>
        {showHint ? (
          <span className="ai-activity-inline__hint">{LLM_WAIT_HINT}</span>
        ) : null}
      </span>
    );
  };

  const renderLeaf = (row: ActivityStepPayload, nested: boolean) => {
    const visual = resolveActivityVisualKind(row.kind);
    const status = resolveDisplayedActivityStatus(row, rows, inFlight);
    return (
      <div
        key={row.id}
        className={[
          "ai-activity-inline__row",
          nested
            ? "ai-activity-inline__row--child"
            : "ai-activity-inline__row--parent",
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
        {renderCopy(row, status)}
        <span
          className={`ai-activity-inline__badge ai-activity-inline__badge--${visual}`}
        >
          {visualKindBadgeLabel(visual)}
        </span>
      </div>
    );
  };

  const renderErrorPass = () => (
    <div
      key="activity-error-pass"
      className="ai-activity-inline__row ai-activity-inline__row--child ai-activity-inline__row--pass ai-activity-inline__row--running"
    >
      <span className="ai-activity-inline__icon" aria-hidden>
        {statusIcon("running")}
      </span>
      <span className="ai-activity-inline__copy">
        <span className="ai-activity-inline__label">
          {ERROR_RECOVERY_LABEL}
          <ThinkingDots />
        </span>
      </span>
      <span className="ai-activity-inline__badge ai-activity-inline__badge--ai">
        {visualKindBadgeLabel("ai")}
      </span>
    </div>
  );

  const renderParent = (row: ActivityStepPayload) => {
    const visual = resolveActivityVisualKind(row.kind);
    const status = resolveDisplayedActivityStatus(row, rows, inFlight);
    const children = childRowsByParent.get(row.id) ?? [];
    const attachErrorPass = showErrorPass && errorRecoveryParentId === row.id;
    const hasChildren = children.length > 0 || attachErrorPass;
    const open = detailsOpen && isParentOpen(row, hasChildren);
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
        {renderCopy(row, status)}
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
            onClick={() => toggleParent(row, hasChildren)}
            aria-expanded={open}
          >
            {parentRowBody}
          </button>
        ) : (
          <div className={parentRowClass}>{parentRowBody}</div>
        )}
        {hasChildren ? (
          <div
            className={`ai-activity-inline__children${open ? " ai-activity-inline__children--open" : ""}`}
            aria-hidden={!open}
          >
            <div className="ai-activity-inline__children-inner">
              {children.map((child) => renderLeaf(child, true))}
              {attachErrorPass ? renderErrorPass() : null}
            </div>
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
          const attachErrorPass =
            showErrorPass && errorRecoveryParentId === row.id;
          if (
            children.length > 0 ||
            attachErrorPass ||
            (row.kind !== "tool" && row.kind !== "llm")
          ) {
            return renderParent(row);
          }
          return renderLeaf(row, false);
        })}
        {orphanChildren.map((row) => renderLeaf(row, true))}
      </div>
      {showErrorPass &&
      errorRecoveryParentId &&
      !rootRows.some((row) => row.id === errorRecoveryParentId)
        ? renderErrorPass()
        : null}
      <div
        className={`ai-activity-inline__thinking${
          showContinuation ? " ai-activity-inline__thinking--open" : ""
        }`}
        aria-hidden={!showContinuation}
      >
        <div className="ai-activity-inline__thinking-inner">
          {TURN_CONTINUATION_LABEL}
          <ThinkingDots />
        </div>
      </div>
    </div>
  );
};
