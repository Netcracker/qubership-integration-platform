import {
  Constant,
  DataType,
  GivenValue,
  ValueSupplier,
} from "../../mapper/model/model.ts";
import { Button, Flex, Form, Input, Modal, Select, SelectProps } from "antd";
import { DataTypes } from "../../mapper/util/types.ts";
import Checkbox from "antd/lib/checkbox";
import React, { useEffect, useMemo, useRef, useState } from "react";
import { useModalContext } from "../../ModalContextProvider.tsx";
import { buildTypeOptions } from "./InlineTypeEdit.tsx";
import { TimestampFormatParameters } from "./transformation/parameters/FormatDateTimeParameters.tsx";
import { getGeneratorsForType } from "../../mapper/model/generators.ts";

type FormData = Constant;

export type EditConstantDialogProps = {
  title: string;
  constant: Constant;
  onSubmit: (changes: Omit<Partial<Constant>, "id">) => void;
};

export const EditConstantDialog: React.FC<EditConstantDialogProps> = ({
  title,
  constant,
  onSubmit,
}) => {
  const { closeContainingModal } = useModalContext();
  const [parametersComponent, setParametersComponent] =
    useState<React.ReactNode>("");
  const [isGenerated, setIsGenerated] = useState<boolean>(false);
  const [generatorName, setGeneratorName] = useState<string>("");
  const [generatorOptions, setGeneratorOptions] = useState<
    SelectProps["options"]
  >([]);
  const [form] = Form.useForm();
  const nameValue = Form.useWatch("name", form) as string;
  const valueSupplier = Form.useWatch("valueSupplier", form) as ValueSupplier;
  /** Last name we treated as “in sync” with the value field; stop syncing once value diverges from it. */
  const nameForValueSyncRef = useRef(constant.name);

  useEffect(() => {
    nameForValueSyncRef.current = constant.name;
  }, [constant.name, constant.id]);

  const typeOptions: ReturnType<typeof buildTypeOptions> = buildTypeOptions(
    DataTypes.stringType(),
    [],
    false,
    false,
  );

  const parametersComponentMap: Record<string, React.ReactNode> = useMemo(
    () => ({
      uuid: <></>,
      currentDate: (
        <TimestampFormatParameters
          offset={0}
          caption={"Generator Parameters"}
          path={["valueSupplier", "generator"]}
        />
      ),
      currentTime: (
        <TimestampFormatParameters
          offset={0}
          caption={"Generator Parameters"}
          path={["valueSupplier", "generator"]}
        />
      ),
      currentDateTime: (
        <TimestampFormatParameters
          offset={0}
          caption={"Generator Parameters"}
          path={["valueSupplier", "generator"]}
        />
      ),
    }),
    [],
  );

  useEffect(() => {
    setParametersComponent(parametersComponentMap[generatorName] || "");
  }, [parametersComponentMap, generatorName]);

  useEffect(() => {
    setIsGenerated(constant.valueSupplier.kind === "generated");
  }, [constant]);

  useEffect(() => {
    setGeneratorName(
      constant.valueSupplier.kind === "generated"
        ? constant.valueSupplier.generator.name
        : "",
    );
  }, [constant]);

  useEffect(() => {
    setGeneratorOptions(
      getGeneratorsForType(constant.type).map((g) => ({
        label: g.title,
        value: g.name,
      })),
    );
  }, [constant]);

  // Mirror name into value until the user edits value away from the synced name.
  useEffect(() => {
    if (!nameValue || isGenerated) {
      if (!nameValue) {
        nameForValueSyncRef.current = "";
      }
      return;
    }
    const currentValue =
      valueSupplier?.kind === "given" ? valueSupplier.value : "";
    const prevName = nameForValueSyncRef.current;
    // Do not treat empty value as “fill from name” once the name was already non-empty
    // (otherwise deleting the last character in the value field restores the full name).
    const shouldSyncFromName =
      (!currentValue && prevName === "") ||
      currentValue === prevName ||
      (Boolean(constant.name) && currentValue === constant.name);

    if (shouldSyncFromName) {
      form.setFieldsValue({
        valueSupplier: { kind: "given", value: nameValue },
      });
    }
    nameForValueSyncRef.current = nameValue;
  }, [nameValue, isGenerated, form, constant.name, valueSupplier]);

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
          form="constantEditForm"
          htmlType={"submit"}
        >
          Submit
        </Button>,
      ]}
    >
      <Form<FormData>
        form={form}
        id="constantEditForm"
        labelCol={{ flex: "100px" }}
        wrapperCol={{ flex: "auto" }}
        labelWrap
        initialValues={{ ...constant }}
        onValuesChange={(_changes: Partial<Constant>, values) => {
          setIsGenerated(values.valueSupplier.kind === "generated");
          setGeneratorName(
            values.valueSupplier.kind === "generated"
              ? values.valueSupplier.generator.name
              : "",
          );
          setGeneratorOptions(
            getGeneratorsForType(values.type).map((g) => ({
              label: g.title,
              value: g.name,
            })),
          );
        }}
        onFinish={(changes) => {
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
              value: typeOptions.find((option) =>
                DataTypes.same(option.type, type, []),
              )?.value,
            };
          }}
          normalize={(value) => {
            return (
              typeOptions.find((option) => option.value === value)?.type ??
              constant.type
            );
          }}
        >
          <Select options={typeOptions} />
        </Form.Item>
        <Flex vertical={false} style={{ marginLeft: 100 }}>
          {generatorOptions?.length === 0 ? (
            <></>
          ) : (
            <Form.Item
              label={null}
              name={"valueSupplier"}
              valuePropName={"checked"}
              getValueProps={(supplier: ValueSupplier) => {
                return { checked: supplier.kind === "generated" };
              }}
              normalize={(generated: boolean) => {
                return generated
                  ? {
                      kind: "generated",
                      generator: { name: "generateUUID", parameters: [] },
                    }
                  : { kind: "given", value: "" };
              }}
            >
              <Checkbox
                onChange={(event) => setIsGenerated(event.target.checked)}
              >
                Generated
              </Checkbox>
            </Form.Item>
          )}
          {isGenerated ? (
            <Form.Item
              style={{ width: "100%" }}
              label={null}
              name={["valueSupplier", "generator", "name"]}
            >
              <Select<string> options={generatorOptions} />
            </Form.Item>
          ) : (
            <Form.Item
              style={{ flexGrow: 1 }}
              label={null}
              name={"valueSupplier"}
              getValueProps={(valueSupplier: GivenValue) => {
                return {
                  value: valueSupplier.value,
                };
              }}
              normalize={(value: string): GivenValue => {
                return { kind: "given", value: value };
              }}
            >
              <Input />
            </Form.Item>
          )}
        </Flex>
        <Flex vertical style={{ overflowY: "auto", flexGrow: 1 }}>
          {parametersComponent}
        </Flex>
      </Form>
    </Modal>
  );
};
