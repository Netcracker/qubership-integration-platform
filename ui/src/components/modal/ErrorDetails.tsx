import { Button, Col, Flex, Modal, Row } from "antd";
import { useModalContext } from "../../ModalContextProvider.tsx";
import { formatTimestamp } from "../../misc/format-utils.ts";
import { DownloadOutlined, CopyOutlined } from "@ant-design/icons";
import styles from "./ErrorDetails.module.css";
import React from "react";
import { downloadFile } from "../../misc/download-utils.ts";
import { copyToClipboard } from "../../misc/clipboard-util.ts";

type ErrorDetailsProps = {
  service: string;
  timestamp: number | string;
  message: string;
  stacktrace: string;
};

export const ErrorDetails: React.FC<ErrorDetailsProps> = ({
  service,
  timestamp,
  message,
  stacktrace,
}) => {
  const { closeContainingModal } = useModalContext();

  const getErrorDetailsText = () => {
    return [
      `Error Originating Service: ${service}`,
      `Error Date: ${formatTimestamp(timestamp)}`,
      "Error Message:",
      message,
      ...(stacktrace ? ["Stacktrace:", stacktrace] : []),
    ].join("\n");
  };

  const downloadErrorDetails = async () => {
    const blob = new Blob([getErrorDetailsText()]);
    const fileName = `Error at ${service} at ${formatTimestamp(timestamp)}.txt`;
    const file = new File([blob], fileName, { type: "text/plain" });
    downloadFile(file);
  };

  return (
    <Modal
      title="Error Details"
      open={true}
      onCancel={async () => closeContainingModal()}
      footer={[
        <Button
          icon={<DownloadOutlined />}
          type="text"
          onClick={downloadErrorDetails}
        >
          Download
        </Button>,
        <Button
          icon={<CopyOutlined />}
          type="text"
          onClick={async () => copyToClipboard(getErrorDetailsText()) }
        >
          Copy
        </Button>,
      ]}
    >
      <Flex gap="small" vertical>
        <Row>
          <Col className={styles.label} span={8}>
            Originating service:
          </Col>
          <Col>{service}</Col>
        </Row>
        <Row>
          <Col className={styles.label} span={8}>
            Date:
          </Col>
          <Col>{formatTimestamp(timestamp)}</Col>
        </Row>
        <Row>
          <Col className={styles.label} span={8}>
            Message:
          </Col>
        </Row>
        <Row>
          <Col span={24}>{message}</Col>
        </Row>
        {stacktrace ? (
          <>
            <Row>
              <Col className={styles.label} span={8}>
                Stack Trace:
              </Col>
            </Row>
            <Row>
              <Col className={styles.stacktrace} span={24}>
                {stacktrace}
              </Col>
            </Row>
          </>
        ) : null}
      </Flex>
    </Modal>
  );
};
