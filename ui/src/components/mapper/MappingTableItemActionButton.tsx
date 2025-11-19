import {
  getXmlNamespaces,
  isAttributeItem,
  isBodyGroup,
  isConstantGroup,
  isConstantItem,
  isHeaderGroup,
  isPropertyGroup,
  MappingTableItem,
} from "./MappingTableView.tsx";
import React, { useCallback, useContext, useEffect, useState } from "react";
import { ItemType } from "antd/es/menu/interface";
import { App, Button, Dropdown } from "antd";
import { DataTypes } from "../../mapper/util/types.ts";
import { ChainContext } from "../../pages/ChainPage.tsx";
import { LoadSchemaDialog } from "./LoadSchemaDialog.tsx";
import { XmlNamespace } from "../../mapper/model/metadata.ts";
import { NamespacesEditDialog } from "./NamespacesEditDialog.tsx";
import { useModalsContext } from "../../Modals.tsx";
import { DataType, SchemaKind } from "../../mapper/model/model.ts";
import { Icon } from "../../IconProvider.tsx";

export type MappingTableItemActionButtonProps = {
  elementId: string;
  item: MappingTableItem;
  readonly: boolean;
  enableEdit: boolean;
  enableXmlNamespaces: boolean;
  schemaKind: SchemaKind;
  onEdit?: () => void;
  onLoad?: (type: DataType) => void;
  onExport?: () => void;
  onUpdateXmlNamespaces?: (namespaces: XmlNamespace[]) => void;
  onAdd?: () => void;
  onClear?: () => void;
  onDelete?: () => void;
};

export const MappingTableItemActionButton: React.FC<
  MappingTableItemActionButtonProps
> = ({
  elementId,
  item,
  readonly,
  enableEdit,
  enableXmlNamespaces,
  schemaKind,
  onEdit,
  onLoad,
  onExport,
  onUpdateXmlNamespaces,
  onAdd,
  onClear,
  onDelete,
}) => {
  const chainContext = useContext(ChainContext);
  const { modal } = App.useApp();
  const { showModal } = useModalsContext();
  const [items, setItems] = useState<ItemType[]>([]);

  const buildItems = useCallback(() => {
    const isGroup =
      isConstantGroup(item) ||
      isHeaderGroup(item) ||
      isPropertyGroup(item) ||
      isBodyGroup(item);
    const isComplexObject =
      (isAttributeItem(item) &&
        DataTypes.isComplexType(item.resolvedType, item.typeDefinitions)) ||
      (isBodyGroup(item) &&
        item.type &&
        DataTypes.isComplexType(item.type, []));
    const canLoad =
      !readonly &&
      (isBodyGroup(item) ||
        (isAttributeItem(item) &&
          DataTypes.isComplexType(item.resolvedType, item.typeDefinitions)));
    const items: ItemType[] = [];
    if (canLoad) {
      items.push({
        key: "load",
        label: "Load",
        icon: <Icon name="cloudUpload" />,
        onClick: () => {
          showModal({
            component: (
              <ChainContext.Provider value={chainContext}>
                <LoadSchemaDialog
                  elementId={elementId}
                  schemaKind={schemaKind}
                  onSubmit={(type) => onLoad?.(type)}
                />
              </ChainContext.Provider>
            ),
          });
        },
      });
    }
    if (!readonly && !isGroup && enableEdit) {
      items.push({
        key: "edit",
        label: "Edit",
        icon: <Icon name="edit" />,
        onClick: () => {
          onEdit?.();
        },
      });
    }
    if (isComplexObject) {
      items.push({
        key: "export",
        label: "Export",
        icon: <Icon name="cloudDownload" />,
        onClick: () => {
          onExport?.();
        },
      });
    }
    if (
      isComplexObject &&
      !isBodyGroup(item) &&
      enableXmlNamespaces &&
      !readonly
    ) {
      items.push({
        key: "namespaces",
        label: "Namespaces",
        icon: <span style={{ fontSize: "9px" }}>{"</>"}</span>,
        onClick: () => {
          showModal({
            component: (
              <NamespacesEditDialog
                namespaces={getXmlNamespaces(
                  item.resolvedType,
                  item.typeDefinitions,
                )}
                onSubmit={(namespaces) => {
                  onUpdateXmlNamespaces?.(namespaces);
                }}
              />
            ),
          });
        },
      });
    }
    if ((isGroup || isComplexObject) && !readonly) {
      items.push(
        {
          key: "add",
          label: "Add",
          icon: <Icon name="plusCircle" />,
          onClick: () => {
            onAdd?.();
          },
        },
        {
          key: "clear",
          label: "Clear",
          icon: <Icon name="clear" />,
          onClick: () => {
            modal.confirm({
              title: "Clear tree",
              content: "Are you sure you want to clear the whole tree?",
              onOk: () => onClear?.(),
            });
          },
        },
      );
    }
    if (!readonly && !isGroup) {
      items.push({
        key: "delete",
        label: "Delete",
        icon: <Icon name="delete" />,
        onClick: () => {
          const title = `Delete ${isConstantItem(item) ? "constant" : "attribute"}`;
          const content = `Are you sure you want to delete this ${isConstantItem(item) ? "constant" : "attribute"} and all related connections?`;
          modal.confirm({
            title,
            content,
            onOk: () => {
              onDelete?.();
            },
          });
        },
      });
    }
    return items;
  }, [
    chainContext,
    elementId,
    enableEdit,
    enableXmlNamespaces,
    item,
    modal,
    onAdd,
    onClear,
    onDelete,
    onEdit,
    onExport,
    onLoad,
    onUpdateXmlNamespaces,
    readonly,
    schemaKind,
    showModal,
  ]);

  useEffect(() => {
    setItems(buildItems());
  }, [buildItems]);

  return (
    <Dropdown menu={{ items }} trigger={["click"]} placement="bottomRight">
      <Button size="small" type="text" icon={<Icon name="more" />} />
    </Dropdown>
  );
};
