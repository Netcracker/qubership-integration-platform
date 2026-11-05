import {
  AccessControl,
  AccessControlProperty,
  AccessControlType,
} from "../../../api/apiTypes.ts";
import React, { ReactNode, useMemo } from "react";
import { Space, Tag, Typography } from "antd";
import { PLACEHOLDER } from "../../../misc/format-utils.ts";
import { hasUnsavedChanges } from "./access-control-utils.ts";
import { OverridableIcon } from "../../../icons/IconProvider.tsx";
import { AbacAttributesPopUp } from "./AbacAttributesPopUp.tsx";
import { useModalsContext } from "../../../Modals.tsx";
import { useNavigate } from "react-router";
import { DeploymentsCumulativeState } from "../../deployment_runtime_states/DeploymentsCumulativeState.tsx";
import {
  DetailsSidebar,
  DetailsSidebarSection,
  Placeholder,
  renderOptional,
} from "../../DetailsSidebar.tsx";

export type AccessControlSidebarProps = {
  value: AccessControl | null | undefined;
  open: boolean;
  onClose: () => void;
  deployedChainIds: Set<string>;
};

export const AccessControlSidebar: React.FC<AccessControlSidebarProps> = ({
  value,
  open,
  onClose,
  deployedChainIds,
}): ReactNode => {
  const { showModal } = useModalsContext();
  const navigate = useNavigate();

  const sections: DetailsSidebarSection[] = useMemo(
    () => [
      {
        key: "general-info",
        title: "general info",
        items: [
          ...(value && hasUnsavedChanges(value)
            ? [
                {
                  label: "",
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
            label: "Endpoint",
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
              <Placeholder />
            ),
          },
          {
            label: "Type",
            value: (() => {
              const properties = value?.properties as unknown as
                | AccessControlProperty
                | undefined;
              const { externalRoute, privateRoute } = properties ?? {};
              const privateRouteStr = privateRoute ? "Private" : "Internal";
              const externalRouteStr = externalRoute
                ? "External"
                : privateRouteStr;
              return externalRoute && privateRoute
                ? "External, Private"
                : externalRouteStr;
            })(),
          },
          {
            label: "Access Control Type",
            value: (() => {
              const properties = value?.properties as unknown as
                | AccessControlProperty
                | undefined;
              const val = properties?.accessControlType;
              return val === undefined || val === null ? (
                <Placeholder />
              ) : (
                String(val)
              );
            })(),
          },
          {
            label: "Roles",
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
                <Placeholder />
              );
            })(),
          },
          {
            label: "Attributes",
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
                <Placeholder />
              ),
          },
          {
            label: "Chain",
            value: value?.chainId ? (
              <a onClick={() => void navigate(`/chains/${value.chainId}`)}>
                {value.chainName}
              </a>
            ) : (
              <Placeholder />
            ),
          },
          {
            label: "Chain Status",
            value: value?.chainId ? (
              <DeploymentsCumulativeState
                chainId={value.chainId}
                isNotificationEnabled={!deployedChainIds.has(value.chainId)}
              />
            ) : (
              <Placeholder />
            ),
          },
          {
            label: "Chain ID",
            value: renderOptional(value?.chainId),
          },
        ],
      },
    ],
    [deployedChainIds, navigate, showModal, value],
  );

  return (
    <DetailsSidebar
      title={"Endpoint Details"}
      open={open}
      onClose={onClose}
      sections={sections}
    />
  );
};
