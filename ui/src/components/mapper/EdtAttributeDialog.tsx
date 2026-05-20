import {
  Attribute,
  AttributeKind,
  DataType,
  TypeDefinition,
} from "../../mapper/model/model.ts";
import React, { useEffect, useState } from "react";
import { useModalContext } from "../../ModalContextProvider.tsx";
import { Button, Flex, Form, Input, Modal, Select } from "antd";
import Checkbox from "antd/lib/checkbox";
import { buildTypeOptions } from "./InlineTypeEdit.tsx";
import { DataTypes } from "../../mapper/util/types.ts";

type FormData = Attribute & {
  hasDefaultValue: boolean;
};

export type EditAttributeDialogProps = {
  title: string;
  kind: AttributeKind;
  attribute: Attribute;
  typeDefinitions: TypeDefinition[];
  onSubmit: (changes: Omit<Partial<Attribute>, "id">) => void;
};

export const EditAttributeDialog: React.FC<EditAttributeDialogProps> = ({
  title,
  kind,
  attribute,
  typeDefinitions,
  onSubmit,
}) => {
  const { closeContainingModal } = useModalContext();
  const [options, setOptions] = useState<ReturnType<typeof buildTypeOptions>>(
    [],
  );
  const [hasDefaultValue, setHasDefaultValue] = useState<boolean>(false);

  useEffect(() => {
    const enableArrayTypes = kind !== "header";
    const enableObjectType = kind !== "header";
    setOptions(
      buildTypeOptions(
        attribute.type,
        typeDefinitions,
        enableArrayTypes,
        enableObjectType,
      ),
    );
  }, [attribute.type, kind, typeDefinitions]);

  useEffect(() => {
    setHasDefaultValue(attribute.defaultValue !== undefined);
  }, [attribute]);

  return (
    <Modal
      title={title}
      open={true}
      onCancel={closeContainingModal}
      footer={[
        <Button key="cancel" onClick={closeContainingModal}>
          Cancel
        </Button>,
        <Button
          key="submit"
          type="primary"
          form="attributeEditForm"
          htmlType={"submit"}
        >
          Submit
        </Button>,
      ]}
    >
      <Form<FormData>
        id="attributeEditForm"
        labelCol={{ flex: "70px" }}
        wrapperCol={{ flex: "auto" }}
        labelWrap
        initialValues={{
          ...attribute,
          hasDefaultValue: attribute.defaultValue !== undefined,
        }}
        onValuesChange={(_changes, values) => {
          setHasDefaultValue(values.hasDefaultValue);
        }}
        onFinish={(values) => {
          const changes = {
            name: values.name,
            type: values.type,
            defaultValue: values.hasDefaultValue
              ? values.defaultValue
              : undefined,
            required: values.required,
          };
          onSubmit?.(changes);
          closeContainingModal();
        }}
      >
        <Form.Item label={"Name"} name={"name"} rules={[{ required: true }]}>
          <Input />
        </Form.Item>
        <Form.Item
          style={{ width: "100%" }}
          label={"Type"}
          name={"type"}
          rules={[{ required: true }]}
          getValueProps={(type: DataType) => {
            return {
              value: options.find((option) =>
                DataTypes.same(option.type, type, typeDefinitions),
              )?.value,
            };
          }}
          normalize={(value) => {
            return (
              options.find((option) => option.value === value)?.type ??
              attribute.type
            );
          }}
        >
          <Select options={options} />
        </Form.Item>
        <Flex vertical={false} style={{ marginLeft: 70 }}>
          <Form.Item
            label={null}
            name={"hasDefaultValue"}
            valuePropName={"checked"}
          >
            <Checkbox style={{ color: "var(--vscode-foreground)" }}>
              Default value
            </Checkbox>
          </Form.Item>
          <Form.Item style={{ flexGrow: 1 }} label={null} name={"defaultValue"}>
            <Input disabled={!hasDefaultValue} />
          </Form.Item>
        </Flex>
        <Form.Item
          style={{ marginLeft: 70 }}
          label={null}
          name={"required"}
          valuePropName={"checked"}
        >
          <Checkbox style={{ color: "var(--vscode-foreground)" }}>
            Required
          </Checkbox>
        </Form.Item>
      </Form>
    </Modal>
  );
};
