import React, { createContext, useEffect, useMemo, useState } from "react";
import {
  MappingAction,
  MappingDescription,
  Transformation,
} from "../../mapper/model/model";
import { Button, Flex, Form, Modal, Select } from "antd";
import { useModalContext } from "../../ModalContextProvider";
import { TRANSFORMATIONS } from "../../mapper/model/transformations";
import { capitalize } from "../../misc/format-utils";
import { TrimParameters } from "./transformation/parameters/TrimParameters";
import { ReplaceAllParameters } from "./transformation/parameters/ReplaceAllParameters";
import { DefaultValueParameters } from "./transformation/parameters/DefautValueParameters";
import { FormatDateTimeParameters } from "./transformation/parameters/FormatDateTimeParameters";
import { ExpressionParameters } from "./transformation/parameters/ExpressionParameters";
import { ConditionalParameters } from "./transformation/parameters/ConditionalParameters";
import { DictionaryParameters } from "./transformation/parameters/DictionaryParameters";
import { MappingUtil } from "../../mapper/util/mapping.ts";
import TextArea from "antd/lib/input/TextArea";

export type TransformationContextProps = {
  mappingDescription: MappingDescription;
  action: MappingAction;
};

export const TransformationContext = createContext<TransformationContextProps>({
  mappingDescription: MappingUtil.emptyMapping(),
  action: {
    id: "",
    sources: [],
    target: { type: "attribute", kind: "body", path: [] },
  },
});

export type TransformationEditDialogProps = {
  transformation?: Transformation;
  description?: string;
  enableDescription?: boolean;
  onSubmit?: (
    transformation: Transformation | undefined,
    description: string,
  ) => void;
};
export const TransformationEditDialog: React.FC<
  TransformationEditDialogProps
> = ({ transformation, description, enableDescription, onSubmit }) => {
  const { closeContainingModal } = useModalContext();
  const [form] = Form.useForm<Transformation>();
  const [parametersComponent, setParametersComponent] =
    useState<React.ReactNode>("");
  const [descriptionValue, setDescriptionValue] = useState<string>("");

  const parametersComponentMap: Record<string, React.ReactNode> = useMemo(
    () => ({
      conditional: <ConditionalParameters />,
      defaultValue: <DefaultValueParameters />,
      dictionary: <DictionaryParameters />,
      expression: <ExpressionParameters />,
      formatDateTime: <FormatDateTimeParameters />,
      trim: <TrimParameters />,
      replaceAll: <ReplaceAllParameters />,
    }),
    [],
  );

  useEffect(() => {
    setParametersComponent(
      parametersComponentMap[transformation?.name ?? ""] || "",
    );
  }, [parametersComponentMap, transformation]);

  useEffect(() => {
    form.setFieldsValue({
      name: transformation?.name ?? "",
      parameters: transformation?.parameters ?? [],
    });
  }, [form, transformation]);

  return (
    <Modal
      title="Edit transformation"
      open={true}
      onCancel={closeContainingModal}
      footer={[
        <Button key="cancel" onClick={closeContainingModal}>
          Cancel
        </Button>,
        <Button
          key="submit"
          type="primary"
          form="transformationEditForm"
          onClick={() => {
            form.submit();
          }}
        >
          Save
        </Button>,
      ]}
    >
      <Form<Transformation>
        form={form}
        style={{ height: "60vh", display: "flex", flexDirection: "column" }}
        id="transformationEditForm"
        labelCol={{ flex: "150px" }}
        wrapperCol={{ flex: "auto" }}
        labelWrap
        onValuesChange={(changes: Partial<Transformation>) => {
          if (changes.name !== undefined) {
            form.setFieldValue("parameters", []);
            setParametersComponent(parametersComponentMap[changes.name] || "");
          }
        }}
        onFinish={(values) => {
          onSubmit?.(values.name ? values : undefined, descriptionValue);
          closeContainingModal();
        }}
      >
        <Form.Item name="name" label="Transfomation">
          <Select<string>
            options={[
              {
                value: "",
                label: "None",
              },
              ...TRANSFORMATIONS.map((t) => ({
                value: t.name,
                label: capitalize(t.title),
              })),
            ]}
          />
        </Form.Item>
        <Flex vertical style={{ overflowY: "auto", flexGrow: 1 }}>
          {parametersComponent}
        </Flex>
      </Form>
      {enableDescription ? (
        <TextArea
          placeholder={"Description"}
          defaultValue={description}
          autoSize={{ minRows: 2, maxRows: 2 }}
          onChange={(event) => setDescriptionValue(event.target.value)}
        />
      ) : (
        <></>
      )}
    </Modal>
  );
};
