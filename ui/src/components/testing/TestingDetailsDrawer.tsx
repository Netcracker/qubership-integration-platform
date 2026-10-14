import React from "react";
import { Descriptions, Divider, Drawer, Typography } from "antd";
import type { DescriptionsItemType } from "antd/es/descriptions";
import { RowLink } from "../table/RowLink.tsx";
import { TestingAuditFields } from "../../api/apiTypes.ts";
import { EMPTY, formatAudit } from "./testingAudit.tsx";

/** The items of a single `Descriptions` block. */
export type TestingDetailsSection = DescriptionsItemType[];

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
    label: "Id",
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
 * a test case links its trigger, an endpoint mock its endpoint.
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
  return [
    {
      label: "Created",
      children: formatAudit(entity.createdBy, entity.createdAt),
    },
    {
      label: "Updated",
      children: formatAudit(entity.updatedBy, entity.updatedAt),
    },
  ];
}

export const TestingDetailsDrawer: React.FC<TestingDetailsDrawerProps> = ({
  title,
  sections,
  open,
  onClose,
}) => (
  <Drawer
    title={title}
    placement="right"
    size={380}
    open={open}
    onClose={onClose}
    destroyOnHidden
  >
    {sections.map((items, index) => (
      <React.Fragment key={index}>
        {index > 0 && <Divider style={{ margin: "12px 0" }} />}
        <Descriptions
          column={1}
          size="small"
          layout="vertical"
          colon={false}
          items={items}
        />
      </React.Fragment>
    ))}
  </Drawer>
);
