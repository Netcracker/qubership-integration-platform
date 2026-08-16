import React from "react";
import { Descriptions, Divider, Drawer, Typography } from "antd";
import { useNavigate } from "react-router";
import { EndpointMock } from "../../api/apiTypes.ts";
import { formatTimestamp, PLACEHOLDER } from "../../misc/format-utils.ts";
import { EnabledTag } from "./TestingTags.tsx";
import { formatMockNumber } from "./endpointMocks.ts";

export type EndpointMockDetailsDrawerProps = {
  endpointMock: EndpointMock | null;
  chainName: string;
  elementName: string;
  open: boolean;
  onClose: () => void;
};

const EMPTY = <Typography.Text type="secondary">{PLACEHOLDER}</Typography.Text>;

function formatAudit(user: string | null, timestamp: string | null) {
  if (!timestamp) {
    return EMPTY;
  }
  return `${formatTimestamp(timestamp)}${user ? ` by ${user}` : ""}`;
}

export const EndpointMockDetailsDrawer: React.FC<
  EndpointMockDetailsDrawerProps
> = ({ endpointMock, chainName, elementName, open, onClose }) => {
  const navigate = useNavigate();
  const chainId = endpointMock?.endpointReference?.chainId;
  const elementId = endpointMock?.endpointReference?.elementId;
  const matchers = endpointMock?.requestMatchers ?? [];

  return (
    <Drawer
      title="Endpoint Mock Details"
      placement="right"
      size={380}
      open={open}
      onClose={onClose}
      destroyOnHidden
    >
      {!endpointMock ? null : (
        <>
          <Descriptions column={1} size="small" layout="vertical" colon={false}>
            <Descriptions.Item label="Id">
              <Typography.Text copyable style={{ wordBreak: "break-all" }}>
                {endpointMock.id}
              </Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="Name">
              {endpointMock.name || EMPTY}
            </Descriptions.Item>
            <Descriptions.Item label="Description">
              {endpointMock.description || EMPTY}
            </Descriptions.Item>
          </Descriptions>
          <Divider style={{ margin: "12px 0" }} />
          <Descriptions column={1} size="small" layout="vertical" colon={false}>
            <Descriptions.Item label="Chain">
              {chainId ? (
                <a onClick={() => void navigate(`/chains/${chainId}`)}>
                  {chainName || chainId}
                </a>
              ) : (
                EMPTY
              )}
            </Descriptions.Item>
            <Descriptions.Item label="Endpoint">
              {chainId && elementId ? (
                <a
                  onClick={() =>
                    void navigate(`/chains/${chainId}/graph/${elementId}`)
                  }
                >
                  {elementName || elementId}
                </a>
              ) : (
                EMPTY
              )}
            </Descriptions.Item>
          </Descriptions>
          <Divider style={{ margin: "12px 0" }} />
          <Descriptions column={1} size="small" layout="vertical" colon={false}>
            <Descriptions.Item label="Status">
              <EnabledTag enabled={endpointMock.enabled} />
            </Descriptions.Item>
            <Descriptions.Item label="Response status">
              {formatMockNumber(endpointMock.responseSettings?.status)}
            </Descriptions.Item>
            <Descriptions.Item label="Response delay, ms">
              {formatMockNumber(endpointMock.responseSettings?.delay)}
            </Descriptions.Item>
            <Descriptions.Item label="Rules">
              {matchers.length}
            </Descriptions.Item>
            <Descriptions.Item label="Active rules">
              {matchers.filter((matcher) => matcher.enabled).length}
            </Descriptions.Item>
          </Descriptions>
          <Divider style={{ margin: "12px 0" }} />
          <Descriptions column={1} size="small" layout="vertical" colon={false}>
            <Descriptions.Item label="Created">
              {formatAudit(endpointMock.createdBy, endpointMock.createdAt)}
            </Descriptions.Item>
            <Descriptions.Item label="Updated">
              {formatAudit(endpointMock.updatedBy, endpointMock.updatedAt)}
            </Descriptions.Item>
          </Descriptions>
        </>
      )}
    </Drawer>
  );
};
