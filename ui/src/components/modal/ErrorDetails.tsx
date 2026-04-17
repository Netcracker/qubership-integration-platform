import { Button, Flex, Modal, theme, Tooltip } from "antd";
import { useModalContext } from "../../ModalContextProvider.tsx";
import { capitalize, formatTimestamp } from "../../misc/format-utils.ts";
import styles from "./ErrorDetails.module.css";
import React, { useMemo } from "react";
import { downloadFile } from "../../misc/download-utils.ts";
import { copyToClipboard } from "../../misc/clipboard-util.ts";
import { OverridableIcon } from "../../icons/IconProvider.tsx";
import { DeploymentStatus } from "../../api/apiTypes.ts";
import {
  getDeploymentStatusTone,
  isTokenDark,
} from "../../theme/semanticColors";
import { CloudServerOutlined } from "@ant-design/icons";
type ErrorDetailsProps = {
  service: string;
  timestamp: number | string;
  message: string;
  stacktrace: string;
  /** When provided, drives modal title and accent color. Otherwise shows a generic "Error" header. */
  status?: string;
};

const STATUS_LABELS: Record<DeploymentStatus, string> = {
  [DeploymentStatus.FAILED]: "Failed",
  [DeploymentStatus.PROCESSING]: "Processing",
  [DeploymentStatus.DEPLOYED]: "Deployed",
  [DeploymentStatus.REMOVED]: "Removed",
};

function getStatusLabel(status: string | undefined): string {
  const known = status ? STATUS_LABELS[status as DeploymentStatus] : undefined;
  return known ?? (capitalize(status ?? "") || "Error");
}

export const ErrorDetails: React.FC<ErrorDetailsProps> = ({
  service,
  timestamp,
  message,
  stacktrace,
  status,
}) => {
  const { closeContainingModal } = useModalContext();
  const { token } = theme.useToken();

  const tone = getDeploymentStatusTone(status ?? DeploymentStatus.FAILED, token);
  const statusLabel = getStatusLabel(status);

  const isDark = isTokenDark(token);
  const rootVars: React.CSSProperties & Record<string, string> = {
    "--error-accent": tone.accent,
    "--chip-bg": token.colorInfoBg,
    "--chip-border": token.colorInfoBorder,
    "--chip-fg": token.colorInfoText,
    "--meta-bg": token.colorFillAlter,
    "--meta-border": token.colorBorderSecondary,
    "--divider": token.colorSplit,
    "--message-bg": tone.bg,
    "--message-fg": tone.text,
    "--console-bg": isDark ? "#0d1117" : token.colorFillSecondary,
    "--console-fg": isDark ? "#c9d1d9" : token.colorText,
    "--console-border": token.colorBorderSecondary,
    "--console-line-hover": token.colorFillTertiary,
    "--console-gutter": token.colorBorderSecondary,
    "--console-badge-bg": token.colorFillSecondary,
    "--console-badge-fg": token.colorTextSecondary,
  };

  const getErrorDetailsText = () =>
    [
      `Error Originating Service: ${service}`,
      `Error Date: ${formatTimestamp(timestamp)}`,
      "Error Message:",
      message,
      ...(stacktrace ? ["Stacktrace:", stacktrace] : []),
    ].join("\n");

  const downloadErrorDetails = () => {
    const blob = new Blob([getErrorDetailsText()]);
    const fileName = `Error at ${service} at ${formatTimestamp(timestamp)}.txt`;
    const file = new File([blob], fileName, { type: "text/plain" });
    downloadFile(file);
  };

  const stackLines = useMemo(
    () => (stacktrace ? stacktrace.split(/\r?\n/) : []),
    [stacktrace],
  );

  const modalTitle = (
    <div className={styles.titleRow} style={rootVars}>
      <span className={styles.titleDot} aria-hidden />
      <span className={styles.titleMain}>{statusLabel}</span>
    </div>
  );

  return (
    <Modal
      title={modalTitle}
      open={true}
      onCancel={closeContainingModal}
      width={640}
      footer={[
        <Tooltip key="copy" title="Copy full report to clipboard">
          <Button
            icon={<OverridableIcon name="copy" />}
            type="text"
            onClick={() => void copyToClipboard(getErrorDetailsText())}
          >
            Copy
          </Button>
        </Tooltip>,
        <Tooltip key="download" title="Download as .txt">
          <Button
            icon={<OverridableIcon name="download" />}
            type="primary"
            onClick={downloadErrorDetails}
          >
            Download
          </Button>
        </Tooltip>,
      ]}
    >
      <Flex gap={14} vertical style={rootVars}>
        <div className={styles.metaGrid}>
          <div className={styles.metaLabel}>Service</div>
          <div className={styles.metaValue}>
            <span className={styles.chip}>
              <CloudServerOutlined aria-hidden />
              {service || "—"}
            </span>
          </div>
          <div className={styles.metaLabel}>Occurred</div>
          <div className={styles.metaValue}>{formatTimestamp(timestamp)}</div>
        </div>

        <div className={styles.sectionLabel}>Message</div>
        <div className={styles.messageCard}>
          {message || "No message provided"}
        </div>

        {stacktrace ? (
          <>
            <div className={styles.sectionLabel}>Stack trace</div>
            <pre className={styles.console}>
              <span className={styles.consoleGutter} aria-hidden />
              <span className={styles.consoleBadge}>stderr</span>
              <div className={styles.stack}>
                {stackLines.map((line, idx) => (
                  <span className={styles.consoleLine} key={idx}>
                    {line || " "}
                  </span>
                ))}
              </div>
            </pre>
          </>
        ) : null}
      </Flex>
    </Modal>
  );
};
