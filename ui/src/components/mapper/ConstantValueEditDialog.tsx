import { Button, Form, Input, InputNumber, Modal, Select } from "antd";
import { useModalContext } from "../../ModalContextProvider";
import {
  DataType,
  ValueGenerator,
  ValueSupplier,
} from "../../mapper/model/model";
import Checkbox from "antd/lib/checkbox";
import React, { useEffect, useState } from "react";
import { getGeneratorsForType } from "../../mapper/model/generators";
import { TransformationInfo } from "../../mapper/model/transformations";
import { CheckboxChangeEvent } from "antd/lib/checkbox/Checkbox";

export type ConstantValueEditDialogProps = {
  type: DataType;
  valueSupplier?: ValueSupplier;
  onSubmit?: (valueSupplier: ValueSupplier) => void;
};

function isDateTimeGenerator(name: string): boolean {
  return ["currentDate", "currentTime", "currentDateTime"].includes(name);
}

type FormData = {
  generated: boolean;
  value?: string;
  generator?: ValueGenerator;
};

export const ConstantValueEditDialog: React.FC<
  ConstantValueEditDialogProps
> = ({ type, valueSupplier, onSubmit }) => {
  const { closeContainingModal } = useModalContext();
  const [generators, setGenerators] = useState<TransformationInfo[]>([]);
  const [data, setData] = useState<FormData>({ generated: false });

  useEffect(() => {
    setGenerators(getGeneratorsForType(type));
  }, [type]);

  useEffect(() => {
    setData({
      generated: valueSupplier?.kind === "generated",
      value: valueSupplier?.kind === "given" ? valueSupplier.value : "",
      generator:
        valueSupplier?.kind === "generated"
          ? valueSupplier.generator
          : { name: "generateUUID", parameters: [] },
    });
  }, [valueSupplier]);

  return (
    <Modal
      title="Edit constant value"
      open={true}
      onCancel={closeContainingModal}
      footer={[
        <Button key="cancel" onClick={closeContainingModal}>
          Cancel
        </Button>,
        <Button
          key="submit"
          type="primary"
          form="valueEditForm"
          htmlType={"submit"}
        >
          Submit
        </Button>,
      ]}
    >
      <Form<{
        generated: boolean;
        value?: string;
        generator?: ValueGenerator;
      }>
        id="valueEditForm"
        labelCol={{ flex: "150px" }}
        wrapperCol={{ flex: "auto" }}
        labelWrap
        initialValues={{
          generated: valueSupplier?.kind === "generated",
          value:
            valueSupplier?.kind === "given" ? valueSupplier.value : undefined,
          generator:
            valueSupplier?.kind === "generated"
              ? valueSupplier.generator
              : { name: "generateUUID", parameters: [] },
        }}
        onValuesChange={(_changes, values) => {
          setData(values);
        }}
        onFinish={(values) => {
          const supplier: ValueSupplier = values.generated
            ? {
                kind: "generated",
                generator: {
                  name: values.generator?.name ?? "generateUUID",
                  parameters:
                    values.generator?.name === "generateUUID"
                      ? []
                      : (values.generator?.parameters.map((v, i) => {
                          return i === 0 ? (v ?? "false") : (v ?? "");
                        }) ?? []),
                },
              }
            : { kind: "given", value: values.value ?? "" };
          onSubmit?.(supplier);
          closeContainingModal();
        }}
      >
        {generators.length > 0 ? (
          <Form.Item
            style={{ marginLeft: 150 }}
            name="generated"
            valuePropName="checked"
          >
            <Checkbox>generated</Checkbox>
          </Form.Item>
        ) : (
          <></>
        )}
        {data.generated ? (
          <>
            <Form.Item
              name={["generator", "name"]}
              label="Generator"
              rules={[{ required: true }]}
            >
              <Select<string>
                options={generators.map((g) => ({
                  value: g.name,
                  label: g.title,
                }))}
              />
            </Form.Item>
            {isDateTimeGenerator(data.generator?.name ?? "") ? (
              <>
                <Form.Item
                  style={{ marginLeft: 150 }}
                  name={["generator", "parameters", 0]}
                  getValueProps={(value) => ({ checked: value === "true" })}
                  normalize={(value: CheckboxChangeEvent) =>
                    value.target.checked.toString()
                  }
                >
                  <Checkbox>Unix epoch</Checkbox>
                </Form.Item>
                <Form.Item
                  hidden={
                    !!data.generator?.parameters?.[0] &&
                    data.generator.parameters[0] === "true"
                  }
                  name={["generator", "parameters", 1]}
                  label="Format"
                  rules={[
                    {
                      required:
                        !data.generator?.parameters?.[0] ||
                        data.generator?.parameters?.[0] === "false",
                      message: "Format is required",
                    },
                  ]}
                >
                  <Input style={{ width: "100%" }} />
                </Form.Item>
                <Form.Item
                  hidden={
                    !!data.generator?.parameters?.[0] &&
                    data.generator.parameters[0] === "true"
                  }
                  name={["generator", "parameters", 2]}
                  label="Locale"
                >
                  <Input style={{ width: "100%" }} />
                </Form.Item>
                <Form.Item
                  hidden={
                    !!data.generator?.parameters?.[0] &&
                    data.generator.parameters[0] === "true"
                  }
                  name={["generator", "parameters", 3]}
                  label="Time zone"
                >
                  <Input style={{ width: "100%" }} />
                </Form.Item>
              </>
            ) : (
              <></>
            )}
          </>
        ) : (
          <Form.Item
            name="value"
            label={generators.length > 0 ? "Value" : undefined}
          >
            {type.name === "string" ? (
              <Input style={{ width: "100%" }} />
            ) : type.name === "number" ? (
              <InputNumber<string>
                style={{ width: "100%" }}
                stringMode
                autoFocus
              />
            ) : type.name === "boolean" ? (
              <Select
                options={[
                  { value: "true", label: "true" },
                  { value: "false", label: "false" },
                ]}
              />
            ) : (
              <></>
            )}
          </Form.Item>
        )}
      </Form>
    </Modal>
  );
};
