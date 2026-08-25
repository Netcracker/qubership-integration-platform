import React from "react";
import { Flex, Tabs, Typography } from "antd";
import { OperationInfo, SystemOperation } from "../../../api/apiTypes";
import { Prism as SyntaxHighlighter } from "react-syntax-highlighter";
import { useSyntaxHighlighterTheme } from "../../../hooks/useSyntaxHighlighterTheme";
import { ModalWithFullscreenToggle } from "../../modal/ModalWithFullscreenToggle.tsx";
import { MethodBadge } from "../ui/MethodBadge.tsx";
import { OperationBadges, hasOperationBadges } from "../ui/OperationBadges.tsx";
import styles from "./OperationInfoModal.module.css";

interface OperationInfoModalProps {
  visible: boolean;
  onClose: () => void;
  operationInfo?: OperationInfo;
  /** Operation record for the header badges (method/channel/protocol/...); independent of `operationInfo`. */
  operation?: SystemOperation;
  loading?: boolean;
}

/** Header badges for the operation's typed fields, with the summary on the same
 *  row. Each field renders only when present. */
const OperationMeta: React.FC<{ operation?: SystemOperation }> = ({
  operation,
}) => {
  if (!operation) return null;

  const hasMeta =
    !!operation.method || !!operation.summary || hasOperationBadges(operation);
  if (!hasMeta) return null;

  return (
    <Flex align="center" gap={8} wrap className={styles.meta}>
      {operation.method && <MethodBadge value={operation.method} />}
      <OperationBadges operation={operation} />
      {operation.summary && (
        <Typography.Text
          type="secondary"
          className={styles.summary}
          ellipsis={{ tooltip: operation.summary }}
        >
          {operation.summary}
        </Typography.Text>
      )}
    </Flex>
  );
};

export const OperationInfoModal: React.FC<OperationInfoModalProps> = ({
  visible,
  onClose,
  operationInfo,
  operation,
  loading,
}) => {
  const syntaxTheme = useSyntaxHighlighterTheme();

  const renderJsonTabContent = (data: unknown) => (
    <SyntaxHighlighter
      language="json"
      style={syntaxTheme}
      className={styles.codeBlock}
      customStyle={{
        margin: 0,
        flex: "1 1 auto",
        minHeight: 0,
        boxSizing: "border-box",
      }}
      PreTag="pre"
      CodeTag="code"
    >
      {loading ? "{}" : JSON.stringify(data, null, 2)}
    </SyntaxHighlighter>
  );

  return (
    <ModalWithFullscreenToggle
      open={visible}
      onCancel={onClose}
      title="Operation info"
      footer={null}
      destroyOnHidden
    >
      <OperationMeta operation={operation} />
      <Tabs
        className="flex-tabs"
        defaultActiveKey="specification"
        items={[
          {
            key: "specification",
            label: "Specification",
            children: renderJsonTabContent(operationInfo?.specification),
          },
          {
            key: "request",
            label: "Request schema",
            children: renderJsonTabContent(operationInfo?.requestSchema),
          },
          {
            key: "response",
            label: "Response schemas",
            children: renderJsonTabContent(operationInfo?.responseSchemas),
          },
        ]}
      />
    </ModalWithFullscreenToggle>
  );
};
