import React, { useState } from "react";
import { Button, Card, Checkbox, Form, Input, message, Modal, Spin, Tabs, Typography, Upload } from "antd";
import { InboxOutlined } from "@ant-design/icons";
import type { RcFile } from "antd/es/upload";
import { useModalContext } from "../../ModalContextProvider";
import { api } from "../../api/api";
import { getErrorMessage } from "../../misc/error-utils";
import { useNotificationService } from "../../hooks/useNotificationService";
import type { ElementWithChainName } from "../../api/apiTypes";
import { ExclamationCircleOutlined } from "@ant-design/icons";
import { ApiSpecificationType, ApiSpecificationFormat } from "../../api/apiTypes";
import styles from "./Services.module.css";
import { validateFiles } from "./utils";

const POLLING_INTERVAL = 1200;
const DEFAULT_EXTERNAL_ROUTES_ONLY = true;
const MODAL_WIDTH = 600;

const SUPPORTED_EXTENSIONS = ['.json', '.yaml', '.yml', '.xml', '.wsdl', '.xsd', '.graphql', '.graphqls', '.proto', '.zip'];

interface Props {
  systemId?: string;
  specificationGroupId?: string;
  groupMode?: boolean;
  onSuccess?: () => void;
  isImplementedService?: boolean;
}

const ImportSpecificationsModal: React.FC<Props> = ({ systemId, specificationGroupId, groupMode, onSuccess, isImplementedService }) => {
  const { closeContainingModal } = useModalContext();
  const notify = useNotificationService();
  const [files, setFiles] = useState<RcFile[]>([]);
  const [loading, setLoading] = useState(false);
  const [polling, setPolling] = useState(false);
  const [progressText, setProgressText] = useState<string | null>(null);
  const [name, setName] = useState<string>("");
  const [nameTouched, setNameTouched] = useState(false);
  const [externalRoutesOnly, setExternalRoutesOnly] = useState(DEFAULT_EXTERNAL_ROUTES_ONLY);
  const [elements, setElements] = useState<ElementWithChainName[]>([]);
  const [selectedChainIds, setSelectedChainIds] = useState<string[]>([]);
  const [loadingChains, setLoadingChains] = useState(false);
  const [validationError, setValidationError] = useState<null | { message: string; triggers: ElementWithChainName[] }>(null);

  const isGroupMode = groupMode ?? (!!systemId && !specificationGroupId);

  const handleCancel = () => {
    closeContainingModal();
  };

  const handleImport = async () => {
    const validation = validateFiles(files, SUPPORTED_EXTENSIONS);
    if (!validation.valid) {
      message.warning(validation.message);
      return;
    }
    if (isGroupMode) {
      if (!name.trim()) {
        message.warning("Name is required");
        return;
      }
    }
    setLoading(true);
    setProgressText("Uploading...");
    try {
      let res;
      if (isGroupMode) {
        res = await api.importSpecificationGroup(
          systemId!,
          name.trim(),
          files,
        );
      } else {
        res = await api.importSpecification(
          specificationGroupId!,
          files,
        );
      }
      setProgressText("Processing...");
      setPolling(true);
      await pollStatus(res.id);
      resetLoadingState();
      closeContainingModal();
      onSuccess?.();
    } catch (e: unknown) {
      handleError(e, 'Import failed');
    }
  };

  const pollStatus = async (importId: string) => {
    while (true) {
      try {
        const result = await api.getImportSpecificationResult(importId);
        if (result.done) {
          setProgressText(result.warningMessage ? result.warningMessage : "Import complete");
          return result;
        }
      } catch (e: unknown) {
        handleError(e, 'Failed to get import status');
        throw e;
      }
      await new Promise(res => setTimeout(res, POLLING_INTERVAL));
    }
  };

  const handleFilesChange = (fileList: RcFile[]) => {
    const validation = validateFiles(fileList, SUPPORTED_EXTENSIONS);
    if (!validation.valid) {
      message.warning(validation.message);
      return;
    }

    setFiles(fileList);
    if (!nameTouched && fileList.length > 0 && isGroupMode) {
      const base = fileList[0].name.replace(/\.[^.]+$/, "");
      setName(base);
    }
  };

  const handleNameChange = (value: string) => {
    setName(value);
    setNameTouched(true);
  };

  const handleNameBlur = () => {
    setNameTouched(true);
  };

  const resetLoadingState = () => {
    setLoading(false);
    setPolling(false);
    setProgressText(null);
  };

  const handleError = (e: unknown, fallbackMessage: string) => {
    resetLoadingState();
    notify.requestFailed(getErrorMessage(e, fallbackMessage), e);
  };

  const fetchChainsWithHttpTriggers = async (externalOnly: boolean) => {
    setLoadingChains(true);
    try {
      const filter = JSON.stringify({
        chainsWithHttpTriggers: true,
        externalRoutesOnly: externalOnly,
      });
      const folders = await api.getRootFolders(filter, "");
      const targetChainIds = folders.map((f) => f.id);
      const triggers = await api.getElementsByType("any-chain", "http-trigger");
      const validTriggers = triggers.filter(
        (t) =>
          (t.properties && (!t.properties["integrationOperationId"]
            || !t.properties["integrationSpecificationGroupId"]))
              && targetChainIds.includes(t.chainId),
      );
      setElements(validTriggers);
    } catch (e) {
      notify.requestFailed(getErrorMessage(e, "Failed to load chains"), e);
    } finally {
      setLoadingChains(false);
    }
  };

  React.useEffect(() => {
    void fetchChainsWithHttpTriggers(externalRoutesOnly);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [externalRoutesOnly]);

  const handleChainSelect = (chainId: string, checked: boolean) => {
    setSelectedChainIds((prev) =>
      checked ? [...prev, chainId] : prev.filter((id) => id !== chainId)
    );
  };

  const handleGenerateAndImport = async () => {
    if (selectedChainIds.length === 0) return;
    if (!name.trim()) {
      setNameTouched(true);
      return;
    }
    const invalidTriggers: ElementWithChainName[] = elements
      .filter(e => {
        if (!selectedChainIds.includes(e.chainId) || !e.properties?.["httpMethodRestrict"]) {
          return false;
        }
        const httpMethodRestrict = e.properties["httpMethodRestrict"];
        if (typeof httpMethodRestrict !== "string") {
          return false;
        }
        const httpMethods = httpMethodRestrict.split(",");
        return httpMethods.length !== 1;
      })
    if (invalidTriggers.length > 0) {
      setValidationError({
        message: "Single HTTP method has to be specified for next trigger(s):",
        triggers: invalidTriggers
      });
      return;
    }
    setLoading(true);
    setProgressText("Generating API specification...");
    try {
      const selectedElements = elements.filter(e => selectedChainIds.includes(e.chainId));
      const httpTriggerIds: string[] = selectedElements.map(e => e.id);
      const specFile = await api.generateApiSpecification(
        [], [],
        selectedElements.map(e => e.chainId),
        httpTriggerIds,
        externalRoutesOnly,
        ApiSpecificationType.OpenAPI,
        ApiSpecificationFormat.YAML
      );
      setProgressText("Importing generated specification...");
      if (!systemId) {
        resetLoadingState();
        handleError(new Error("System ID is required for import"), "System ID is required for import");
        return;
      }
      const importResult = await api.importSpecificationGroup(
        String(systemId),
        name.trim(),
        [specFile]
      );
      setProgressText("Processing import...");
      setPolling(true);
      await pollStatus(importResult.id);
      await api.modifyHttpTriggerProperties(
        "any-chain",
        importResult.specificationGroupId,
        httpTriggerIds
      );
      resetLoadingState();
      closeContainingModal();
      onSuccess?.();
    } catch (e) {
      resetLoadingState();
      handleError(e, "Failed to generate and import API");
    }
  };

  return (
    <Modal
      title={isGroupMode ? "Import Specification Group" : "Import Specification"}
      open={true}
      onCancel={handleCancel}
      footer={null}
      width={MODAL_WIDTH}
      maskClosable={true}
      destroyOnHidden
    >
      <Tabs
        defaultActiveKey="file"
        items={[
          {
            key: "file",
            label: "Import File",
            children: (
              <div>
                {isGroupMode && (
                  <Form.Item
                    label="Name"
                    required
                    validateStatus={!name.trim() && nameTouched ? "error" : ""}
                    help={!name.trim() && nameTouched ? "Name is required" : undefined}
                    className={styles.formItemMargin}
                  >
                    <Input
                      value={name}
                      onChange={(e) => handleNameChange(e.target.value)}
                      onBlur={handleNameBlur}
                      placeholder="Enter group name"
                      autoFocus
                    />
                  </Form.Item>
                )}
                <div style={{ marginBottom: 16 }}>
                  <Typography.Text type="secondary">
                    Supported file types: {SUPPORTED_EXTENSIONS.join(', ')}
                  </Typography.Text>
                  <br />
                  <Typography.Text type="secondary">
                    Maximum file size: 25MB per file
                  </Typography.Text>
                </div>
                <Upload.Dragger
                  name="files"
                  multiple
                  accept={SUPPORTED_EXTENSIONS.join(',')}
                  beforeUpload={(_file, fileList) => {
                    handleFilesChange(fileList);
                    return false;
                  }}
                  showUploadList={files.length > 0 ? { showRemoveIcon: true } : false}
                  onRemove={(file) => {
                    setFiles((prev) => prev.filter((f) => f.uid !== file.uid));
                    return true;
                  }}
                  fileList={files}
                >
                  <p className="ant-upload-drag-icon">
                    <InboxOutlined />
                  </p>
                  <p className="ant-upload-text">
                    Drag one or more specification files or click to choose
                  </p>
                </Upload.Dragger>
                <Button
                  type="primary"
                  onClick={() => void handleImport()}
                  loading={loading || polling}
                  disabled={!files.length || (isGroupMode && !name.trim()) || loading || polling}
                  className={styles.importButton}
                  block
                >
                  Import {files.length > 1 ? `${files.length} Files` : 'File'}
                </Button>
                {(loading || polling) && (
                  <div className={styles.loadingContainer}>
                    <Spin />
                    <Typography.Text className={styles.loadingText}>
                      {progressText}
                    </Typography.Text>
                  </div>
                )}
              </div>
            ),
          },
          ...(isImplementedService ? [
            {
              key: "chains",
              label: "Import from Chains",
              children: (
                <div>
                  <Form.Item
                    label="Name"
                    required
                    validateStatus={!name.trim() && nameTouched ? "error" : ""}
                    help={!name.trim() && nameTouched ? "Name is required" : undefined}
                    className={styles.formItemMargin}
                  >
                    <Input
                      value={name}
                      onChange={e => handleNameChange(e.target.value)}
                      onBlur={handleNameBlur}
                      placeholder="Enter group name"
                    />
                  </Form.Item>
                  <Checkbox
                    checked={externalRoutesOnly}
                    onChange={e => setExternalRoutesOnly(e.target.checked)}
                    className={styles.checkboxMargin}
                  >
                    External routes only
                  </Checkbox>
                  {loadingChains ? (
                    <Spin className={styles.spinCenter} />
                  ) : (
                    <div className={styles.chainsContainer}>
                      {elements.length === 0 ? (
                        <Typography.Text type="secondary">No chains found</Typography.Text>
                      ) : (
                        elements.map((element: ElementWithChainName) => (
                           (
                            <Card
                              key={element.chainId}
                              className={styles.chainCard}
                              size="small"
                              styles={{ body: {} }}
                            >
                              <div className={styles.chainCardBody}>
                                <Checkbox
                                  checked={selectedChainIds.includes(element.chainId)}
                                  onChange={e => handleChainSelect(element.chainId, e.target.checked)}
                                  className={styles.checkboxRightMargin}
                                />
                                <div>
                                  <Typography.Text strong>
                                    <a
                                      href={`/chains/${element.chainId}/graph`}
                                      target="_blank"
                                      rel="noopener noreferrer"
                                      className={styles.chainNameLink}
                                    >
                                      {element.chainName}
                                    </a>
                                  </Typography.Text>
                                  <Typography.Text className={styles.chainId}>{element.chainId}</Typography.Text>
                                  <div>
                                    <a
                                      href={`/chains/${element.chainId}/graph/${element.id}`}
                                      target="_blank"
                                      rel="noopener noreferrer"
                                      className={styles.triggerNameLink}
                                    >
                                      {element.name}
                                    </a>
                                  </div>
                                </div>
                              </div>
                            </Card>
                          )
                        ))
                      )}
                    </div>
                  )}
                </div>
              ),
            }
          ] : [])
        ]}
      />
      <div className={styles.actionsContainer}>
        <Button
          onClick={() => setSelectedChainIds([])}
          disabled={selectedChainIds.length === 0}
        >
          Clear
        </Button>
        <Button
          type="primary"
          disabled={selectedChainIds.length === 0}
          onClick={() => void handleGenerateAndImport()}
        >
          Create
        </Button>
      </div>
      {validationError && (
        <div className={styles.validationErrorContainer}>
          <div className={styles.validationErrorHeader}>
            <ExclamationCircleOutlined className={styles.validationErrorIcon} />
            <span className={styles.validationErrorTitle}>{validationError.message}</span>
          </div>
          <ul className={styles.validationErrorList}>
            {validationError.triggers.map(t => (
              <li key={t.id}>
                <a href={`/chains/${t.chainId}/graph/${t.id}`} target="_blank" rel="noopener noreferrer">
                  {t.name} on {t.chainName}
                </a>
              </li>
            ))}
          </ul>
          <div className={styles.validationErrorActions}>
            <Button size="small" onClick={() => setValidationError(null)}>
              Close
            </Button>
          </div>
        </div>
      )}
    </Modal>
  );
};

export { ImportSpecificationsModal };
