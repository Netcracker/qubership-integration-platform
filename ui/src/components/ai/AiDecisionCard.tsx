import { Button, Input, List, Radio, Space, Typography } from "antd";
import React, { useId, useRef, useState } from "react";
import type {
  CatalogSystemType,
  ChatDecision,
  ChatDecisionSpec,
} from "../../ai/modelProviders/types.ts";
import { MarkdownRenderer } from "./AiMarkdownRenderer.tsx";
import {
  decisionCardText,
  isBlankClarifyHalt,
  recoveryCardActions,
  visibleMissingEvidence,
} from "./chatDecisionUtils.ts";

/**
 * Action labels live here rather than on the wire: the question is server-authored in the language
 * of the conversation, the buttons are interface vocabulary. A new pipeline declaring a gate needs
 * no change here as long as it reuses these action names.
 */
const ACTION_LABELS: Record<string, string> = {
  approve: "Approve",
  "approve-and-create": "Approve and create chain",
  "apply-chain-patch": "Apply",
  "create-chain": "Create chain",
  "import-specification": "Import specification",
  "import-specification-internal": "Import as internal",
  "import-specification-external": "Import as external",
  clarify: "Clarify",
  "request-changes": "Request changes",
  "deploy-chain": "Deploy",
  "cancel-deploy": "Not now",
  "redeploy-chain": "Redeploy",
  "cancel-redeploy": "Keep current deployment",
  "undeploy-chain": "Undeploy",
  "cancel-undeploy": "Keep deployed",
  "refresh-deployment": "Refresh status",
  "propose-deployment-fix": "Propose a fix",
  "dismiss-deployment-failure": "Not now",
  "create-maas-kafka-topics": "Create topics",
  "dismiss-maas-kafka-topics": "Not now",
  "session-logging-off": "Off",
  "session-logging-error": "Error",
  "session-logging-info": "Info",
  "session-logging-debug": "Debug",
  yes: "Yes",
  no: "No",
  pass_through: "Pass through",
  describe_mappings: "Describe mappings",
  retry: "Retry",
  revise: "Revise",
  "retry-creation": "Retry creation",
  "edit-requirements": "Edit requirements",
  "rebuild-plan": "Rebuild plan",
  "stop-with-report": "End run and keep report",
};

/**
 * Actions the server runs as a typed command against the run. Everything else is an answer the
 * stage reads, so it travels as an ordinary message.
 */
const COMMAND_ACTIONS = new Set([
  "approve",
  "approve-and-create",
  "create-chain",
  "apply-chain-patch",
  "deploy-chain",
  "cancel-deploy",
  "redeploy-chain",
  "cancel-redeploy",
  "undeploy-chain",
  "cancel-undeploy",
  "refresh-deployment",
  "propose-deployment-fix",
  "dismiss-deployment-failure",
  "create-maas-kafka-topics",
  "dismiss-maas-kafka-topics",
  "session-logging-off",
  "session-logging-error",
  "session-logging-info",
  "session-logging-debug",
  "import-specification",
  "import-specification-internal",
  "import-specification-external",
  "retry",
  "revise",
  "retry-creation",
  "edit-requirements",
  "rebuild-plan",
  "stop-with-report",
]);

/** Actions that run the primary command of their gate. */
const PRIMARY_ACTIONS = new Set([
  "approve",
  "approve-and-create",
  "apply-chain-patch",
  "create-chain",
  "import-specification",
  "import-specification-internal",
  "import-specification-external",
  "deploy-chain",
  "redeploy-chain",
  "undeploy-chain",
  "refresh-deployment",
  "propose-deployment-fix",
  "create-maas-kafka-topics",
  "yes",
  "pass_through",
  "retry",
  "revise",
  "retry-creation",
  "edit-requirements",
  "rebuild-plan",
]);

function actionLabel(action: string): string {
  return ACTION_LABELS[action] ?? action;
}

/** Drop identifiers the interface has no label for, including internal pipeline stage ids. */
function labeledActions(actions: string[]): string[] {
  return actions.filter((action) => ACTION_LABELS[action] !== undefined);
}

const LEGACY_IMPORT_ACTIONS = new Set([
  "import-specification",
  "import-specification-internal",
  "import-specification-external",
]);

function defaultSpecSystemType(spec: ChatDecisionSpec): CatalogSystemType {
  return spec.systemType === "EXTERNAL" ? "EXTERNAL" : "INTERNAL";
}

function initialSpecSystemTypes(
  specs: ChatDecisionSpec[],
): Record<string, CatalogSystemType> {
  return Object.fromEntries(
    specs.map((spec) => [spec.s3Key, defaultSpecSystemType(spec)]),
  );
}

function perSpecImportActions(actions: string[]): string[] {
  const rest = labeledActions(actions).filter(
    (action) => !LEGACY_IMPORT_ACTIONS.has(action),
  );
  return ["import-specification", ...rest];
}

function specSystemTypesMap(
  specs: ChatDecisionSpec[],
  selected: Record<string, CatalogSystemType>,
): Record<string, CatalogSystemType> {
  return Object.fromEntries(
    specs.map((spec) => [spec.s3Key, selected[spec.s3Key] ?? "INTERNAL"]),
  );
}

function commentPlaceholder(
  isMappingGapClarify: boolean,
  isFreeTextClarify: boolean,
): string {
  if (isMappingGapClarify) {
    return "One rule per line: 1: $.source -> $.target";
  }
  if (isFreeTextClarify) {
    return "Provide the missing information";
  }
  return "Add a comment (optional)";
}

function answeredLabel(decision: ChatDecision): string {
  const answered = decision.answeredAction;
  if (answered === undefined) {
    return "";
  }
  if (answered === "yes" || answered === "no" || answered === "pass_through") {
    return actionLabel(answered);
  }
  if (decision.kind === "clarify") {
    return "Sent";
  }
  return actionLabel(answered);
}

export interface AiDecisionCardProps {
  decision: ChatDecision;
  /** Invoked with the clicked action, optional comment, and per-spec types on Import. */
  onAnswer: (
    action: string,
    comment: string,
    specSystemTypes?: Record<string, CatalogSystemType>,
  ) => void;
  /**
   * Invoked with the typed text for kind === "clarify". The caller sends it as an ordinary chat
   * message rather than a decision command, since a clarification has no enumerable answer.
   * Also used when a clarify gate offers enumerable actions (for example Yes / No).
   */
  onSubmitClarification?: (text: string) => void;
  /** Disables the buttons while a request is already in flight. */
  busy?: boolean;
  /** After End run, start a new conversation with the same opening assignment. */
  onStartSameTask?: () => void;
  /** When true, halt buttons stay visible but do not fire. */
  stale?: boolean;
}

/** A gate the run stopped at, rendered inside the transcript so it stays in history. */
export const AiDecisionCard: React.FC<AiDecisionCardProps> = ({
  decision,
  onAnswer,
  onSubmitClarification,
  busy = false,
  onStartSameTask,
  stale = false,
}) => {
  const isClarify = decision.kind === "clarify";
  const isMappingGapClarify =
    isClarify && decision.actions.includes("pass_through");
  const blankClarifyHalt = isBlankClarifyHalt(decision);
  const isFreeTextClarify =
    isClarify &&
    decision.actions.length === 0 &&
    !decision.recovery &&
    !blankClarifyHalt;
  const answeredAction = decision.answeredAction;
  const specRows = decision.specs ?? [];
  const usePerSpecImport = specRows.length > 0;
  const titleId = useId();
  const summaryId = useId();
  const [text, setText] = useState("");
  const [specTypes, setSpecTypes] = useState<Record<string, CatalogSystemType>>(
    () => initialSpecSystemTypes(specRows),
  );
  // Guards against a double click sending the answer twice before `busy` catches up.
  const clickedRef = useRef(false);
  const disabled = busy || stale || answeredAction !== undefined;

  const handleClick = (action: string) => {
    if (disabled || clickedRef.current) return;
    if (action === "describe_mappings") {
      const trimmed = text.trim();
      if (!trimmed) return;
      clickedRef.current = true;
      onSubmitClarification?.(trimmed);
      return;
    }
    clickedRef.current = true;
    if (isClarify && !COMMAND_ACTIONS.has(action)) {
      onSubmitClarification?.(action);
      return;
    }
    if (usePerSpecImport && action === "import-specification") {
      onAnswer(action, text.trim(), specSystemTypesMap(specRows, specTypes));
      return;
    }
    onAnswer(action, text.trim());
  };

  const handleSubmitClarification = () => {
    const trimmed = text.trim();
    if (disabled || clickedRef.current || !trimmed) return;
    clickedRef.current = true;
    onSubmitClarification?.(trimmed);
  };

  const cardText = decisionCardText(decision);
  const missingEvidence = visibleMissingEvidence(decision);
  const showTextArea = isFreeTextClarify || isMappingGapClarify || !isClarify;
  const showYesNoHint =
    isFreeTextClarify && /yes or no|yes\/no/i.test(cardText);

  const haltDecision: ChatDecision = blankClarifyHalt
    ? {
        ...decision,
        actions: ["stop-with-report"],
        recovery: {
          category: "unclassified-failure",
          title: "Creation cannot continue",
          summary:
            "Creation stopped without a question to answer. End the run or start again.",
          preservedWork: "Your approved requirements and plan are saved.",
          technicalDetails: "",
        },
      }
    : decision;
  const recovery = haltDecision.recovery;
  const recoveryActions = recovery
    ? recoveryCardActions(haltDecision)
    : labeledActions(decision.actions);
  const recoveryDetails = recovery
    ? [
        recovery.technicalDetails
          ? `Raw error: ${recovery.technicalDetails}`
          : "",
        recovery.failedStageId
          ? `Internal stage: ${recovery.failedStageId}`
          : "",
        recovery.runId ? `Run identifier: ${recovery.runId}` : "",
      ]
        .filter(Boolean)
        .join("\n")
    : "";
  const retryDelaySeconds = recovery?.retryDelayMs
    ? Math.ceil(recovery.retryDelayMs / 1000)
    : 0;
  const sameTaskFooter =
    answeredAction === "stop-with-report" && onStartSameTask ? (
      <div className="ai-decision-card__same-task">
        <Typography.Paragraph type="secondary">
          This run is closed. You can keep the report or start a new creation
          with the same task.
        </Typography.Paragraph>
        <Button size="small" type="primary" onClick={onStartSameTask}>
          Start new creation with the same task
        </Button>
      </div>
    ) : null;

  if (recovery) {
    return (
      <div
        className="ai-decision-card ai-decision-card--recovery"
        data-decision-id={decision.id}
        role="alert"
        aria-labelledby={titleId}
        aria-describedby={summaryId}
      >
        <Typography.Title id={titleId} level={5}>
          {recovery.title}
        </Typography.Title>
        <div id={summaryId} className="ai-decision-card__recovery-summary">
          <MarkdownRenderer>{recovery.summary}</MarkdownRenderer>
          <Typography.Paragraph>{recovery.preservedWork}</Typography.Paragraph>
          {retryDelaySeconds > 0 ? (
            <Typography.Text type="secondary">
              Retry in {retryDelaySeconds}{" "}
              {retryDelaySeconds === 1 ? "second" : "seconds"}.
            </Typography.Text>
          ) : null}
        </div>

        {recoveryDetails ? (
          <details className="ai-decision-card__technical-details">
            <summary>Technical details</summary>
            <pre>{recoveryDetails}</pre>
          </details>
        ) : null}

        {answeredAction !== undefined ? (
          <>
            <Typography.Text type="secondary">
              {answeredLabel(decision)}
            </Typography.Text>
            {sameTaskFooter}
          </>
        ) : (
          <Space className="ai-decision-card__actions" wrap>
            {recoveryActions.map((action) => (
              <Button
                key={action}
                size="small"
                type={PRIMARY_ACTIONS.has(action) ? "primary" : "default"}
                disabled={disabled}
                onClick={() => handleClick(action)}
              >
                {actionLabel(action)}
              </Button>
            ))}
          </Space>
        )}
      </div>
    );
  }

  return (
    <div className="ai-decision-card" data-decision-id={decision.id}>
      {cardText ? (
        <div className="ai-decision-card__question">
          {/* Server text is Markdown: numbered actions and **Adds** / **Removes** verbs. */}
          <MarkdownRenderer>{cardText}</MarkdownRenderer>
        </div>
      ) : null}

      {isClarify && missingEvidence.length > 0 ? (
        <List
          className="ai-decision-card__missing-evidence"
          size="small"
          dataSource={missingEvidence}
          renderItem={(item, index) => (
            <List.Item>
              {isMappingGapClarify ? `${index + 1}. ` : null}
              {item}
            </List.Item>
          )}
          style={{ marginBottom: 8 }}
        />
      ) : null}

      {answeredAction !== undefined ? (
        <Typography.Text type="secondary">
          {answeredLabel(decision)}
        </Typography.Text>
      ) : (
        <>
          {usePerSpecImport ? (
            <div className="ai-decision-card__specs">
              {specRows.map((spec) => (
                <div className="ai-decision-card__spec-row" key={spec.s3Key}>
                  <Typography.Text className="ai-decision-card__spec-name">
                    {spec.displayName}
                  </Typography.Text>
                  <Radio.Group
                    size="small"
                    optionType="button"
                    buttonStyle="solid"
                    disabled={disabled}
                    aria-label={`Catalog system type for ${spec.displayName}`}
                    value={specTypes[spec.s3Key] ?? "INTERNAL"}
                    options={[
                      { label: "Internal", value: "INTERNAL" },
                      { label: "External", value: "EXTERNAL" },
                    ]}
                    onChange={(event) => {
                      const value = event.target.value as CatalogSystemType;
                      setSpecTypes((current) => ({
                        ...current,
                        [spec.s3Key]: value,
                      }));
                    }}
                  />
                </div>
              ))}
            </div>
          ) : null}
          {showTextArea ? (
            <Input.TextArea
              className="ai-decision-card__comment"
              placeholder={commentPlaceholder(
                isMappingGapClarify,
                isFreeTextClarify,
              )}
              autoSize={{ minRows: 1, maxRows: 4 }}
              value={text}
              onChange={(e) => setText(e.target.value)}
              disabled={disabled}
            />
          ) : null}
          {showYesNoHint ? (
            <Typography.Paragraph
              type="secondary"
              className="ai-decision-card__clarify-hint"
            >
              Enter yes or no to enable Submit.
            </Typography.Paragraph>
          ) : null}
          <Space className="ai-decision-card__actions" wrap>
            {isFreeTextClarify ? (
              <Button
                size="small"
                type="primary"
                disabled={disabled || text.trim() === ""}
                onClick={handleSubmitClarification}
              >
                Submit
              </Button>
            ) : (
              (usePerSpecImport
                ? perSpecImportActions(decision.actions)
                : labeledActions(decision.actions)
              ).map((action) => (
                <Button
                  key={action}
                  size="small"
                  type={PRIMARY_ACTIONS.has(action) ? "primary" : "default"}
                  disabled={
                    disabled ||
                    (action === "describe_mappings" && text.trim() === "")
                  }
                  onClick={() => handleClick(action)}
                >
                  {usePerSpecImport && action === "import-specification"
                    ? "Import"
                    : actionLabel(action)}
                </Button>
              ))
            )}
          </Space>
        </>
      )}
    </div>
  );
};
