import React, { ReactNode, useMemo } from "react";
import { Col, Drawer, Flex, Listy, Row, Typography } from "antd";
import {
  formatOptional,
  formatTimestamp,
  PLACEHOLDER,
} from "../misc/format-utils.ts";
import { RowLink } from "./table/RowLink.tsx";
import { TestingAuditFields } from "../api/apiTypes.ts";

export const Placeholder: React.FC = (): ReactNode => (
  <Typography.Text type={"secondary"}>{PLACEHOLDER}</Typography.Text>
);

export function renderOptional<T>(
  value: T | undefined | null,
  renderer: (v: T) => ReactNode = (v: T) => String(v),
  placeholder: ReactNode = <Placeholder />,
) {
  return value === undefined || value === null ? placeholder : renderer(value);
}

export type DetailsSidebarSectionItem = {
  label: ReactNode;
  value: ReactNode;
  showLabel?: boolean;
};

export type DetailsSidebarSection = {
  key: React.Key;
  title: ReactNode;
  items: DetailsSidebarSectionItem[];
};

export type DetailsSidebarProps = {
  title: ReactNode;
  sections: DetailsSidebarSection[];
  open: boolean;
  onClose: () => void;
};

export const IdText: React.FC<{ value?: string }> = ({ value }) =>
  renderOptional(value, (v) => (
    <Typography.Text copyable style={{ wordBreak: "break-all" }}>
      {v}
    </Typography.Text>
  ));

export const AuthorDetails: React.FC<{
  timestamp?: number | string | null;
  username?: string | null;
}> = ({ timestamp, username }) => {
  return timestamp ? (
    <Flex vertical>
      <Typography.Text>{formatTimestamp(timestamp)}</Typography.Text>
      <Typography.Text type={"secondary"}>
        {formatOptional(username)}
      </Typography.Text>
    </Flex>
  ) : (
    <Placeholder />
  );
};

export const DetailsLink: React.FC<{
  to: string;
  children: React.ReactNode;
}> = ({ to, children }) => <RowLink to={to}>{children}</RowLink>;

export function idItem(id?: string): DetailsSidebarSectionItem {
  return {
    label: "ID",
    value: renderOptional(id, (v) => <IdText value={v} />),
  };
}

/** Falls back to the id, since the name is resolved separately and may not have arrived. */
export function chainItem(
  chainId: string | null | undefined,
  chainName: string,
): DetailsSidebarSectionItem {
  return {
    label: "Chain",
    value: chainId ? (
      <DetailsLink to={`/chains/${chainId}`}>
        {chainName || chainId}
      </DetailsLink>
    ) : (
      <Placeholder />
    ),
  };
}

/**
 * The element the entity is attached to, falling back to the id as `chainItem`
 * does. The label is a parameter because the two entities name it differently:
 * a test case links it's trigger, an endpoint mock its endpoint.
 */
export function elementItem(
  label: string,
  chainId: string | null | undefined,
  elementId: string | null | undefined,
  elementName: string,
): DetailsSidebarSectionItem {
  return {
    label,
    value:
      chainId && elementId ? (
        <DetailsLink to={`/chains/${chainId}/graph/${elementId}`}>
          {elementName || elementId}
        </DetailsLink>
      ) : (
        <Placeholder />
      ),
  };
}

export function auditSection(
  entity: TestingAuditFields | null | undefined,
): DetailsSidebarSection {
  return {
    key: "audit",
    title: "audit",
    items: [
      {
        label: "Created",
        value: (
          <AuthorDetails
            timestamp={entity?.createdAt}
            username={entity?.createdBy}
          />
        ),
      },
      {
        label: "Modified",
        value: (
          <AuthorDetails
            timestamp={entity?.updatedAt}
            username={entity?.updatedBy}
          />
        ),
      },
    ],
  };
}

type SidebarItem = {
  section: React.Key;
  key: React.Key;
} & DetailsSidebarSectionItem;

export const DetailsSidebar: React.FC<DetailsSidebarProps> = ({
  title,
  sections,
  open,
  onClose,
}) => {
  const items = useMemo(
    () =>
      sections.flatMap((section) =>
        section.items.map((item, index) => ({
          ...item,
          section: section.key,
          key: index,
        })),
      ),
    [sections],
  );

  return (
    <Drawer
      title={title}
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
          key: (item) => item.section,
          title: (groupKey) =>
            sections.find((section) => section.key === groupKey)?.title ?? "",
        }}
        itemRender={(item) =>
          item.showLabel === undefined || item.showLabel ? (
            <Row wrap={false} gutter={4}>
              <Col span={8}>
                {typeof item.label === "string" ? (
                  <Typography.Text type={"secondary"}>
                    {item.label}
                  </Typography.Text>
                ) : (
                  item.label
                )}
              </Col>

              <Col>{item.value}</Col>
            </Row>
          ) : (
            item.value
          )
        }
        sticky
      />
    </Drawer>
  );
};
