import {
  ChainCommitRequestAction,
  ChainImportInstructions,
  ChainImportPreview,
  EngineDomain,
  ImportChainResult,
  ImportEntityStatus,
  ImportEntityType,
  ImportInstructionResult,
  ImportInstructions,
  ImportMode,
  ImportPreview,
  ImportRequest,
  ImportResult,
  ImportSystemResult,
  ImportVariableResult,
  SystemImportStatus,
} from "../../api/apiTypes.ts";
import React, { useCallback, useEffect, useMemo, useState } from "react";
import {
  Button,
  Dropdown,
  Flex,
  Modal,
  Progress,
  Space,
  Steps,
  Table,
  Tabs,
  Tag,
  UploadFile,
} from "antd";
import Dragger from "antd/es/upload/Dragger";
import { useModalContext } from "../../ModalContextProvider.tsx";
import { api } from "../../api/api.ts";
import { TableProps } from "antd/lib/table";
import { InlineEdit } from "../InlineEdit.tsx";
import { capitalize } from "../../misc/format-utils.ts";
import { SelectEdit } from "../table/SelectEdit.tsx";
import Checkbox from "antd/lib/checkbox";
import { ImportStatus } from "../labels/ImportStatus.tsx";
import { useNotificationService } from "../../hooks/useNotificationService.tsx";
import { OverridableIcon } from "../../icons/IconProvider.tsx";

type ImportChainsProps = {
  onSuccess?: () => void;
};

type PreviewImportInstructionTableItem = {
  id: string;
  name: string;
  overriddenById?: string;
  overriddenByName?: string;
  action?: string;
  labels?: string[];
  children?: PreviewImportInstructionTableItem[];
};

type ResultImportInstructionTableItem = Partial<ImportInstructionResult> & {
  children?: ResultImportInstructionTableItem[];
};

type PreviewImportChainTableItem = ChainImportPreview & {
  domains: EngineDomain[];
};

function getInstructionTableItems(
  obj: ChainImportInstructions | ImportInstructions | undefined | null,
): PreviewImportInstructionTableItem[] {
  return Object.entries(obj ?? {}).flatMap(([action, instructions]) =>
    instructions.map((i) => ({
      id: i.id,
      name: i.name ?? i.id,
      overriddenById: i.overriddenById,
      overriddenByName: i.overriddenByName,
      action: capitalize(action),
      labels: i.labels,
    })),
  );
}

export const ImportChains: React.FC<ImportChainsProps> = ({ onSuccess }) => {
  const IMPORT_STATUS_UPDATE_INTERVAL_MS = 1000;

  const notificationService = useNotificationService();
  const { closeContainingModal } = useModalContext();
  const [step, setStep] = useState<number>(0);
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [loading, setLoading] = useState(false);
  const [importPreview, setImportPreview] = useState<ImportPreview | undefined>(
    undefined,
  );
  const [selectedServiceRowKeys, setSelectedServiceRowKeys] = useState<
    React.Key[]
  >([]);
  const [selectedVariableRowKeys, setSelectedVariableRowKeys] = useState<
    React.Key[]
  >([]);
  const [domains, setDomains] = useState<EngineDomain[]>([]);
  const [validateByHashChecked, setValidateByHashChecked] =
    useState<boolean>(false);
  const [
    previewImportInstructionTableItems,
    setPreviewImportInstructionTableItems,
  ] = useState<PreviewImportInstructionTableItem[]>([]);
  const [previewImportChainTableItems, setPreviewImportChainTableItems] =
    useState<PreviewImportChainTableItem[]>([]);
  const [progress, setProgress] = useState<number>(0);
  const [importResult, setImportResult] = useState<ImportResult | undefined>(
    undefined,
  );
  const [
    resultImportInstructionTableItems,
    setResultImportInstructionTableItems,
  ] = useState<ResultImportInstructionTableItem[]>([]);

  const previewServices = useMemo(
    () => [
      ...(importPreview?.systems ?? []),
      ...(importPreview?.contextService ?? []),
    ],
    [importPreview],
  );
  const previewServicesCount = previewServices.length;
  const resultServices = useMemo(
    () => [
      ...(importResult?.systems ?? []),
      ...(importResult?.contextService ?? []),
    ],
    [importResult],
  );
  const contextServiceIds = useMemo(
    () => new Set((importResult?.contextService ?? []).map((s) => s.id)),
    [importResult],
  );

  const getDomains = useCallback(async () => {
    setLoading(true);
    try {
      return api.getDomains();
    } catch (error) {
      notificationService.requestFailed("Failed to get domains", error);
      return [];
    } finally {
      setLoading(false);
    }
  }, [notificationService]);

  useEffect(() => {
    if (importPreview) {
      setSelectedServiceRowKeys(previewServices.map((i) => i.id));
      setSelectedVariableRowKeys(
        importPreview?.variables?.map((i) => i.name) ?? [],
      );
      void getDomains().then(setDomains);
    }
  }, [getDomains, importPreview, previewServices]);

  useEffect(() => {
    if (importPreview) {
      setPreviewImportChainTableItems(
        importPreview.chains?.map((i, index) => ({
          ...i,
          id: i.id ?? index,
          domains: domains?.filter((domain) =>
            i.deployments?.some((d) => d.domain === domain.id),
          ),
        })) ?? [],
      );
    }
  }, [importPreview, domains]);

  useEffect(() => {
    setResultImportInstructionTableItems([
      {
        id: "Chains",
        name: "Chains",
        children: importResult?.instructionsResult?.filter(
          (i) => i.entityType === ImportEntityType.CHAIN,
        ),
      },
      {
        id: "Services",
        name: "Services",
        children: importResult?.instructionsResult?.filter(
          (i) => i.entityType === ImportEntityType.SERVICE,
        ),
      },
      {
        id: "Common Variables",
        name: "Common Variables",
        children: importResult?.instructionsResult
          ?.filter((i) => i.entityType === ImportEntityType.COMMON_VARIABLE)
          .map((i) => ({ ...i, name: i.name ?? i.id })),
      },
    ]);
  }, [importResult]);

  useEffect(() => {
    setPreviewImportInstructionTableItems([
      {
        id: "Chains",
        name: "Chains",
        children: getInstructionTableItems(importPreview?.instructions?.chains),
      },
      {
        id: "Services",
        name: "Services",
        children: getInstructionTableItems(
          importPreview?.instructions?.services,
        ),
      },
      {
        id: "Common Variables",
        name: "Common Variables",
        children: getInstructionTableItems(
          importPreview?.instructions?.commonVariables,
        ),
      },
    ]);
  }, [importPreview]);

  const getImportPreview = async (file: File) => {
    setLoading(true);
    try {
      const preview = await api.getImportPreview(file);
      setImportPreview(preview);
    } catch (error) {
      notificationService.requestFailed("Failed to get import preview", error);
    } finally {
      setLoading(false);
    }
  };

  const buildImportRequest = (): ImportRequest => ({
    chainCommitRequests: previewImportChainTableItems.map((item) => ({
      id: item.id,
      archiveName: fileList[0].name,
      deployAction: item.deployAction,
      domains: item.domains.map((domain) => ({
        id: domain.id,
        name: domain.name,
      })),
    })),
    systemsCommitRequest: {
      importMode:
        selectedServiceRowKeys.length === previewServicesCount
          ? ImportMode.FULL
          : selectedServiceRowKeys.length === 0
            ? ImportMode.NONE
            : ImportMode.PARTIAL,
      systemIds:
        selectedServiceRowKeys.length === previewServicesCount
          ? []
          : selectedServiceRowKeys.map((i) => i.toString()),
    },
    variablesCommitRequest: {
      importMode:
        selectedVariableRowKeys.length === importPreview?.variables?.length
          ? ImportMode.FULL
          : selectedVariableRowKeys.length === 0
            ? ImportMode.NONE
            : ImportMode.PARTIAL,
      variablesNames:
        selectedVariableRowKeys.length === importPreview?.variables?.length
          ? []
          : selectedVariableRowKeys.map((i) => i.toString()),
    },
  });

  const doImport = async () => {
    if (!fileList[0].originFileObj) {
      return;
    }
    const importRequest = buildImportRequest();
    setLoading(true);
    try {
      const response = await api.commitImport(
        fileList[0].originFileObj,
        importRequest,
        validateByHashChecked,
      );
      return response.importId;
    } catch (error) {
      notificationService.requestFailed(
        "Failed to commit import request",
        error,
      );
    } finally {
      setLoading(false);
    }
  };

  const waitForImportIsDone = async (importId: string | undefined) => {
    if (!importId) {
      return;
    }
    setStep(2);
    setLoading(true);
    setProgress(0);
    try {
      const importStatus = await api.getImportStatus(importId);
      if (importStatus.done) {
        setLoading(false);
        setProgress(100);
        setImportResult(importStatus.result);
        // A small delay is added only to show a nice 100% progress bar.
        setTimeout(() => setStep(3), 500);
        onSuccess?.();
      } else {
        setProgress(importStatus.completion);
        setTimeout(
          () => void waitForImportIsDone(importId),
          IMPORT_STATUS_UPDATE_INTERVAL_MS,
        );
      }
    } catch (error) {
      notificationService.requestFailed("Failed to get import status", error);
      setLoading(false);
    }
  };

  const previewChainTableColumns: TableProps<PreviewImportChainTableItem>["columns"] =
    [
      {
        title: "Name",
        dataIndex: "name",
      },
      {
        title: "ID",
        dataIndex: "id",
      },
      {
        title: "Domain",
        render: (_, item) => (
          <InlineEdit<{ domains: string[] }>
            values={{ domains: item.domains.map((i) => i.id) }}
            editor={
              <SelectEdit
                name="domains"
                multiple
                options={Object.values(domains).map((i) => ({
                  label: i.name,
                  value: i.id,
                }))}
              />
            }
            viewer={
              <Flex gap="4px 4px" wrap>
                {item.domains.map((i) => (
                  <Tag key={i.id}>{i.name}</Tag>
                ))}
              </Flex>
            }
            onSubmit={(values) => {
              setPreviewImportChainTableItems(
                previewImportChainTableItems.map((i) =>
                  i.id === item.id
                    ? {
                        ...i,
                        domains: domains.filter((d) =>
                          values.domains.includes(d.id),
                        ),
                      }
                    : i,
                ),
              );
            }}
          />
        ),
      },
      {
        title: "Instruction Action",
        dataIndex: "instructionAction",
        render: (_, item) => (
          <ImportStatus status={item.instructionAction ?? ""} />
        ),
      },
      {
        title: (
          <Space>
            Action{" "}
            <Dropdown
              menu={{
                items: Object.values(ChainCommitRequestAction).map((i) => ({
                  key: i,
                  label: capitalize(i),
                })),
                onClick: ({ key }) => {
                  setPreviewImportChainTableItems(
                    previewImportChainTableItems.map((i) => ({
                      ...i,
                      deployAction: key as ChainCommitRequestAction,
                    })),
                  );
                },
              }}
            >
              <OverridableIcon name="down" />
            </Dropdown>
          </Space>
        ),
        render: (_, item) => (
          <InlineEdit<{ action: ChainCommitRequestAction }>
            values={{ action: item.deployAction }}
            editor={
              <SelectEdit
                name="action"
                options={Object.values(ChainCommitRequestAction).map((i) => ({
                  label: capitalize(i),
                  value: i,
                }))}
              />
            }
            viewer={capitalize(item.deployAction)}
            onSubmit={({ action }) => {
              setPreviewImportChainTableItems(
                previewImportChainTableItems.map((i) =>
                  i.id === item.id
                    ? {
                        ...i,
                        deployAction: action,
                      }
                    : i,
                ),
              );
            }}
          />
        ),
      },
    ];

  const previewServiceTableColumns: TableProps["columns"] = [
    {
      title: "Name",
      dataIndex: "name",
    },
    {
      title: "ID",
      dataIndex: "id",
    },
  ];

  const previewCommonVariablesTableColumns: TableProps["columns"] = [
    {
      title: "Name",
      dataIndex: "name",
    },
    {
      title: "Value",
      dataIndex: "value",
    },
    {
      title: "Current Value",
      dataIndex: "currentValue",
    },
  ];

  const previewImportInstructionTableColumns: TableProps["columns"] = [
    {
      title: "ID",
      dataIndex: "name",
    },
    {
      title: "Action",
      dataIndex: "action",
    },
    {
      title: "Overridden By",
      dataIndex: "overriddenByName",
    },
    {
      title: "Labels",
      dataIndex: "labels",
    },
  ];

  const resultChainTableColumns: TableProps<ImportChainResult>["columns"] = [
    {
      title: "Name",
      dataIndex: "name",
      render: (_, item) =>
        item.status === ImportEntityStatus.CREATED ||
        item.status === ImportEntityStatus.UPDATED ? (
          <a onClick={() => window.open(`/chains/${item.id}`, "_blank")}>
            {item.name}
          </a>
        ) : (
          item.name
        ),
    },
    {
      title: "ID",
      dataIndex: "id",
    },
    {
      title: "Status",
      dataIndex: "status",
      render: (_, item) => (
        <ImportStatus status={item.status} message={item.errorMessage} />
      ),
    },
  ];

  const resultServiceTableColumns: TableProps<ImportSystemResult>["columns"] = [
    {
      title: "Name",
      dataIndex: "name",
      render: (_, item) =>
        item.status === SystemImportStatus.CREATED ||
        item.status === SystemImportStatus.UPDATED ? (
          <a
            onClick={() => {
              const url = contextServiceIds.has(item.id)
                ? `/services/context/${item.id}/parameters`
                : `/services/systems/${item.id}`;
              window.open(url, "_blank");
            }}
          >
            {item.name}
          </a>
        ) : (
          item.name
        ),
    },
    {
      title: "ID",
      dataIndex: "id",
    },
    {
      title: "Status",
      dataIndex: "status",
      render: (_, item) => (
        <ImportStatus status={item.status} message={item.message} />
      ),
    },
  ];

  const resultCommonVariablesTableColumns: TableProps<ImportVariableResult>["columns"] =
    [
      {
        title: "Name",
        dataIndex: "name",
      },
      {
        title: "Value",
        dataIndex: "value",
      },
      {
        title: "Status",
        dataIndex: "status",
        render: (_, item) => (
          <ImportStatus status={item.status} message={item.error} />
        ),
      },
    ];

  const resultImportInstructionTableColumns: TableProps<ResultImportInstructionTableItem>["columns"] =
    [
      {
        title: "ID",
        dataIndex: "name",
      },
      {
        title: "Status",
        dataIndex: "status",
        render: (_, item) => (
          <ImportStatus status={item.status} message={item.errorMessage} />
        ),
      },
    ];

  return (
    <Modal
      title="Import"
      centered
      open={true}
      onCancel={closeContainingModal}
      width={"80vw"}
      footer={
        step < 2
          ? [
              <Button
                key="submit"
                type="primary"
                disabled={step === 0 && (!fileList || !fileList.length)}
                loading={loading}
                onClick={() => {
                  switch (step) {
                    case 0: {
                      if (fileList[0].originFileObj) {
                        void getImportPreview(fileList[0].originFileObj).then(
                          () => setStep(1),
                        );
                      }
                      break;
                    }
                    case 1: {
                      void doImport().then(waitForImportIsDone);
                      break;
                    }
                  }
                }}
              >
                {step === 0 ? "Next" : "Import"}
              </Button>,
            ]
          : null
      }
    >
      <Flex vertical gap={16} style={{ height: "70vh" }}>
        <Steps
          type="navigation"
          size="small"
          items={[
            {
              title: "Select file",
            },
            {
              title: "Specify actions",
            },
            {
              title: "Import",
            },
            {
              title: "Results",
            },
          ]}
          current={step}
        />
        {
          [
            <Dragger
              key={1}
              rootClassName="flex-dragger"
              multiple={false}
              fileList={fileList}
              beforeUpload={() => false}
              onChange={(info) => setFileList(info.fileList)}
            >
              <p className="ant-upload-drag-icon">
                <OverridableIcon name="inbox" />
              </p>
              <p className="ant-upload-text">
                Click or drag file to this area to upload
              </p>
            </Dragger>,
            <Tabs
              key={2}
              className="flex-tabs"
              style={{ flexGrow: 1 }}
              items={[
                {
                  key: "chains",
                  label: "Chains",
                  children: (
                    <Flex gap={8} vertical style={{ height: "100%" }}>
                      <Checkbox
                        checked={validateByHashChecked}
                        onChange={(event) =>
                          setValidateByHashChecked(event.target.checked)
                        }
                      >
                        Validate By Hash
                      </Checkbox>
                      <Table
                        style={{ flexGrow: 1 }}
                        className="flex-table"
                        size="small"
                        rowKey="id"
                        columns={previewChainTableColumns}
                        dataSource={previewImportChainTableItems}
                        pagination={false}
                        scroll={{ y: "" }}
                      />
                    </Flex>
                  ),
                },
                {
                  key: "services",
                  label: "Services",
                  children: (
                    <Table
                      style={{ height: "100%" }}
                      className="flex-table"
                      size="small"
                      rowKey="id"
                      columns={previewServiceTableColumns}
                      dataSource={previewServices}
                      rowSelection={{
                        type: "checkbox",
                        selectedRowKeys: selectedServiceRowKeys,
                        checkStrictly: false,
                        onChange: (selectedKeys) =>
                          setSelectedServiceRowKeys(selectedKeys),
                      }}
                      pagination={false}
                      scroll={{ y: "" }}
                    />
                  ),
                },
                {
                  key: "commonVariables",
                  label: "Common Variables",
                  children: (
                    <Table
                      style={{ height: "100%" }}
                      className="flex-table"
                      size="small"
                      rowKey="name"
                      columns={previewCommonVariablesTableColumns}
                      dataSource={importPreview?.variables}
                      rowSelection={{
                        type: "checkbox",
                        selectedRowKeys: selectedVariableRowKeys,
                        checkStrictly: false,
                        onChange: (selectedKeys) =>
                          setSelectedVariableRowKeys(selectedKeys),
                      }}
                      pagination={false}
                      scroll={{ y: "" }}
                    />
                  ),
                },
                {
                  key: "importInstructions",
                  label: "Import Instructions",
                  children: (
                    <Table
                      style={{ height: "100%" }}
                      className="flex-table"
                      size="small"
                      rowKey="id"
                      columns={previewImportInstructionTableColumns}
                      dataSource={previewImportInstructionTableItems}
                      pagination={false}
                      scroll={{ y: "" }}
                    />
                  ),
                },
              ]}
            ></Tabs>,
            <Flex
              key={3}
              vertical
              justify={"center"}
              align={"center"}
              style={{ flexGrow: 1 }}
            >
              <Progress type="circle" percent={progress} />
            </Flex>,
            <Tabs
              key={4}
              className="flex-tabs"
              style={{ flexGrow: 1 }}
              items={[
                {
                  key: "chains",
                  label: "Chains",
                  children: (
                    <Table
                      style={{ height: "100%" }}
                      className="flex-table"
                      size="small"
                      rowKey="id"
                      columns={resultChainTableColumns}
                      dataSource={importResult?.chains}
                      pagination={false}
                      scroll={{ y: "" }}
                    />
                  ),
                },
                {
                  key: "services",
                  label: "Services",
                  children: (
                    <Table
                      style={{ height: "100%" }}
                      className="flex-table"
                      size="small"
                      rowKey="id"
                      columns={resultServiceTableColumns}
                      dataSource={resultServices}
                      pagination={false}
                      scroll={{ y: "" }}
                    />
                  ),
                },
                {
                  key: "commonVariables",
                  label: "Common Variables",
                  children: (
                    <Table
                      style={{ height: "100%" }}
                      className="flex-table"
                      size="small"
                      rowKey="name"
                      columns={resultCommonVariablesTableColumns}
                      dataSource={importResult?.variables}
                      pagination={false}
                      scroll={{ y: "" }}
                    />
                  ),
                },
                {
                  key: "importInstructions",
                  label: "Import Instructions",
                  children: (
                    <Table
                      style={{ height: "100%" }}
                      className="flex-table"
                      size="small"
                      rowKey="id"
                      columns={resultImportInstructionTableColumns}
                      dataSource={resultImportInstructionTableItems}
                      pagination={false}
                      scroll={{ y: "" }}
                    />
                  ),
                },
              ]}
            ></Tabs>,
          ][step]
        }
      </Flex>
    </Modal>
  );
};
