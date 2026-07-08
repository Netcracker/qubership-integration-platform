import type { ColumnType } from "antd/lib/table";
import type { BaseEntity } from "../../api/apiTypes.ts";
import { formatOptional, formatTimestamp } from "../../misc/format-utils.ts";

type AuditableEntity = Pick<
  BaseEntity,
  "createdWhen" | "createdBy" | "modifiedWhen" | "modifiedBy"
>;

// Created/Modified audit columns shared by the service list grids. Hidden by
// default; users can toggle them on via the column-settings button.
export function createAuditColumns<
  T extends AuditableEntity,
>(): ColumnType<T>[] {
  return [
    {
      title: "Created At",
      dataIndex: "createdWhen",
      key: "createdWhen",
      width: 160,
      render: (_: unknown, system: T) => formatTimestamp(system.createdWhen),
      hidden: true,
    },
    {
      title: "Created By",
      dataIndex: "createdBy",
      key: "createdBy",
      width: 130,
      render: (_: unknown, system: T) =>
        formatOptional(system.createdBy?.username),
      hidden: true,
    },
    {
      title: "Modified At",
      dataIndex: "modifiedWhen",
      key: "modifiedWhen",
      width: 160,
      render: (_: unknown, system: T) => formatTimestamp(system.modifiedWhen),
      hidden: true,
    },
    {
      title: "Modified By",
      dataIndex: "modifiedBy",
      key: "modifiedBy",
      width: 130,
      render: (_: unknown, system: T) =>
        formatOptional(system.modifiedBy?.username),
      hidden: true,
    },
  ];
}
