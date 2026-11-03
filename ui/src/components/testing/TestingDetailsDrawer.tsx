import React, { ReactNode, useMemo } from "react";
import { Col, Drawer, Listy, Row, Typography } from "antd";
import type { DescriptionsItemType } from "antd/es/descriptions";
import { RowLink } from "../table/RowLink.tsx";
import { TestingAuditFields } from "../../api/apiTypes.ts";
import { EMPTY, formatAudit } from "./testingAudit.tsx";

/** The items of a single `Descriptions` block. */
export type TestingDetailsSection = {
  title: React.Key;
  items: DescriptionsItemType[];
};

export type TestingDetailsDrawerProps = {
  title: string;
  /** One block per entry, divider-separated. Empty while the entity is absent. */
  sections: TestingDetailsSection[];
  open: boolean;
  onClose: () => void;
};

export const DetailsLink: React.FC<{
  to: string;
  children: React.ReactNode;
}> = ({ to, children }) => <RowLink to={to}>{children}</RowLink>;

export function idItem(id: string): DescriptionsItemType {
  return {
    label: "ID",
    children: (
      <Typography.Text copyable style={{ wordBreak: "break-all" }}>
        {id}
      </Typography.Text>
    ),
  };
}

/** Falls back to the id, since the name is resolved separately and may not have arrived. */
export function chainItem(
  chainId: string | null | undefined,
  chainName: string,
): DescriptionsItemType {
  return {
    label: "Chain",
    children: chainId ? (
      <DetailsLink to={`/chains/${chainId}`}>
        {chainName || chainId}
      </DetailsLink>
    ) : (
      EMPTY
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
): DescriptionsItemType {
  return {
    label,
    children:
      chainId && elementId ? (
        <DetailsLink to={`/chains/${chainId}/graph/${elementId}`}>
          {elementName || elementId}
        </DetailsLink>
      ) : (
        EMPTY
      ),
  };
}

export function auditSection(
  entity: TestingAuditFields,
): TestingDetailsSection {
  return {
    title: "audit",
    items: [
      {
        label: "Created",
        children: formatAudit(entity.createdBy, entity.createdAt),
      },
      {
        label: "Updated",
        children: formatAudit(entity.updatedBy, entity.updatedAt),
      },
    ],
  };
}

type SidebarItem = {
  group: React.Key;
  key: ReactNode;
  value: ReactNode;
};

export const TestingDetailsDrawer: React.FC<TestingDetailsDrawerProps> = ({
  title,
  sections,
  open,
  onClose,
}) => {
  const items = useMemo(
    () =>
      sections.flatMap((section) =>
        section.items.map((i) => ({
          group: section.title,
          key: i.label,
          value: i.children,
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
          key: (item) => item.group,
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
