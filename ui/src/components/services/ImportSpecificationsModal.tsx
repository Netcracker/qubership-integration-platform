import React, { useState } from "react";
import { Button, Card, Checkbox, Form, Input, message, Modal, Select, Spin, Tabs, Typography, Upload } from "antd";
import type { RcFile } from "antd/es/upload";
import { useModalContext } from "../../ModalContextProvider";
import { api } from "../../api/api";
import { getErrorMessage } from "../../misc/error-utils";
import { useNotificationService } from "../../hooks/useNotificationService";
import type { ElementWithChainName, SpecApiFile } from "../../api/apiTypes";
import { ApiSpecificationType, ApiSpecificationFormat } from "../../api/apiTypes";
import styles from "./Services.module.css";
import { validateFiles } from "./utils";
import { OverridableIcon } from "../../icons/IconProvider.tsx";
import { VSCodeExtensionApi } from "../../api/rest/vscodeExtensionApi.ts";
import { SourceFlagTag } from "./SourceFlagTag";

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
  const [specApiFiles, setSpecApiFiles] = useState<SpecApiFile[]>([]);
  const [loadingApiFiles, setLoadingApiFiles] = useState(false);
  const [selectedSpecApiFile, setSelectedSpecApiFile] = useState<SpecApiFile | null>(null);

  const isGroupMode = groupMode ?? (!!systemId && !specificationGroupId);
  const isVsCodeContext = api instanceof VSCodeExtensionApi;
  const hasApiTab = isVsCodeContext && isGroupMode;
  const defaultTabKey = hasApiTab ? "api" : "file";
  const [activeTabKey, setActiveTabKey] = useState<string>(defaultTabKey);

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
          systemId!
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
    if (fileList.length > 0 && isGroupMode && (!nameTouched || !name.trim())) {
      const base = fileList[0].name.replace(/\.[^.]+$/, "");
      setName(base);
    }
  };

  const handleNameChange = (value: string) => {
    setName(value);
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

  const fetchSpecApiFiles = async () => {
    if (!isVsCodeContext) return;
    setLoadingApiFiles(true);
    try {
      const files = await api.getSpecApiFiles();
      setSpecApiFiles(files);
    } catch (e) {
      notify.requestFailed(getErrorMessage(e, "Failed to load API contract files"), e);
    } finally {
      setLoadingApiFiles(false);
    }
  };

  React.useEffect(() => {
    if (isVsCodeContext) {
      void fetchSpecApiFiles();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isVsCodeContext]);

  React.useEffect(() => {
    setActiveTabKey(hasApiTab ? "api" : "file");
  }, [hasApiTab]);

  const handleImportFromApi = async () => {
    if (!selectedSpecApiFile) return;
    if (isGroupMode && !name.trim()) {
      setNameTouched(true);
      message.warning("Name is required");
      return;
    }
    setLoading(true);
    setProgressText("Reading specification file...");
    try {
      const fileContent = await api.readSpecificationFileContent(
        selectedSpecApiFile.fileUri,
        selectedSpecApiFile.specificationFilePath
      );
      const fileName = selectedSpecApiFile.specificationFilePath.split('/').pop() || 'specification.yaml';
      const blob = new Blob([fileContent], { type: 'text/yaml' });
      const file = new File([blob], fileName, { type: 'text/yaml', lastModified: Date.now() });
      const fileList: RcFile[] = [file as RcFile];
      setProgressText("Uploading...");
      let res;
      if (isGroupMode) {
        res = await api.importSpecificationGroup(
          systemId!,
          name.trim() || selectedSpecApiFile.name,
          fileList,
        );
      } else {
        res = await api.importSpecification(
          specificationGroupId!,
          fileList,
          systemId!
        );
      }
      setProgressText("Processing...");
      setPolling(true);
      await pollStatus(res.id);
      resetLoadingState();
      closeContainingModal();
      onSuccess?.();
    } catch (e: unknown) {
      handleError(e, 'Import from API failed');
    }
  };

  const handleSpecApiSelect = (value: string | undefined) => {
    const file = value ? specApiFiles.find((item) => item.id === value) ?? null : null;
    setSelectedSpecApiFile(file);
    if (file && isGroupMode && (!nameTouched || !name.trim())) {
      setName(file.name);
    }
  };

  React.useEffect(() => {
    if (selectedSpecApiFile && !specApiFiles.some((file) => file.id === selectedSpecApiFile.id)) {
      setSelectedSpecApiFile(null);
    }
  }, [selectedSpecApiFile, specApiFiles]);

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
        activeKey={activeTabKey}
        onChange={(key) => setActiveTabKey(key)}
        items={[
          ...(hasApiTab ? [
            {
              key: "api",
              label: "Import from API",
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
                        placeholder="Enter group name"
                      />
                    </Form.Item>
                  )}
                  {loadingApiFiles ? (
                    <Spin className={styles.spinCenter} />
                  ) : (
                    <div>
                      {specApiFiles.length === 0 ? (
                        <Typography.Text type="secondary">No API contract files found</Typography.Text>
                      ) : (
                        <Form.Item
                          label="API Contract"
                          className={styles.formItemMargin}
                        >
                          <Select
                            showSearch
                            placeholder="Select API contract"
                            optionFilterProp="label"
                            filterOption={(input, option) =>
                              typeof option?.label === "string" &&
                              option.label.toLowerCase().startsWith(input.toLowerCase())
                            }
                            value={selectedSpecApiFile?.id}
                            onChange={handleSpecApiSelect}
                            allowClear
                          >
                            {specApiFiles.map((file) => (
                              <Select.Option
                                key={file.id}
                                value={file.id}
                                label={file.name}
                              >
                                <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                                  <span>{file.name}</span>
                                  <SourceFlagTag source={file.protocol} />
                                </div>
                              </Select.Option>
                            ))}
                          </Select>
                        </Form.Item>
                      )}
                    </div>
                  )}
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
            }
          ] : []),
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
                    <OverridableIcon name="inbox" />
                  </p>
                  <p className="ant-upload-text">
                    Drag one or more specification files or click to choose
                  </p>
                </Upload.Dragger>
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
        {activeTabKey === "chains" && isImplementedService && (
          <>
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
          </>
        )}
        {activeTabKey === "file" && (
          <Button
            type="primary"
            onClick={() => void handleImport()}
            disabled={!files.length || (isGroupMode && !name.trim()) || loading || polling}
          >
            Import {files.length > 1 ? `${files.length} Files` : "File"}
          </Button>
        )}
        {activeTabKey === "api" && hasApiTab && (
          <Button
            type="primary"
            onClick={() => void handleImportFromApi()}
            disabled={!selectedSpecApiFile || (isGroupMode && !name.trim()) || loading || polling}
          >
            Import
          </Button>
        )}
        <Button onClick={handleCancel}>
          Cancel
        </Button>
      </div>
      {validationError && (
        <div className={styles.validationErrorContainer}>
          <div className={styles.validationErrorHeader}>
            <OverridableIcon name="exclamationCircle" className={styles.validationErrorIcon} />
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
