import { Form, Input, Select } from "antd";
import React, { useContext, useEffect, useState } from "react";
import { Chain } from "../api/apiTypes.ts";
import { ChainContext } from "./ChainPage.tsx";
import {
  ChainExtensionProperties,
  loadChainExtensionPropertiesToForm,
  readChainExtensionPropertiesFromForm,
} from "./ChainExtensionProperties.tsx";
import styles from "./Chain.module.css";
import { api } from "../api/api.ts";
import { useNotificationService } from "../hooks/useNotificationService.tsx";
import { isVsCode } from "../api/rest/vscodeExtensionApi.ts";
import { useBlocker } from "react-router-dom";
import { useModalsContext } from "../Modals.tsx";
import { UnsavedChangesModal } from "../components/modal/UnsavedChangesModal.tsx";
import { useRegisterChainHeaderActions } from "./ChainHeaderActionsContext.tsx";
import { ApplyFormButton } from "../components/ApplyFormButton.tsx";
import {
  GROUP_SEGMENT_REGEX,
  parseGroupSegments,
} from "../misc/group-utils.ts";
import { usePermissions } from "../permissions/usePermissions.tsx";
import { hasPermissions } from "../permissions/funcs.ts";
import { Require } from "../permissions/Require.tsx";
import {
  decodeStoredText,
  normalizeStoredText,
} from "../misc/chainMetadataSanitizer.ts";

const { TextArea } = Input;
const { useForm } = Form;

export type FormData = {
  name: string;
  labels: string[];
  group?: string;
  description: string;
  businessDescription: string;
  assumptions: string;
  outOfScope: string;
  domain?: string;
  deployAction?: string;
};

export const ChainProperties: React.FC = () => {
  const [isUpdating, setIsUpdating] = useState<boolean>(false);
  const [hasChanges, setHasChanges] = useState<boolean>(false);
  const blocker = useBlocker(hasChanges);
  const { showModal } = useModalsContext();
  const notificationService = useNotificationService();
  const [form] = useForm();
  const chainContext = useContext(ChainContext);
  const permissions = usePermissions();
  const [disabled, setDisabled] = useState<boolean>(false);

  useEffect(() => {
    setDisabled(!hasPermissions(permissions, { chain: ["update"] }));
  }, [permissions]);

  const moveChain = async (chainId: string, folder?: string) => {
    try {
      await api.moveChain(chainId, folder);
      return true;
    } catch (error) {
      notificationService.requestFailed("Failed to move chain", error);
      return false;
    }
  };

  useEffect(() => {
    if (blocker.state === "blocked") {
      showModal({
        component: (
          <UnsavedChangesModal
            onYes={() => {
              void (async () => {
                const values = (await form.validateFields()) as FormData;
                const isSaved = await handleFinish(values);
                if (isSaved) {
                  blocker.proceed();
                }
              })();
            }}
            onNo={() => {
              setHasChanges(false);
              blocker.proceed();
            }}
            onCancelQuestion={() => {
              blocker.reset();
            }}
          />
        ),
      });
    }
  }, [blocker, form, showModal]);

  useEffect(() => {
    if (chainContext?.chain) {
      const formData: FormData = {
        name: chainContext.chain.name ?? "",
        group: isVsCode
          ? chainContext.chain?.navigationPath
              .map(([, value]) => value)
              .join("/")
          : undefined,
        labels: chainContext.chain.labels?.map((label) =>
          decodeStoredText(label.name),
        ) ?? [],
        description: decodeStoredText(chainContext.chain.description),
        businessDescription: chainContext.chain.businessDescription ?? "",
        assumptions: chainContext.chain.assumptions ?? "",
        outOfScope: chainContext.chain.outOfScope ?? "",
      };
      loadChainExtensionPropertiesToForm(chainContext, formData);
      form.setFieldsValue(formData);
    }
  }, []);

  const handleFinish = async (values: FormData): Promise<boolean> => {
    if (!chainContext?.chain) return false;

    const changes: Partial<Chain> = {
      name: values.name,
      labels: values.labels?.map((name) => ({
        name: normalizeStoredText(name) ?? "",
        technical: false,
      })),
      description: normalizeStoredText(values.description),
      businessDescription: values.businessDescription,
      assumptions: values.assumptions,
      outOfScope: values.outOfScope,
    };

    // The group is stored as the chain's location on disk, so it is saved by the move alone.
    if (isVsCode) {
      const group = parseGroupSegments(values.group ?? "").join("/");
      const moved = await moveChain(String(chainContext.chain.id), group);
      if (!moved) {
        return false;
      }
    }

    readChainExtensionPropertiesFromForm(values, changes);

    setIsUpdating(true);
    try {
      await chainContext.update(changes);
      setHasChanges(false);
      return true;
    } finally {
      setIsUpdating(false);
    }
  };

  useRegisterChainHeaderActions(
    <Require permissions={{ chain: ["update"] }}>
      <ApplyFormButton
        formId="chain-properties-form"
        loading={isUpdating}
        disabled={!hasChanges}
      />
    </Require>,
    [isUpdating, hasChanges],
  );

  return (
    <div className={styles.pageContainer}>
      <div className={styles.formContent}>
        <Form<FormData>
          id="chain-properties-form"
          form={form}
          disabled={isUpdating || disabled}
          labelCol={{ flex: "150px" }}
          wrapperCol={{ flex: "auto" }}
          labelWrap
          onChange={() => setHasChanges(true)}
          onFinish={(values) => {
            void handleFinish(values);
          }}
        >
          <Form.Item label="Name" name="name" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          {isVsCode && (
            <Form.Item
              label="Group"
              name="group"
              rules={[
                {
                  validator: (_, value: string) => {
                    const invalid = parseGroupSegments(value ?? "").some(
                      (segment) => !GROUP_SEGMENT_REGEX.test(segment),
                    );
                    return invalid
                      ? Promise.reject(
                          new Error(
                            'Group segments must not contain: / : * ? " < > | , ; \\',
                          ),
                        )
                      : Promise.resolve();
                  },
                },
              ]}
            >
              <Input />
            </Form.Item>
          )}
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
            <TextArea className="fixed-textarea" disabled={disabled} />
          </Form.Item>
          <Form.Item label="Business Description" name="businessDescription">
            <TextArea className="fixed-textarea" disabled={disabled} />
          </Form.Item>
          <Form.Item label="Assumptions" name="assumptions">
            <TextArea className="fixed-textarea" disabled={disabled} />
          </Form.Item>
          <Form.Item label="Out of Scope" name="outOfScope">
            <TextArea className="fixed-textarea" disabled={disabled} />
          </Form.Item>
          <ChainExtensionProperties onChange={() => setHasChanges(true)} />
        </Form>
      </div>
    </div>
  );
};
