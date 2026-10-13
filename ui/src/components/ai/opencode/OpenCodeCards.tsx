import { Alert, Button, Checkbox, Input, Radio, Space, Typography } from "antd";
import { useState } from "react";
import type {
  PermissionRequest,
  QuestionRequest,
} from "@opencode-ai/sdk/v2/client";
import { chatError } from "../../../ai/opencode/client.ts";

export function OpenCodePermissionCard({
  request,
  disabled,
  onReply,
}: {
  request: PermissionRequest;
  disabled: boolean;
  onReply: (id: string, reply: "once" | "always" | "reject") => Promise<void>;
}) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function reply(value: "once" | "always" | "reject") {
    setPending(true);
    setError(null);
    try {
      await onReply(request.id, value);
    } catch (err) {
      setError(chatError(err));
    } finally {
      setPending(false);
    }
  }
  return (
    <div className="ai-decision-card" data-decision-id={request.id}>
      <Typography.Paragraph>
        Allow OpenCode to use {request.permission}?
      </Typography.Paragraph>
      <Typography.Paragraph
        code
        style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}
      >
        {request.patterns.join("\n")}
      </Typography.Paragraph>
      {error && <Alert type="error" title={error} />}
      <Space wrap className="ai-decision-card__actions">
        <Button
          type="primary"
          disabled={disabled || pending}
          onClick={() => void reply("once")}
        >
          Allow once
        </Button>
        <Button
          disabled={disabled || pending}
          onClick={() => void reply("always")}
        >
          Always allow
        </Button>
        <Button
          disabled={disabled || pending}
          onClick={() => void reply("reject")}
        >
          Reject
        </Button>
      </Space>
    </div>
  );
}

export function OpenCodeQuestionCard({
  request,
  disabled,
  onReply,
}: {
  request: QuestionRequest;
  disabled: boolean;
  onReply: (id: string, answers: string[][] | null) => Promise<void>;
}) {
  const [selections, setSelections] = useState<string[][]>(
    request.questions.map(() => []),
  );
  const [custom, setCustom] = useState<string[]>(
    request.questions.map(() => ""),
  );
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const answers = request.questions.map((q, i) => {
    const own = q.custom !== false ? custom[i].trim() : "";
    return own ? (q.multiple ? [...selections[i], own] : [own]) : selections[i];
  });
  async function reply(value: string[][] | null) {
    setPending(true);
    setError(null);
    try {
      await onReply(request.id, value);
    } catch (err) {
      setError(chatError(err));
    } finally {
      setPending(false);
    }
  }
  return (
    <div className="ai-decision-card" data-decision-id={request.id}>
      {request.questions.map((q, i) => {
        const options = q.options.map((o) => ({
          value: o.label,
          label: (
            <span>
              {o.label}{" "}
              <Typography.Text type="secondary">
                {o.description}
              </Typography.Text>
            </span>
          ),
        }));
        const change = (values: string[]) =>
          setSelections((all) => all.map((v, j) => (j === i ? values : v)));
        return (
          <div key={i} className="ai-decision-card__question">
            <Typography.Paragraph strong>
              {q.header}: {q.question}
            </Typography.Paragraph>
            {q.multiple ? (
              <Checkbox.Group
                options={options}
                value={selections[i]}
                disabled={disabled || pending}
                onChange={(values) => change(values)}
              />
            ) : (
              <Radio.Group
                options={options}
                value={selections[i][0]}
                disabled={disabled || pending}
                onChange={(e) => {
                  change([String(e.target.value)]);
                  setCustom((all) => all.map((v, j) => (j === i ? "" : v)));
                }}
              />
            )}
            {q.custom !== false && (
              <Input
                aria-label={`Your answer: ${q.header}`}
                placeholder="Your answer"
                value={custom[i]}
                disabled={disabled || pending}
                onChange={(e) => {
                  if (!q.multiple && e.target.value.trim()) change([]);
                  setCustom((all) =>
                    all.map((v, j) => (j === i ? e.target.value : v)),
                  );
                }}
              />
            )}
          </div>
        );
      })}
      {error && <Alert type="error" title={error} />}
      <Space className="ai-decision-card__actions">
        <Button
          type="primary"
          disabled={disabled || pending || answers.some((a) => !a.length)}
          onClick={() => void reply(answers)}
        >
          Submit
        </Button>
        <Button disabled={disabled || pending} onClick={() => void reply(null)}>
          Skip
        </Button>
      </Space>
    </div>
  );
}
