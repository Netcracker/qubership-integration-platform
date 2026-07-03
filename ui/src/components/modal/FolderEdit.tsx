import React, { useEffect, useRef, useState } from "react";
import { Button, Checkbox, Flex, Form, Input, InputRef, Modal } from "antd";
import { useModalContext } from "../../ModalContextProvider.tsx";
import type { FieldData } from "../../types/antd.ts";
import { GROUP_SEGMENT_REGEX } from "../../misc/group-utils.ts";

const { TextArea } = Input;

export type FolderEditMode = "create" | "update";

export type FolderEditProps = {
  mode?: FolderEditMode;
  name?: string;
  onSubmit: (
    name: string,
    openFolder: boolean,
    newTab: boolean,
  ) => void | Promise<void>;
};

type FolderEditFormData = {
  name: string;
  openFolder: boolean;
  newTab: boolean;
};

export const FolderEdit: React.FC<FolderEditProps> = ({
  mode,
  name,
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
        <Button
          key="cancel"
          disabled={confirmLoading}
          onClick={closeContainingModal}
        >
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
      <Form<FolderEditFormData>
        id="folderEditForm"
        disabled={confirmLoading}
        labelCol={{ flex: "150px" }}
        wrapperCol={{ flex: "auto" }}
        initialValues={{
          name,
          ...((!mode || mode === "create") && {
            openFolder: true,
            newTab: false,
          }),
        }}
        labelWrap
        onFieldsChange={(changedFields: FieldData<FolderEditFormData>[]) => {
          changedFields
            .filter((field) => field.name[0] === "openFolder")
            .forEach((field) => {
              setIsOpenChecked(field.value as boolean);
            });
        }}
        onFinish={(values) => {
          setConfirmLoading(true);
          try {
            const result = onSubmit?.(
              values.name,
              values.openFolder,
              values.newTab,
            );
            if (result instanceof Promise) {
              result
                .then(() => {
                  closeContainingModal();
                  setConfirmLoading(false);
                })
                .catch(() => {
                  setConfirmLoading(false);
                });
            } else {
              closeContainingModal();
              setConfirmLoading(false);
            }
            // eslint-disable-next-line @typescript-eslint/no-unused-vars
          } catch (error) {
            setConfirmLoading(false);
          }
        }}
      >
        <Form.Item
          name="name"
          label="Name"
          rules={[
            { required: true, message: "Name is required" },
            {
              pattern: GROUP_SEGMENT_REGEX,
              message: 'Name must not contain any of: / : * ? " < > | , ; \\',
            },
          ]}
        >
          <Input ref={nameInput} />
        </Form.Item>
        <Form.Item name="description" label="Description">
          <TextArea className="fixed-textarea" />
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
