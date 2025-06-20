import { Button, Flex, Form, Input, InputRef, Modal, Select, Tabs } from "antd";
import React, { useEffect, useRef, useState } from "react";
import { useModalContext } from "../../ModalContextProvider.tsx";
import { ChainCreationRequest } from "../../api/apiTypes.ts";
import TextArea from "antd/lib/input/TextArea";
import Checkbox from "antd/lib/checkbox";

export type ChainCreateProps = {
  onSubmit: (
    request: ChainCreationRequest,
    openChain: boolean,
    newTab: boolean,
  ) => void;
};

type FormData = Omit<ChainCreationRequest, "labels"> & {
  labels: string[];
  openChain: boolean;
  newTab: boolean;
};

export const ChainCreate: React.FC<ChainCreateProps> = ({ onSubmit }) => {
  const [confirmLoading, setConfirmLoading] = useState(false);
  const [isOpenChecked, setIsOpenChecked] = useState<boolean>(true);
  const { closeContainingModal } = useModalContext();
  const nameInput = useRef<InputRef>(null);

  useEffect(() => {
    nameInput.current?.focus();
  }, [nameInput]);

  return (
    <Modal
      title="New Chain"
      open={true}
      onCancel={closeContainingModal}
      footer={[
        <Button key="cancel" onClick={closeContainingModal}>
          Cancel
        </Button>,
        <Button
          key="submit"
          type="primary"
          form="createChainForm"
          htmlType={"submit"}
          loading={confirmLoading}
        >
          Submit
        </Button>,
      ]}
    >
      <Form<FormData>
        id="createChainForm"
        labelCol={{ flex: "150px" }}
        wrapperCol={{ flex: "auto" }}
        labelWrap
        initialValues={{ openChain: isOpenChecked }}
        onFieldsChange={(changedFields) => {
          changedFields
            .filter((field) => field.name[0] === "openChain")
            .forEach((field) => {
              setIsOpenChecked(field.value);
            });
        }}
        onFinish={async (values) => {
          setConfirmLoading(true);
          try {
            onSubmit?.(
              {
                ...{ ...values, openChain: undefined, newTab: undefined },
                labels: values.labels?.map((s) => ({
                  name: s,
                  technical: false,
                })) ?? [],
              },
              values.openChain,
              values.newTab,
            );
            closeContainingModal();
          } finally {
            setConfirmLoading(false);
          }
        }}
      >
        <Tabs
          items={[
            {
              key: "generalInfo",
              label: "General Info",
              children: (
                <>
                  <Form.Item
                    label="Name"
                    name="name"
                    rules={[{ required: true, message: "Name is required" }]}
                  >
                    <Input ref={nameInput} />
                  </Form.Item>
                  <Form.Item label="Labels" name="labels">
                    <Select
                      mode="tags"
                      tokenSeparators={[" "]}
                      popupClassName="not-displayed"
                      suffixIcon={<></>}
                    />
                  </Form.Item>
                  <Form.Item label="Description" name="description">
                    <TextArea style={{ height: 120, resize: "none" }} />
                  </Form.Item>
                </>
              ),
            },
            {
              key: "extendedDescription",
              label: "Extended Description",
              children: (
                <>
                  <Form.Item
                    label="Business Description"
                    name="businessDescription"
                  >
                    <TextArea style={{ height: 120, resize: "none" }} />
                  </Form.Item>
                  <Form.Item label="Assumptions" name="assumptions">
                    <TextArea style={{ height: 120, resize: "none" }} />
                  </Form.Item>
                  <Form.Item label="Out of Scope" name="outOfScope">
                    <TextArea style={{ height: 120, resize: "none" }} />
                  </Form.Item>
                </>
              ),
            },
          ]}
        ></Tabs>
        <Flex vertical={false} style={{ marginLeft: 150 }}>
          <Form.Item name="openChain" valuePropName="checked" label={null}>
            <Checkbox>Open chain</Checkbox>
          </Form.Item>
          <Form.Item name="newTab" valuePropName="checked" label={null}>
            <Checkbox disabled={!isOpenChecked}>In new tab</Checkbox>
          </Form.Item>
        </Flex>
      </Form>
    </Modal>
  );
};
