import React, { useMemo, useState } from "react";
import { Button, Input, Modal, Table, Upload, UploadFile } from "antd";
import type { ColumnsType } from "antd/lib/table";
import {
  TestingImportResult,
  TestingImportStatus,
} from "../../../api/apiTypes.ts";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";
import { OverridableIcon } from "../../../icons/IconProvider.tsx";
import { formatOptional } from "../../../misc/format-utils.ts";
import { useModalContext } from "../../../ModalContextProvider.tsx";
import { tableEmpty } from "../../table/tableEmpty.tsx";
import { matchesByFields } from "../../table/tableSearch.ts";
import { ImportResultTag } from "../../testing/TestingTags.tsx";

/** One row per archive entry, keyed by position: the service reports duplicates. */
type ImportResultRow = TestingImportResult & { key: string };

export type TestingImportModalProps = {
  title: string;
  /** Message of the notification raised when the whole upload fails. */
  failureMessage: string;
  importFiles: (files: File[]) => Promise<TestingImportResult[]>;
  /** Called once the upload created or updated something. */
  onImported: () => void;
};

const COLUMNS: ColumnsType<ImportResultRow> = [
  { title: "Archive", dataIndex: "archive", key: "archive", width: 180 },
  { title: "File Name", dataIndex: "fileName", key: "fileName", width: 180 },
  {
    title: "Id",
    key: "entityId",
    width: 180,
    render: (_, row) => formatOptional(row.entityId),
  },
  {
    title: "Name",
    key: "entityName",
    width: 180,
    render: (_, row) => formatOptional(row.entityName),
  },
  {
    title: "Result",
    key: "result",
    width: 110,
    render: (_, row) => <ImportResultTag status={row.result} />,
  },
  {
    title: "Error",
    key: "message",
    render: (_, row) => formatOptional(row.message),
  },
];

function createdOrUpdated(results: TestingImportResult[]): boolean {
  return results.some(
    (result) =>
      result.result === TestingImportStatus.CREATED ||
      result.result === TestingImportStatus.UPDATED,
  );
}

/**
 * Two-phase import: upload archives, then read the per-entry outcome. Shared by
 * the test-case and endpoint-mock lists, which differ only in title and call.
 */
export const TestingImportModal: React.FC<TestingImportModalProps> = ({
  title,
  failureMessage,
  importFiles,
  onImported,
}) => {
  const { closeContainingModal } = useModalContext();
  const notificationService = useNotificationService();
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [uploading, setUploading] = useState(false);
  const [results, setResults] = useState<TestingImportResult[] | null>(null);
  const [searchString, setSearchString] = useState("");

  const submit = async () => {
    setUploading(true);
    try {
      const files = fileList
        .map((file) => file.originFileObj)
        .filter((file) => !!file);
      const importResults = await importFiles(files);
      setResults(importResults);
      if (createdOrUpdated(importResults)) {
        onImported();
      }
    } catch (error) {
      notificationService.requestFailed(failureMessage, error);
    } finally {
      setUploading(false);
    }
  };

  const rows = useMemo<ImportResultRow[]>(
    () =>
      (results ?? [])
        .map((result, index) => ({ ...result, key: String(index) }))
        .filter((row) =>
          matchesByFields(searchString, [
            row.archive,
            row.fileName,
            row.entityId,
            row.entityName,
            row.result,
            row.message,
          ]),
        ),
    [results, searchString],
  );

  const footer = results
    ? [
        <Button key="close" onClick={closeContainingModal}>
          Close
        </Button>,
      ]
    : [
        <Button
          key="clear"
          disabled={fileList.length === 0 || uploading}
          onClick={() => setFileList([])}
        >
          Clear
        </Button>,
        <Button
          key="submit"
          type="primary"
          disabled={fileList.length === 0}
          loading={uploading}
          onClick={() => void submit()}
        >
          Import
        </Button>,
      ];

  return (
    <Modal
      title={title}
      centered
      open={true}
      onCancel={closeContainingModal}
      width="60%"
      footer={footer}
    >
      {results ? (
        <>
          <Input
            allowClear
            aria-label="Search import results"
            data-testid="import-results-search"
            placeholder="Search results..."
            value={searchString}
            onChange={(event) => setSearchString(event.target.value)}
            style={{ marginBottom: 8 }}
          />
          <Table<ImportResultRow>
            size="small"
            columns={COLUMNS}
            dataSource={rows}
            pagination={false}
            rowKey="key"
            scroll={{ y: 320, x: "max-content" }}
            locale={{ emptyText: tableEmpty("No import results to display") }}
          />
        </>
      ) : (
        <Upload.Dragger
          multiple
          accept=".zip"
          fileList={fileList}
          beforeUpload={() => false}
          onChange={(info) => setFileList(info.fileList)}
        >
          <p className="ant-upload-drag-icon">
            <OverridableIcon name="inbox" />
          </p>
          <p className="ant-upload-text">
            Click or drag archives to this area to upload
          </p>
          <p className="ant-upload-hint">
            Each archive holds one exported entity per file. An entity whose id
            already exists is updated in place.
          </p>
        </Upload.Dragger>
      )}
    </Modal>
  );
};
