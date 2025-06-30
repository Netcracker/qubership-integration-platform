import { Button, Form, Input, Select } from "antd";
import React, { useContext, useEffect, useState } from "react";
import TextArea from "antd/lib/input/TextArea";
import { Chain } from "../api/apiTypes.ts";
import { useForm } from "antd/lib/form/Form";
import { ChainContext } from "./ChainPage.tsx";

type FormData = {
  name: string;
  labels: string[];
  description: string;
  businessDescription: string;
  assumptions: string;
  outOfScope: string;
};

export const ChainProperties: React.FC = () => {
  const [isUpdating, setIsUpdating] = useState<boolean>(false);
  const [hasChanges, setHasChanges] = useState<boolean>(false);
  const [form] = useForm();

  const chainContext = useContext(ChainContext);

  useEffect(() => {
    if (chainContext?.chain) {
      const formData = {
        name: chainContext.chain.name,
        labels: chainContext.chain.labels.map((label) => label.name),
        description: chainContext.chain.description,
        businessDescription: chainContext.chain.businessDescription,
        assumptions: chainContext.chain.assumptions,
        outOfScope: chainContext.chain.outOfScope,
      };
      form.setFieldsValue(formData);
    }
  }, [chainContext, form]);

  return (
    <Form<FormData>
      form={form}
      disabled={isUpdating}
      labelCol={{ flex: "150px" }}
      wrapperCol={{ flex: "auto" }}
      labelWrap
      onChange={() => setHasChanges(true)}
      onFinish={(values) => {
        if (chainContext) {
          const changes: Partial<Chain> = {
            name: values.name,
            labels: values.labels?.map((l) => ({
              name: l,
              technical: false,
            })),
            description: values.description,
            businessDescription: values.businessDescription,
            assumptions: values.assumptions,
            outOfScope: values.outOfScope,
          };
          setIsUpdating(true);
          void chainContext
            .update(changes)
            .then(() => setHasChanges(false))
            .finally(() => setIsUpdating(false));
        }
      }}
    >
      <Form.Item label="Name" name="name" rules={[{ required: true }]}>
        <Input />
      </Form.Item>
      <Form.Item label="Labels" name="labels">
        <Select
          mode="tags"
          tokenSeparators={[" "]}
          classNames={{ popup: { root: "not-displayed" } }}
          onChange={() => setHasChanges(true)}
          suffixIcon={<></>}
        />
      </Form.Item>
      <Form.Item label="Description" name="description">
        <TextArea style={{ height: 120, resize: "none" }} />
      </Form.Item>
      <Form.Item label="Business Description" name="businessDescription">
        <TextArea style={{ height: 120, resize: "none" }} />
      </Form.Item>
      <Form.Item label="Assumptions" name="assumptions">
        <TextArea style={{ height: 120, resize: "none" }} />
      </Form.Item>
      <Form.Item label="Out of Scope" name="outOfScope">
        <TextArea style={{ height: 120, resize: "none" }} />
      </Form.Item>
      <Button
        type="primary"
        htmlType="submit"
        loading={isUpdating}
        disabled={!hasChanges}
        style={{ marginLeft: 150 }}
      >
        Apply
      </Button>
    </Form>
  );
};
