import React from "react";
import type { ServiceEntity } from "./ServicesTreeTable";
import { IntegrationSystemType } from "../../api/apiTypes";
import { OverridableIcon } from "../../icons/IconProvider.tsx";
import type { RequiredPermissions } from "../../permissions/types.ts";

/** Same rule as `isIntegrationSystem` in ServicesTreeTable (avoid runtime import cycle). */
function isIntegrationServiceRow(record: ServiceEntity): boolean {
  return "type" in record && record["type"] !== IntegrationSystemType.CONTEXT;
}

export interface ActionConfig<T = never> {
  key: string;
  label: string;
  icon?: React.ReactNode;
  onClick: (record: T) => void;
  confirm?: {
    title: string;
    okText?: string;
    cancelText?: string;
  };
  visible?: (record: T) => boolean;
  require?: RequiredPermissions;
}

export function getServiceActions({
  onEdit,
  onDelete,
  onExpandAll,
  onCollapseAll,
  isRootEntity,
  isExpandAvailable,
  onExportSelected,
  onAddSpecificationGroup,
}: {
  onEdit: (record: ServiceEntity) => void;
  onDelete: (record: ServiceEntity) => void;
  onExpandAll: (record: ServiceEntity) => void;
  onCollapseAll: (record: ServiceEntity) => void;
  isRootEntity: (record: ServiceEntity) => boolean;
  isExpandAvailable: (record: ServiceEntity) => boolean;
  onExportSelected?: (selected: ServiceEntity[]) => void;
  onAddSpecificationGroup?: (record: ServiceEntity) => void;
}) {
  return (record: ServiceEntity): ActionConfig<ServiceEntity>[] => {
    if (!isRootEntity(record)) return [];
    const actions: ActionConfig<ServiceEntity>[] = [
      {
        key: "edit",
        label: "Edit",
        icon: <OverridableIcon name="edit" />,
        onClick: onEdit,
        require: { service: ["update"] },
      },
      {
        key: "delete",
        label: "Delete",
        icon: <OverridableIcon name="delete" />,
        onClick: onDelete,
        confirm: {
          title: "Are you sure you want to delete this service?",
          okText: "Delete",
          cancelText: "Cancel",
        },
        require: { service: ["delete"] },
      },
    ];
    if (onAddSpecificationGroup && isIntegrationServiceRow(record)) {
      actions.push({
        key: "addSpecificationGroup",
        label: "Add Specification Group",
        icon: <OverridableIcon name="plus" />,
        onClick: onAddSpecificationGroup,
        require: { specificationGroup: ["import"] },
      });
    }
    if (isExpandAvailable(record)) {
      actions.push(
        {
          key: "expandAll",
          label: "Expand All",
          icon: <OverridableIcon name="columnHeight" />,
          onClick: onExpandAll,
        },
        {
          key: "collapseAll",
          label: "Collapse All",
          icon: <OverridableIcon name="verticalAlignMiddle" />,
          onClick: onCollapseAll,
        },
      );
    }

    if (onExportSelected) {
      actions.push({
        key: "export",
        label: "Export",
        icon: <OverridableIcon name="cloudDownload" />,
        onClick: (rec) => onExportSelected([rec]),
        require: { service: ["export"] },
      });
    }
    return actions;
  };
}
