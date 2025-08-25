import React, { ChangeEvent, useCallback, useEffect, useState } from "react";
import {
  Button,
  CheckboxOptionType,
  Form,
  Input,
  Modal,
  Radio,
  RadioChangeEvent,
} from "antd";
import { useModalContext } from "../../ModalContextProvider.tsx";
import styles from "./ChainElementModification.module.css";
import {
  Element,
  PatchElementRequest,
  LibraryElementProperty,
  PropertyType,
} from "../../api/apiTypes.ts";
import TextArea from "antd/lib/input/TextArea";
import { useElement } from "../../hooks/useElement.tsx";
import { useLibraryElement } from "../../hooks/useLibraryElement.tsx";
import YAML from "yaml";
import { InfoCircleOutlined } from "@ant-design/icons";
import { useNotificationService } from "../../hooks/useNotificationService.tsx";
import {ChainGraphNode} from "../graph/nodes/ChainGraphNodeTypes.ts";

type ElementModificationProps = {
  node: ChainGraphNode;
  chainId: string;
  elementId: string;
  onSubmit: (changedElement: Element, node: ChainGraphNode) => void;
  onClose?: () => void;
};

function constructTitle(name: string, type?: string): string {
  return type ? `${name} (${type})` : `${name}`;
}

type ChainElementPropertiesFormData = {
  name: string;
  description: string;
  [PropertyType.COMMON]: string;
  [PropertyType.ADVANCED]: string;
  [PropertyType.HIDDEN]: string;
  [PropertyType.UNKNOWN]: string;
};

export const ChainElementModification: React.FC<ElementModificationProps> = ({
  node,
  chainId,
  elementId,
  onSubmit,
  onClose,
}) => {
  const { isLoading: libraryElementIsLoading, libraryElement } =
    useLibraryElement(node.data.elementType);
  const [isLoading, setIsLoading] = useState(false);
  const [isDescriptionModalOpen, setIsDescriptionModalOpen] = useState(false);
  const { updateElement } = useElement();
  const { closeContainingModal } = useModalContext();
  const [form] = Form.useForm();
  const [title, setTitle] = useState(constructTitle(`${node.data.label}`));

  const paramsOptions: CheckboxOptionType<string>[] = [
    { label: "Common", value: PropertyType.COMMON },
    { label: "Advanced", value: PropertyType.ADVANCED },
    { label: "Hidden", value: PropertyType.HIDDEN },
    { label: "Unknown", value: PropertyType.UNKNOWN },
  ];
  const textAreaOptions = {
    minRows: 3,
    maxRows: 10,
  };
  const [paramsRadioValue, setParamsRadioValue] = useState(PropertyType.COMMON);
  const notificationService = useNotificationService();

  const handleOk = async (data: ChainElementPropertiesFormData) => {
    setIsLoading(true);
    try {
      const properties = constructProperties(data);
      if (properties === undefined) {
        return;
      }

      const request: PatchElementRequest = {
        name: data.name,
        description: data.description,
        type: node.data.elementType,
        parentElementId: node.parentId,
        properties: properties,
      };
      const changedElement: Element | undefined = await updateElement(
        chainId,
        elementId,
        request,
      );
      if (changedElement) {
        onSubmit?.(changedElement, node);
      }
    } catch (error) {
      notificationService.errorWithDetails(
        "Save element failed",
        "Failed to save element",
        error,
      );
    } finally {
      setIsLoading(false);
      handleClose();
    }
  };

  const handleClose = () => {
    closeContainingModal();
    onClose?.();
  };

  const constructProperties = (
    data: ChainElementPropertiesFormData,
  ): Record<string, unknown> | undefined => {
    try {
      return {
        ...(data[PropertyType.COMMON]
          ? (YAML.parse(data[PropertyType.COMMON]) as object)
          : {}),
        ...(data[PropertyType.ADVANCED]
          ? (YAML.parse(data[PropertyType.ADVANCED]) as object)
          : {}),
        ...(data[PropertyType.HIDDEN]
          ? (YAML.parse(data[PropertyType.HIDDEN]) as object)
          : {}),
        ...(data[PropertyType.UNKNOWN]
          ? (YAML.parse(data[PropertyType.UNKNOWN]) as object)
          : {}),
      };
    } catch (error) {
      notificationService.errorWithDetails(
        "Parse element failed",
        "Failed to construct element properties",
        error,
      );
    }
  };

  const setPropertiesFormValue = useCallback(
    (value: Record<string, unknown>, type: PropertyType) => {
      form.setFieldValue(
        type,
        value && Object.keys(value).length
          ? YAML.stringify(value, null, 2).trim()
          : undefined,
      );
    },
    [form],
  );

  const setUnknownProperties = useCallback(
    (properties: Record<string, unknown>) => {
      const result = Object.keys(properties)
        .filter(
          (key) =>
            !(
              libraryElement!.properties[PropertyType.COMMON].find(
                (val) => val.name === key,
              ) ||
              libraryElement!.properties[PropertyType.ADVANCED].find(
                (val) => val.name === key,
              ) ||
              libraryElement!.properties[PropertyType.HIDDEN].find(
                (val) => val.name === key,
              )
            ),
        )
        .reduce((obj: Record<string, unknown>, key) => {
          obj[key] = properties[key];
          return obj;
        }, {});
      setPropertiesFormValue(result, PropertyType.UNKNOWN);
    },
    [libraryElement, setPropertiesFormValue],
  );

  const setPropertiesByCategory = useCallback(
    (properties: Record<string, unknown>, type: PropertyType) => {
      const result = Object.keys(properties)
        .filter((key) => {
          return libraryElement!.properties[type].find(
            (val: LibraryElementProperty) => val.name === key,
          );
        })
        .reduce((obj: Record<string, unknown>, key) => {
          obj[key] = properties[key];
          return obj;
        }, {});
      setPropertiesFormValue(result, type);
    },
    [libraryElement, setPropertiesFormValue],
  );

  const categorizeProperties = useCallback(
    (properties: Record<string, unknown>) => {
      if (!properties || !libraryElement) {
        return;
      }
      setPropertiesByCategory(properties, PropertyType.COMMON);
      setPropertiesByCategory(properties, PropertyType.ADVANCED);
      setPropertiesByCategory(properties, PropertyType.HIDDEN);
      setUnknownProperties(properties);
    },
    [libraryElement, setPropertiesByCategory, setUnknownProperties],
  );

  const onNameChange = ({
    target: { value },
  }: ChangeEvent<HTMLInputElement>) => {
    setTitle(constructTitle(value, libraryElement?.title));
  };

  const onParamsRadioChange = ({ target: { value } }: RadioChangeEvent) => {
    setParamsRadioValue(value as PropertyType);
  };

  const openDescriptionModal = () => {
    setIsDescriptionModalOpen(true);
  };

  const closeDescriptionModal = () => {
    setIsDescriptionModalOpen(false);
  };

  useEffect(() => {
    setTitle(constructTitle(`${node.data.label}`, libraryElement?.title));
    categorizeProperties(node.data.properties);
  }, [categorizeProperties, libraryElement, node]);

  return (
    <Modal
      width={"80vw"}
      open
      title={title}
      onCancel={handleClose}
      maskClosable={false}
      centered
      loading={libraryElementIsLoading}
      footer={[
        <Button key="cancel" onClick={handleClose}>
          Cancel
        </Button>,
        <Button
          key="submit"
          type="primary"
          form="elementModificationForm"
          htmlType={"submit"}
          loading={isLoading}
        >
          Save
        </Button>,
      ]}
      classNames={{
        content: styles["modal"],
      }}
    >
      <Form<ChainElementPropertiesFormData>
        id="elementModificationForm"
        disabled={isLoading}
        form={form}
        onFinish={(values) => void handleOk(values)}
        layout="horizontal"
        labelCol={{ span: 4 }}
        style={{ width: "100%" }}
        initialValues={{
          ["name"]: node.data.label,
          ["description"]: node.data.description,
          ["properties-radio-button"]: paramsRadioValue,
          [PropertyType.UNKNOWN]: YAML.stringify(node.data.properties, null, 2),
        }}
      >
        <Form.Item
          label="Name"
          name="name"
          rules={[
            { required: true, message: "Please specify name of the element" },
          ]}
        >
          <Input onChange={onNameChange} />
        </Form.Item>
        <Form.Item label="Description" name="description">
          <Input />
        </Form.Item>
        <Form.Item name="properties-radio-button" label="Properties">
          <Radio.Group
            optionType="button"
            buttonStyle="solid"
            options={paramsOptions}
            onChange={onParamsRadioChange}
            value={paramsRadioValue}
          />
          <InfoCircleOutlined
            onClick={openDescriptionModal}
            className={styles.description_icon}
          />
        </Form.Item>
        <Form.Item
          name={PropertyType.COMMON}
          hidden={paramsRadioValue !== PropertyType.COMMON}
        >
          <TextArea autoSize={textAreaOptions} />
        </Form.Item>
        <Form.Item
          name={PropertyType.ADVANCED}
          hidden={paramsRadioValue !== PropertyType.ADVANCED}
        >
          <TextArea autoSize={textAreaOptions} />
        </Form.Item>
        <Form.Item
          name={PropertyType.HIDDEN}
          hidden={paramsRadioValue !== PropertyType.HIDDEN}
        >
          <TextArea autoSize={textAreaOptions} />
        </Form.Item>
        <Form.Item
          name={PropertyType.UNKNOWN}
          hidden={paramsRadioValue !== PropertyType.UNKNOWN}
        >
          <TextArea autoSize={textAreaOptions} />
        </Form.Item>
      </Form>
      <Modal
        open={isDescriptionModalOpen}
        title={`${libraryElement?.title} ${paramsRadioValue} parameters description`}
        onCancel={closeDescriptionModal}
        footer={null}
        classNames={{
          content: styles["modal"],
        }}
      >
        <TextArea
          autoSize={{
            minRows: 10,
            maxRows: 20,
          }}
          value={YAML.stringify(
            libraryElement?.properties[paramsRadioValue],
            null,
            2,
          )}
          readOnly={true}
        />
      </Modal>
    </Modal>
  );
};
