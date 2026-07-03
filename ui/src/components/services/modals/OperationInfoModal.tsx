import React from "react";
import { Tabs } from "antd";
import { OperationInfo } from "../../../api/apiTypes";
import { Prism as SyntaxHighlighter } from "react-syntax-highlighter";
import { useSyntaxHighlighterTheme } from "../../../hooks/useSyntaxHighlighterTheme";
import { ModalWithFullscreenToggle } from "../../modal/ModalWithFullscreenToggle.tsx";
import styles from "./OperationInfoModal.module.css";

interface OperationInfoModalProps {
  visible: boolean;
  onClose: () => void;
  operationInfo?: OperationInfo;
  loading?: boolean;
}

export const OperationInfoModal: React.FC<OperationInfoModalProps> = ({
  visible,
  onClose,
  operationInfo,
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
        height: "100%",
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
      <Tabs
        className={styles.tabs}
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
