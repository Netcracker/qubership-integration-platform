/* eslint-disable react/prop-types -- TypeScript types define props */
import { Button, Checkbox, Form, Modal, Select } from "antd";
import React, { useState, useEffect } from "react";
import { useModalContext } from "../../../ModalContextProvider.tsx";
import {
  AccessControl as AccessControlData,
  AccessControlProperty,
  AccessControlUpdateRequest,
} from "../../../api/apiTypes.ts";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";

export type AddDeleteRolesPopUpProps = {
  record?: AccessControlData;
  records?: AccessControlData[];
  onSuccess?: () => void;
  mode?: "add" | "delete";
  /**
   * The two calls come from the table's own useAccessControl. Calling the hook here would give
   * the dialog a second instance of it, which fetches a page of rows nothing renders.
   */
  updateAccessControl: (
    requests: AccessControlUpdateRequest[],
  ) => Promise<void>;
  bulkDeployAccessControl: (chainIds: string[]) => Promise<void>;
};

/** The roles each element ends up with: the selected ones removed, or merged in. */
export const buildUpdateRequests = (
  records: AccessControlData[],
  selectedRoles: string[],
  isDeleteMode: boolean,
): AccessControlUpdateRequest[] =>
  records.map((rec) => {
    if (!rec.elementId) {
      throw new Error("Element ID is required");
    }

    const props = rec.properties as unknown as
      | AccessControlProperty
      | undefined;
    const existingRoles = Array.isArray(props?.roles) ? props.roles : [];

    return {
      elementId: rec.elementId,
      roles: isDeleteMode
        ? existingRoles.filter((role: string) => !selectedRoles.includes(role))
        : Array.from(new Set([...existingRoles, ...selectedRoles])),
    };
  });

/** One entry per chain, however many of its elements were edited. */
export const chainIdsOf = (records: AccessControlData[]): string[] =>
  Array.from(new Set(records.map((rec) => rec.chainId).filter(Boolean)));

export const AddDeleteRolesPopUp: React.FC<AddDeleteRolesPopUpProps> = ({
  record,
  records,
  onSuccess,
  mode = "add",
  updateAccessControl,
  bulkDeployAccessControl,
}) => {
  const recordsToProcess =
    records && records.length > 0 ? records : record ? [record] : [];
  const { closeContainingModal } = useModalContext();
  const notificationService = useNotificationService();
  const [form] = Form.useForm();
  const getAllUniqueRoles = (): string[] => {
    const allRoles = new Set<string>();
    recordsToProcess.forEach((rec) => {
      const roles = (
        rec?.properties as unknown as AccessControlProperty | undefined
      )?.roles;
      if (Array.isArray(roles)) {
        roles.forEach((role: string) => allRoles.add(role));
      }
    });
    return Array.from(allRoles);
  };

  const getInitialRoles = (): string[] => {
    if (recordsToProcess.length === 0) return [];
    if (recordsToProcess.length > 1) {
      return getAllUniqueRoles();
    }
    const props = recordsToProcess[0]?.properties as unknown as
      | AccessControlProperty
      | undefined;
    const roles = props?.roles;
    return Array.isArray(roles) ? [...roles] : [];
  };
  const isDeleteMode = mode === "delete";
  const [selectedRoles, setSelectedRoles] = useState<string[]>(
    isDeleteMode ? [] : getInitialRoles(),
  );
  const [isLoading, setIsLoading] = useState(false);

  useEffect(() => {
    if (isDeleteMode) {
      setSelectedRoles([]);
      form.setFieldsValue({ roles: [], redeploy: false });
    } else {
      const roles = getInitialRoles();
      setSelectedRoles(roles);
      form.setFieldsValue({ roles, redeploy: false });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- getInitialRoles depends on recordsToProcess
  }, [recordsToProcess, form, isDeleteMode]);

  /** Reports the failure instead of throwing: the roles are saved either way. */
  const deployChains = async (
    records: AccessControlData[],
  ): Promise<Error | undefined> => {
    try {
      await bulkDeployAccessControl(chainIdsOf(records));
      return undefined;
    } catch (err: unknown) {
      return err instanceof Error ? err : new Error(String(err));
    }
  };

  const handleSubmit = async () => {
    if (recordsToProcess.length === 0) {
      notificationService.info("Error", "Element ID is required");
      return;
    }

    if (isDeleteMode && selectedRoles.length === 0) {
      notificationService.info(
        "Error",
        "Please select at least one role to delete",
      );
      return;
    }

    try {
      setIsLoading(true);

      const formValues = form.getFieldsValue() as {
        roles?: string[];
        redeploy?: boolean;
      };

      await updateAccessControl(
        buildUpdateRequests(recordsToProcess, selectedRoles, isDeleteMode),
      );

      const deployError = formValues.redeploy
        ? await deployChains(recordsToProcess)
        : undefined;

      if (deployError) {
        // The roles are saved either way; a chain that failed keeps its unsaved changes.
        notificationService.requestFailed(
          isDeleteMode
            ? "Roles deleted, but some chains were not deployed"
            : "Roles updated, but some chains were not deployed",
          deployError,
        );
      } else {
        notificationService.info(
          "Success",
          isDeleteMode
            ? "Roles deleted successfully"
            : "Roles updated successfully",
        );
      }
      onSuccess?.();
      closeContainingModal();
    } catch (err: unknown) {
      notificationService.requestFailed(
        isDeleteMode ? "Failed to delete roles" : "Failed to update roles",
        err instanceof Error ? err : new Error(String(err)),
      );
    } finally {
      setIsLoading(false);
    }
  };

  const handleRolesChange = (roles: string[] | []) => {
    const rolesArray = Array.isArray(roles) ? roles : [];

    if (!isDeleteMode && rolesArray.length < selectedRoles.length) {
      form.setFieldsValue({ roles: selectedRoles });
      return;
    }

    setSelectedRoles(rolesArray);
    form.setFieldsValue({ roles: rolesArray });
  };

  const roleOptions = isDeleteMode
    ? getAllUniqueRoles().map((role) => ({ label: role, value: role }))
    : [];

  return (
    <Modal
      title={isDeleteMode ? "Remove Roles" : "Add Roles"}
      open={true}
      onCancel={closeContainingModal}
      styles={{ body: { paddingTop: "8px" } }}
      footer={[
        <Button key="cancel" onClick={closeContainingModal}>
          Cancel
        </Button>,
        <Button
          key="submit"
          type="primary"
          danger={isDeleteMode}
          onClick={() => void handleSubmit()}
          loading={isLoading}
        >
          {isDeleteMode ? "Delete" : "Save"}
        </Button>,
      ]}
      width={600}
    >
      <Form
        form={form}
        layout="vertical"
        labelCol={{ flex: "23px" }}
        wrapperCol={{ flex: "auto" }}
        labelWrap
        initialValues={{
          roles: isDeleteMode ? [] : getInitialRoles(),
          redeploy: false,
        }}
      >
        <Form.Item name="roles">
          <Select
            mode={isDeleteMode ? "multiple" : "tags"}
            allowClear
            style={{ width: "100%" }}
            placeholder={isDeleteMode ? "Remove Roles" : "Add Roles"}
            onChange={handleRolesChange}
            value={selectedRoles}
            options={roleOptions}
          />
        </Form.Item>
        <Form.Item name="redeploy" valuePropName="checked">
          <Checkbox>Redeploy selected chain to apply changes</Checkbox>
        </Form.Item>
      </Form>
    </Modal>
  );
};
