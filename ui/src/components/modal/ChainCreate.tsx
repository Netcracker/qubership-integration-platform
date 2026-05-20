import {
  Button,
  Flex,
  Form,
  Input,
  InputRef,
  Modal,
  Select,
  Spin,
  Tabs,
} from "antd";
import React, { useEffect, useRef, useState } from "react";
import { useModalContext } from "../../ModalContextProvider.tsx";
import { ChainCreationRequest, EntityLabel } from "../../api/apiTypes.ts";
import TextArea from "antd/lib/input/TextArea";
import Checkbox from "antd/lib/checkbox";
import { FieldData } from "rc-field-form/lib/interface";
import { api } from "../../api/api.ts";
import { useNotificationService } from "../../hooks/useNotificationService.tsx";

export type ChainMetadataUpdate = {
  name: string;
  labels: EntityLabel[];
  description?: string;
  businessDescription?: string;
  assumptions?: string;
  outOfScope?: string;
};

type ChainCreateCreateProps = {
  variant?: "create";
  onSubmit: (
    request: ChainCreationRequest,
    openChain: boolean,
    newTab: boolean,
  ) => void | Promise<void>;
};

type ChainCreateEditProps = {
  variant: "editChainMetaData";
  chainId: string;
  onUpdateMetadata: (
    chainId: string,
    update: ChainMetadataUpdate,
    openChain: boolean,
    newTab: boolean,
  ) => void | Promise<void>;
};

export type ChainCreateProps = ChainCreateCreateProps | ChainCreateEditProps;

type FormData = Omit<ChainCreationRequest, "labels"> & {
  labels: string[];
  openChain: boolean;
  newTab: boolean;
};

export const ChainCreate: React.FC<ChainCreateProps> = (props) => {
  const isEdit = props.variant === "editChainMetaData";
  const editChainId = isEdit ? props.chainId : undefined;
  const [form] = Form.useForm<FormData>();
  const [confirmLoading, setConfirmLoading] = useState(false);
  const [isOpenChecked, setIsOpenChecked] = useState<boolean>(!isEdit);
  const [dataReady, setDataReady] = useState(!isEdit);
  const { closeContainingModal } = useModalContext();
  const nameInput = useRef<InputRef>(null);
  const notificationService = useNotificationService();

  const formId = isEdit ? "editChainMetadataForm" : "createChainForm";
  const modalTitle = isEdit ? "Edit chain" : "New Chain";

  useEffect(() => {
    if (!isEdit || !editChainId) {
      return;
    }

    let cancelled = false;
    void (async () => {
      try {
        const chain = await api.getChain(editChainId);
        if (cancelled) {
          return;
        }
        form.setFieldsValue({
          name: chain.name ?? "",
          labels: chain.labels?.map((l) => l.name) ?? [],
          description: chain.description ?? "",
          businessDescription: chain.businessDescription ?? "",
          assumptions: chain.assumptions ?? "",
          outOfScope: chain.outOfScope ?? "",
          openChain: false,
          newTab: false,
        });
        setIsOpenChecked(false);
        setDataReady(true);
      } catch (error) {
        notificationService.requestFailed("Failed to load chain", error);
        closeContainingModal();
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [isEdit, editChainId, form, notificationService, closeContainingModal]);

  useEffect(() => {
    if (!dataReady) {
      return;
    }
    nameInput.current?.focus();
  }, [dataReady]);

  const runSubmitResult = (result: void | Promise<void>) => {
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
  };

  return (
    <Modal
      title={modalTitle}
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
          form={formId}
          htmlType={"submit"}
          loading={confirmLoading}
          disabled={dataReady === false}
        >
          Submit
        </Button>,
      ]}
    >
      {dataReady ? (
        <Form<FormData>
          id={formId}
          form={form}
          disabled={confirmLoading}
          labelCol={{ flex: "150px" }}
          wrapperCol={{ flex: "auto" }}
          labelWrap
          initialValues={
            isEdit ? undefined : { openChain: true, newTab: false }
          }
          onFieldsChange={(changedFields: FieldData<FormData>[]) => {
            changedFields
              .filter((field) => field.name?.[0] === "openChain")
              .forEach((field) => {
                setIsOpenChecked(field.value as boolean);
              });
          }}
          onFinish={(values) => {
            setConfirmLoading(true);
            try {
              const labels: EntityLabel[] =
                values.labels?.map((s) => ({
                  name: s,
                  technical: false,
                })) ?? [];
              if (props.variant === "editChainMetaData") {
                const result = props.onUpdateMetadata(
                  props.chainId,
                  {
                    name: values.name,
                    labels,
                    description: values.description,
                    businessDescription: values.businessDescription,
                    assumptions: values.assumptions,
                    outOfScope: values.outOfScope,
                  },
                  values.openChain,
                  values.newTab,
                );
                runSubmitResult(result);
              } else {
                const result = props.onSubmit(
                  {
                    name: values.name,
                    description: values.description,
                    businessDescription: values.businessDescription,
                    assumptions: values.assumptions,
                    outOfScope: values.outOfScope,
                    labels,
                  },
                  values.openChain,
                  values.newTab,
                );
                runSubmitResult(result);
              }
            } catch {
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
                        classNames={{ popup: { root: "not-displayed" } }}
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
          />
          <Flex vertical={false} style={{ marginLeft: 150 }}>
            <Form.Item name="openChain" valuePropName="checked" label={null}>
              <Checkbox>Open chain</Checkbox>
            </Form.Item>
            <Form.Item name="newTab" valuePropName="checked" label={null}>
              <Checkbox disabled={!isOpenChecked}>In new tab</Checkbox>
            </Form.Item>
          </Flex>
        </Form>
      ) : (
        <div style={{ padding: 48, textAlign: "center" }}>
          <Spin />
        </div>
      )}
    </Modal>
  );
};
