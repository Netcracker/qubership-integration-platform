import type { ServiceEntity } from "./ServicesTreeTable";
import { IntegrationSystemType } from "../../api/apiTypes";
import type { RequiredPermissions } from "../../permissions/types.ts";

/** Same rule as `isIntegrationSystem` in ServicesTreeTable (avoid runtime import cycle). */
function isIntegrationServiceRow(record: ServiceEntity): boolean {
  return "type" in record && record["type"] !== IntegrationSystemType.CONTEXT;
}

export interface ActionConfig<T = never> {
  key: string;
  label: string;
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
        onClick: onEdit,
        require: { service: ["update"] },
      },
      {
        key: "delete",
        label: "Delete",
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
        onClick: onAddSpecificationGroup,
        require: { specificationGroup: ["import"] },
      });
    }
    if (isExpandAvailable(record)) {
      actions.push(
        {
          key: "expandAll",
          label: "Expand All",
          onClick: onExpandAll,
        },
        {
          key: "collapseAll",
          label: "Collapse All",
          onClick: onCollapseAll,
        },
      );
    }

    if (onExportSelected) {
      actions.push({
        key: "export",
        label: "Export",
        onClick: (rec) => onExportSelected([rec]),
        require: { service: ["export"] },
      });
    }
    return actions;
  };
}
