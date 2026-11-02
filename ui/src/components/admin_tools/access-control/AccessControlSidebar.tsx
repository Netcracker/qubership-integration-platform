import {
  AccessControl,
  AccessControlProperty,
  AccessControlType,
} from "../../../api/apiTypes.ts";
import React, { ReactNode, useMemo } from "react";
import { Col, Drawer, Listy, Row, Space, Tag, Typography } from "antd";
import { formatOptional, PLACEHOLDER } from "../../../misc/format-utils.ts";
import { hasUnsavedChanges } from "./access-control-utils.ts";
import { OverridableIcon } from "../../../icons/IconProvider.tsx";
import { AbacAttributesPopUp } from "./AbacAttributesPopUp.tsx";
import { useModalsContext } from "../../../Modals.tsx";
import { useNavigate } from "react-router";
import { DeploymentsCumulativeState } from "../../deployment_runtime_states/DeploymentsCumulativeState.tsx";

export type AccessControlSidebarProps = {
  value: AccessControl | null | undefined;
  open: boolean;
  onClose: () => void;
  deployedChainIds: Set<string>;
};

type SidebarItem = {
  key: ReactNode;
  value: ReactNode;
};

export const AccessControlSidebar: React.FC<AccessControlSidebarProps> = ({
  value,
  open,
  onClose,
  deployedChainIds,
}): ReactNode => {
  const { showModal } = useModalsContext();
  const navigate = useNavigate();

  const items = useMemo(
    () => [
      ...(value && hasUnsavedChanges(value)
        ? [
            {
              key: "",
              value: (
                <Tag color="blue" style={{ fontSize: 13 }}>
                  <Space>
                    <OverridableIcon name="warning" />
                    Unsaved Changes
                  </Space>
                </Tag>
              ),
            },
          ]
        : []),
      {
        key: "Endpoint",
        value: value?.chainId ? (
          <a
            href={`/chains/${value.chainId}/graph/${value.elementId}`}
            target="_blank"
            rel="noopener noreferrer"
          >
            {(value.properties as unknown as AccessControlProperty)
              ?.contextPath || PLACEHOLDER}
          </a>
        ) : (
          PLACEHOLDER
        ),
      },
      {
        key: "Type",
        value: (() => {
          const properties = value?.properties as unknown as
            | AccessControlProperty
            | undefined;
          const { externalRoute, privateRoute } = properties ?? {};
          return externalRoute && privateRoute
            ? "External, Private"
            : externalRoute
              ? "External"
              : privateRoute
                ? "Private"
                : "Internal";
        })(),
      },
      {
        key: "Access Control Type",
        value: (() => {
          const properties = value?.properties as unknown as
            | AccessControlProperty
            | undefined;
          const val = properties?.accessControlType;
          return typeof val === "string"
            ? val
            : val != null
              ? String(val)
              : PLACEHOLDER;
        })(),
      },
      {
        key: "Roles",
        value: (() => {
          const properties = value?.properties as unknown as
            | AccessControlProperty
            | undefined;
          const roles = properties?.roles;
          return roles && Array.isArray(roles) && roles.length > 0 ? (
            <span>
              {roles.map((role, idx) => (
                <Tag
                  key={`${role}-${idx}`}
                  color="blue"
                  style={{ marginBottom: 4 }}
                >
                  {role}
                </Tag>
              ))}
            </span>
          ) : (
            PLACEHOLDER
          );
        })(),
      },
      {
        key: "Attributes",
        value:
          (value?.properties as unknown as AccessControlProperty)
            ?.accessControlType === AccessControlType.ABAC ? (
            <Typography.Link
              onClick={() => {
                if (value) {
                  showModal({
                    component: <AbacAttributesPopUp record={value} />,
                  });
                }
              }}
            >
              Details
            </Typography.Link>
          ) : (
            PLACEHOLDER
          ),
      },
      {
        key: "Chain",
        value: value?.chainId ? (
          <a onClick={() => void navigate(`/chains/${value.chainId}`)}>
            {value.chainName}
          </a>
        ) : (
          PLACEHOLDER
        ),
      },
      {
        key: "Chain Status",
        value: value?.chainId ? (
          <DeploymentsCumulativeState
            chainId={value.chainId}
            isNotificationEnabled={!deployedChainIds.has(value.chainId)}
          />
        ) : (
          PLACEHOLDER
        ),
      },
      {
        key: "Chain ID",
        value: formatOptional(value?.chainId),
      },
    ],
    [deployedChainIds, navigate, showModal, value],
  );

  return (
    <Drawer
      title="Endpoint Details"
      placement="right"
      size={350}
      open={open}
      onClose={onClose}
      destroyOnHidden
      closable={{ placement: "end" }}
      getContainer={".page-content"}
      rootStyle={{ position: "absolute" }}
      classNames={{ body: "details-sidebar-body" }}
    >
      <Listy<SidebarItem>
        classNames={{
          groupHeader: "details-sidebar-properties-group",
        }}
        style={{ height: "100%" }}
        items={items}
        rowKey={"key"}
        group={{
          key: () => "general info",
          title: (groupKey) => `${groupKey}`,
        }}
        itemRender={(item) => (
          <Row wrap={false} gutter={4}>
            <Col span={8}>{item.key}</Col>
            <Col>{item.value}</Col>
          </Row>
        )}
        sticky
      />
    </Drawer>
  );
};
