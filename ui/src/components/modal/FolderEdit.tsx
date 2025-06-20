import React, { useEffect, useRef, useState } from "react";
import { Button, Flex, Form, Input, InputRef, Modal } from "antd";
import { useModalContext } from "../../ModalContextProvider.tsx";
import TextArea from "antd/lib/input/TextArea";
import Checkbox from "antd/lib/checkbox";

export type FolderEditMode = "create" | "update";

export type FolderEditProps = {
  mode?: FolderEditMode;
  name?: string;
  description?: string;
  onSubmit: (
    name: string,
    description: string,
    openFolder: boolean,
    newTab: boolean,
  ) => void;
};

export const FolderEdit: React.FC<FolderEditProps> = ({
  mode,
  name,
  description,
  onSubmit,
}) => {
  const { closeContainingModal } = useModalContext();
  const [confirmLoading, setConfirmLoading] = useState(false);
  const [isOpenChecked, setIsOpenChecked] = useState<boolean>(true);
  const nameInput = useRef<InputRef>(null);

  useEffect(() => {
    nameInput.current?.focus();
  }, [nameInput]);

  return (
    <Modal
      title={!mode || mode === "create" ? "New Folder" : "Edit Folder"}
      open={true}
      onCancel={closeContainingModal}
      footer={[
        <Button key="cancel" onClick={closeContainingModal}>
          Cancel
        </Button>,
        <Button
          key="submit"
          type="primary"
          form="folderEditForm"
          htmlType={"submit"}
          loading={confirmLoading}
        >
          {!mode || mode === "create" ? "Create" : "Apply"}
        </Button>,
      ]}
    >
      <Form<{
        name: string;
        description: string;
        openFolder: boolean;
        newTab: boolean;
      }>
        id="folderEditForm"
        labelCol={{ flex: "150px" }}
        wrapperCol={{ flex: "auto" }}
        initialValues={{ name, description }}
        labelWrap
        onFieldsChange={(changedFields) => {
          changedFields
            .filter((field) => field.name[0] === "openFolder")
            .forEach((field) => {
              setIsOpenChecked(field.value);
            });
        }}
        onFinish={async (values) => {
          setConfirmLoading(true);
          try {
            onSubmit?.(
              values.name,
              values.description,
              values.openFolder,
              values.newTab,
            );
            closeContainingModal();
          } finally {
            setConfirmLoading(false);
          }
        }}
      >
        <Form.Item name="name" label="Name" rules={[{ required: true, message: "Name is required" }]}>
          <Input ref={nameInput} />
        </Form.Item>
        <Form.Item name="Description" label="description">
          <TextArea style={{ height: 120, resize: "none" }} />
        </Form.Item>
        {mode === "create" ? (
          <Flex vertical={false} style={{ marginLeft: 150 }}>
            <Form.Item name="openFolder" valuePropName="checked" label={null}>
              <Checkbox>Open folder</Checkbox>
            </Form.Item>
            <Form.Item name="newTab" valuePropName="checked" label={null}>
              <Checkbox disabled={!isOpenChecked}>In new tab</Checkbox>
            </Form.Item>
          </Flex>
        ) : null}
      </Form>
    </Modal>
  );
};
