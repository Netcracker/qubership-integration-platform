import React, { useState, useRef, useCallback, useEffect } from "react";
import {
  Modal,
  Form,
  Input,
  Badge,
  Button,
  Flex,
  Select,
  Tooltip,
  Radio,
  Space,
} from "antd";
import {
  Environment,
  EnvironmentRequest,
  EnvironmentSourceType,
} from "../../../api/apiTypes";
import { useServiceContext } from "../detail/ServiceParametersPage";
import { OverridableIcon } from "../../../icons/IconProvider.tsx";
import { environmentLabelOptions } from "../utils.tsx";
import { isAmqpProtocol, isKafkaProtocol } from "../../../misc/protocol-utils";
import { useModalsContext } from "../../../Modals.tsx";
import { UnsavedChangesModal } from "../../modal/UnsavedChangesModal.tsx";
import { useModalContext } from "../../../ModalContextProvider.tsx";
import { treeExpandIcon } from "../../table/TreeExpandIcon.tsx";
import {
  KeyValuePairsEditor,
  KeyValuePairsEditorRef,
  KeyValuePairsEditorTab,
} from "../../table/kv/KeyValuePairsEditor.tsx";
import {
  buildKeyValuePairsFromObject,
  buildObjectFromKeyValuePairs,
  KeyValuePair,
} from "../../../misc/key-value-text-util.ts";
import styles from "./EnvironmentParamsModal.module.css";

type EnvironmentAction = "create" | "edit";

type EnvironmentParamsModalProps = {
  action: EnvironmentAction;
  environment: Environment;
  onSubmit: (request: EnvironmentRequest) => Promise<void>;
};

type EnvironmentFormData = Omit<EnvironmentRequest, "properties"> & {
  properties: KeyValuePair[];
};

export const EnvironmentParamsModal: React.FC<EnvironmentParamsModalProps> = ({
  action,
  environment,
  onSubmit,
}) => {
  const [form] = Form.useForm();
  const { closeContainingModal } = useModalContext();
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [showProperties, setShowProperties] = useState(false);
  const [hasChanges, setHasChanges] = useState(false);
  const { showModal } = useModalsContext();
  const [isMaasSelected, setIsMaasSelected] = useState(false);
  const [propertiesCount, setPropertiesCount] = useState<number>(0);
  const [propertiesTab, setPropertiesTab] =
    useState<KeyValuePairsEditorTab>("table");

  const ExpandButton = treeExpandIcon<void>();
  const propertiesEditorRef = useRef<KeyValuePairsEditorRef>(null);

  const system = useServiceContext();
  const protocol = system?.protocol || "";
  const isAsyncProtocolSupported =
    isKafkaProtocol(protocol) || isAmqpProtocol(protocol);

  useEffect(() => {
    setPropertiesCount(Object.keys(environment?.properties ?? {}).length);
    setIsMaasSelected(
      environment?.sourceType === EnvironmentSourceType.MAAS ||
        environment?.sourceType === EnvironmentSourceType.MAAS_BY_CLASSIFIER,
    );
  }, [environment]);

  useEffect(() => {
    propertiesEditorRef.current?.activateTab(propertiesTab);
  }, [propertiesTab, showProperties]);

  const handleSubmit = useCallback(async () => {
    try {
      setIsSubmitting(true);
      const values = (await form.validateFields()) as EnvironmentFormData;
      const request = {
        ...values,
        address:
          values.sourceType === EnvironmentSourceType.MAAS ||
          values.sourceType === EnvironmentSourceType.MAAS_BY_CLASSIFIER
            ? undefined
            : values.address,
        properties: buildObjectFromKeyValuePairs(values.properties),
      };
      await onSubmit(request);
      closeContainingModal();
    } catch (_error) {
      // Do nothing, i.e. do not close form
    } finally {
      setIsSubmitting(false);
    }
  }, [form, onSubmit, closeContainingModal]);

  const handleClose = useCallback(() => {
    if (hasChanges) {
      showModal({
        component: (
          <UnsavedChangesModal
            onYes={() => void handleSubmit()}
            onNo={closeContainingModal}
            onCancelQuestion={() => {
              /* stay in environment modal */
            }}
          />
        ),
      });
    } else {
      closeContainingModal();
    }
  }, [closeContainingModal, handleSubmit, hasChanges, showModal]);

  const onValuesChange = useCallback(
    (_: Partial<EnvironmentFormData>, values: EnvironmentFormData) => {
      setHasChanges(true);
      setPropertiesCount(values.properties?.length ?? 0);
      setIsMaasSelected(
        values.sourceType === EnvironmentSourceType.MAAS ||
          values.sourceType === EnvironmentSourceType.MAAS_BY_CLASSIFIER,
      );
    },
    [],
  );

  return (
    <Modal
      open={true}
      title={action === "edit" ? "Edit Environment" : "Add Environment"}
      onCancel={handleClose}
      onOk={() => void handleSubmit()}
      okText="Save"
      cancelText="Cancel"
      width={"90vw"}
      confirmLoading={isSubmitting}
    >
      <Form<EnvironmentFormData>
        className={styles["environment-edit-form"]}
        form={form}
        layout="vertical"
        initialValues={{
          ...environment,
          sourceType:
            environment.sourceType === EnvironmentSourceType.MAAS
              ? EnvironmentSourceType.MAAS_BY_CLASSIFIER
              : environment.sourceType,
          properties: buildKeyValuePairsFromObject(
            environment.properties ?? {},
          ),
        }}
        onValuesChange={onValuesChange}
      >
        <Form.Item
          label="Name"
          name="name"
          rules={[{ required: true, message: "Name is required" }]}
        >
          <Input />
        </Form.Item>

        <Form.Item label="Labels" name="labels">
          <Select
            mode="multiple"
            allowClear
            style={{ width: "100%" }}
            options={environmentLabelOptions}
            placeholder="Select environment labels"
          />
        </Form.Item>

        <Form.Item
          label="Source type"
          name="sourceType"
          layout="horizontal"
          colon={false}
          labelCol={{ className: styles["source-type-label"] }}
        >
          <Radio.Group
            optionType={"default"}
            disabled={!isAsyncProtocolSupported}
          >
            <Radio.Button value="MANUAL">Manual</Radio.Button>
            <Radio.Button value="MAAS_BY_CLASSIFIER">
              <Space>
                <span>MAAS</span>
                <Tooltip
                  title={
                    "This type allows the use of the MaaS classifier to obtain connection parameters when creating a chain snapshot."
                  }
                >
                  <OverridableIcon name={"info"} />
                </Tooltip>
              </Space>
            </Radio.Button>
          </Radio.Group>
        </Form.Item>

        <Form.Item
          label="Address"
          name="address"
          hidden={isMaasSelected}
          rules={
            isMaasSelected
              ? []
              : [{ required: true, message: "Address is required" }]
          }
        >
          <Input />
        </Form.Item>

        <Form.Item
          className={
            "flex-form-item flex-form-item-h100 " +
            styles["properties-form-item"]
          }
          label={
            <Flex
              vertical={false}
              justify={"space-between"}
              align={"center"}
              style={{ width: "100%" }}
            >
              <Flex
                vertical={false}
                justify={"start"}
                align={"center"}
                style={{ height: "32px" }}
              >
                <ExpandButton
                  prefixCls={""}
                  expanded={showProperties}
                  record={undefined}
                  expandable={true}
                  onExpand={() => setShowProperties((prev) => !prev)}
                />
                <span>Properties</span>
                <Badge
                  count={propertiesCount}
                  style={{
                    backgroundColor: "var(--vscode-button-background, #1677ff)",
                    marginLeft: 8,
                  }}
                />
              </Flex>
              <Space style={{ display: showProperties ? "flex" : "none" }}>
                <Radio.Group
                  block
                  optionType={"button"}
                  value={propertiesTab}
                  onChange={(event) => {
                    setPropertiesTab(
                      event.target.value as KeyValuePairsEditorTab,
                    );
                  }}
                >
                  <Radio.Button value="table">Table</Radio.Button>
                  <Radio.Button value="text">Text</Radio.Button>
                </Radio.Group>
                <Tooltip title="Add property">
                  <Button
                    data-testid="environment-properties-add"
                    aria-label="Add property"
                    disabled={propertiesTab !== "table"}
                    type={"text"}
                    icon={<OverridableIcon name={"plus"} />}
                    onClick={() => {
                      propertiesEditorRef.current?.add();
                    }}
                  />
                </Tooltip>
              </Space>
            </Flex>
          }
          name="properties"
        >
          {showProperties && (
            <KeyValuePairsEditor
              actionRef={propertiesEditorRef}
              renderTabBar={() => <></>}
            />
          )}
        </Form.Item>
      </Form>
    </Modal>
  );
};
