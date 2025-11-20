import {
  Attribute,
  AttributeKind,
  Constant,
  DataType,
  MappingAction,
  MappingDescription,
  TypeDefinition,
  ValueSupplier,
  SchemaKind,
  ConstantReference,
  AttributeReference,
} from "../../mapper/model/model.ts";
import {
  Button,
  Dropdown,
  Flex,
  message,
  Radio,
  Select,
  Space,
  Table,
  TableProps,
} from "antd";
import Search from "antd/lib/input/Search";
import React, { useCallback, useEffect, useState } from "react";
import {
  exportAsMarkdown,
  MarkdownMappingExportOptions,
} from "../../mapper/markdown/markdown.ts";
import { downloadFile } from "../../misc/download-utils.ts";
import { MappingUtil } from "../../mapper/util/mapping.ts";
import { formatDate, PLACEHOLDER } from "../../misc/format-utils.ts";
import {
  DESCRIPTION_KEY,
  isXmlNamespaces,
  METADATA_DATA_FORMAT_KEY,
  METADATA_SOURCE_XML_NAMESPACES_KEY,
  SourceFormat,
  XmlNamespace,
} from "../../mapper/model/metadata.ts";
import { MetadataUtil } from "../../mapper/util/metadata.ts";
import { Attributes } from "../../mapper/util/attributes.ts";
import { DataTypes } from "../../mapper/util/types.ts";
import styles from "./MappingTableView.module.css";
import inlineEditStyles from "../InlineEdit.module.css";
import { MappingActions } from "../../mapper/util/actions.ts";
import { ConstantValue } from "./ConstantValue.tsx";
import { TransformationValue } from "./TransformationValue.tsx";
import { verifyMappingAction } from "../../mapper/verification/actions.ts";
import { FilterDropdownProps, SortOrder } from "antd/es/table/interface";
import {
  getTextColumnFilterFn,
  TextColumnFilterDropdown,
} from "../table/TextColumnFilterDropdown.tsx";
import {
  EnumColumnFilterDropdown,
  getEnumColumnFilterFn,
} from "../table/EnumColumnFilterDropdown.tsx";
import {
  ElementReferenceColumnFilterDropdown,
  getElementReferenceColumnFilterFn,
} from "./ElementReferenceColumnFilterDropdown.tsx";
import {
  getTransformationColumnFilterFn,
  TransformationColumnFilterDropdown,
} from "./TransformationColumnFilterDropdown.tsx";
import { TRANSFORMATIONS } from "../../mapper/model/transformations.ts";
import { isAttributeDetail } from "../../mapper/util/schema.ts";
import { GENERATORS } from "../../mapper/model/generators.ts";
import { TextValueEdit } from "../table/TextValueEdit.tsx";
import { InlineEdit } from "../InlineEdit.tsx";
import { SelectEdit } from "../table/SelectEdit.tsx";
import { InlineTypeEdit } from "./InlineTypeEdit.tsx";
import { DefaultValueEdit } from "./DefaultValueEdit.tsx";
import { InlineElementReferencesEdit } from "./InlineElementReferencesEdit.tsx";
import { useModalsContext } from "../../Modals.tsx";
import { ConstantValueEditDialog } from "./ConstantValueEditDialog.tsx";
import {
  TransformationContext,
  TransformationEditDialog,
} from "./TransformationEditDialog.tsx";
import { useMappingDescription } from "./useMappingDescription.tsx";
import { MappingTableItemActionButton } from "./MappingTableItemActionButton.tsx";
import { OverridableIcon } from "../../icons/IconProvider.tsx";

export type MappingTableViewProps = Omit<
  React.HTMLAttributes<HTMLElement>,
  "onChange"
> & {
  elementId: string;
  mapping?: MappingDescription;
  readonlySource?: boolean;
  readonlyTarget?: boolean;
  onChange?: (mapping: MappingDescription) => void;
};

type OnChange = NonNullable<TableProps<MappingTableItem>["onChange"]>;
type Filters = Parameters<OnChange>[1];

type GetSingle<T> = T extends (infer U)[] ? U : never;
type Sorts = GetSingle<Parameters<OnChange>[2]>;

export type TableControlsState = {
  searchString: string;
  selectedColumns: string[];
  filters: Filters;
  sorts: Sorts;
};

export type ConstantItem = {
  id: string;
  itemType: "constant";
  constant: Constant;
  actions: MappingAction[];
};

export function isConstantItem(obj: unknown): obj is ConstantItem {
  return (
    typeof obj === "object" &&
    obj !== null &&
    "itemType" in obj &&
    obj.itemType === "constant"
  );
}

export type AttributeItem = {
  id: string;
  itemType: "attribute";
  kind: AttributeKind;
  path: Attribute[];
  resolvedType: DataType;
  actions: MappingAction[];
  typeDefinitions: TypeDefinition[];
  attribute: Attribute;
  children?: AttributeItem[];
};

export function isAttributeItem(obj: unknown): obj is AttributeItem {
  return (
    typeof obj === "object" &&
    obj !== null &&
    "itemType" in obj &&
    obj.itemType === "attribute"
  );
}

export type ConstantGroup = {
  id: string;
  itemType: "constant-group";
  children: ConstantItem[];
};

export function isConstantGroup(obj: unknown): obj is ConstantGroup {
  return (
    typeof obj === "object" &&
    obj !== null &&
    "itemType" in obj &&
    obj.itemType === "constant-group"
  );
}

export type HeaderGroup = {
  id: string;
  itemType: "header-group";
  kind: "header";
  children: AttributeItem[];
};

export function isHeaderGroup(obj: unknown): obj is HeaderGroup {
  return (
    typeof obj === "object" &&
    obj !== null &&
    "itemType" in obj &&
    obj.itemType === "header-group"
  );
}

export type PropertyGroup = {
  id: string;
  itemType: "property-group";
  kind: "property";
  children: AttributeItem[];
};

export function isPropertyGroup(obj: unknown): obj is PropertyGroup {
  return (
    typeof obj === "object" &&
    obj !== null &&
    "itemType" in obj &&
    obj.itemType === "property-group"
  );
}

export type BodyGroup = {
  id: string;
  itemType: "body-group";
  kind: "body";
  type: DataType | null | undefined;
  children?: AttributeItem[];
};

export function isBodyGroup(obj: unknown): obj is BodyGroup {
  return (
    typeof obj === "object" &&
    obj !== null &&
    "itemType" in obj &&
    obj.itemType === "body-group"
  );
}

export type MappingTableItem =
  | ConstantGroup
  | HeaderGroup
  | PropertyGroup
  | BodyGroup
  | ConstantItem
  | AttributeItem;

const alwaysVisibleColumns = ["name", "actions"];

function exportMappingAsMarkdown(mapping: MappingDescription): void {
  const options: MarkdownMappingExportOptions = { titleSize: "h1" };
  const text = exportAsMarkdown(mapping, options);
  const blob = new Blob([text], { type: "text/markdown" });
  const timestamp = formatDate(new Date());
  const fileName = `mapping-${timestamp}.md`;
  const file = new File([blob], fileName, { type: "text/markdown" });
  downloadFile(file);
}

export function buildAttributeItemId(
  schemaKind: SchemaKind,
  kind: AttributeKind,
  path: string[],
): string {
  return `${schemaKind}-${kind}-${path.join("-")}`;
}

export function buildConstantId(constantId: string): string {
  return `constant-${constantId}`;
}

function buildAttributeItem(
  schemaKind: SchemaKind,
  attribute: Attribute,
  kind: AttributeKind,
  path: Attribute[],
  definitions: TypeDefinition[],
  actions: MappingAction[],
): AttributeItem {
  const typeDefinitions = [
    ...definitions,
    ...Attributes.extractTypeDefinitions(path),
  ];
  const resolveResult = DataTypes.resolveType(attribute.type, typeDefinitions);
  const type = resolveResult.type ?? DataTypes.nullType();
  const p = [...path, attribute];
  const children = Attributes.getChildAttributes(
    attribute,
    resolveResult.definitions,
  )
    .filter((a) => !p.some((a1) => a1.id === a.id))
    .map((a) =>
      buildAttributeItem(schemaKind, a, kind, p, definitions, actions),
    );
  return {
    id: buildAttributeItemId(
      schemaKind,
      kind,
      p.map((i) => i.id),
    ),
    itemType: "attribute",
    kind,
    attribute,
    resolvedType: type,
    typeDefinitions: resolveResult.definitions,
    actions: MappingActions.findActionsByElementReference(actions, {
      type: "attribute",
      kind,
      path: p.map((a) => a.id),
    }),
    path: p,
    children: children.length > 0 ? children : undefined,
  };
}

export function buildMappingTableItems(
  mappingDescription: MappingDescription,
  schemaKind: SchemaKind,
): MappingTableItem[] {
  const items: MappingTableItem[] = [];
  if (schemaKind === SchemaKind.SOURCE) {
    items.push({
      id: "constant-group",
      itemType: "constant-group",
      children: mappingDescription.constants.map((constant) => ({
        id: buildConstantId(constant.id),
        itemType: "constant",
        constant,
        actions: MappingActions.findActionsByElementReference(
          mappingDescription.actions,
          {
            type: "constant",
            constantId: constant.id,
          },
        ),
      })),
    });
  }
  const schema =
    schemaKind === SchemaKind.SOURCE
      ? mappingDescription.source
      : mappingDescription.target;
  items.push(
    {
      id: "header-group",
      itemType: "header-group",
      kind: "header",
      children: schema.headers.map((attribute) =>
        buildAttributeItem(
          schemaKind,
          attribute,
          "header",
          [],
          [],
          mappingDescription.actions,
        ),
      ),
    },
    {
      id: "property-group",
      itemType: "property-group",
      kind: "property",
      children: schema.properties.map((attribute) =>
        buildAttributeItem(
          schemaKind,
          attribute,
          "property",
          [],
          [],
          mappingDescription.actions,
        ),
      ),
    },
    {
      id: "body-group",
      itemType: "body-group",
      kind: "body",
      type: schema.body
        ? DataTypes.resolveType(schema.body, []).type
        : undefined,
      children: schema.body
        ? Attributes.getChildAttributes(
            Attributes.buildAttribute("", "", schema.body),
            DataTypes.getTypeDefinitions(schema.body),
          ).map((a) =>
            buildAttributeItem(
              schemaKind,
              a,
              "body",
              [],
              schema.body ? DataTypes.getTypeDefinitions(schema.body) : [],
              mappingDescription.actions,
            ),
          )
        : undefined,
    },
  );
  return items;
}

function compareGroupItems(
  i0: MappingTableItem,
  i1: MappingTableItem,
  sortOrder: SortOrder,
): number {
  const types = [
    "constant-group",
    "header-group",
    "property-group",
    "body-group",
  ];
  const result = types.indexOf(i0.itemType) - types.indexOf(i1.itemType);
  return sortOrder === "ascend" ? result : -result;
}

export function filterMappingTableItems<
  T extends MappingTableItem | ConstantItem | AttributeItem,
>(items: T[], predicate: (item: MappingTableItem) => boolean): T[] {
  return items
    .filter((item) => predicate(item))
    .map((item) =>
      isConstantItem(item) || !item.children
        ? item
        : {
            ...item,
            // @ts-expect-error Don't know why TypeScript can't realize that a "children" field type is the same as the item.children type.
            // The type of filterMappingTableItems return value is the same as its first argument's type.
            children: filterMappingTableItems(item.children, predicate),
          },
    );
}

export function buildMappingTableItemPredicate(
  mappingDescription: MappingDescription,
  schemaKind: SchemaKind,
  searchString: string,
): (item: MappingTableItem) => boolean {
  return (item) => {
    if (
      !searchString ||
      isBodyGroup(item) ||
      isPropertyGroup(item) ||
      isHeaderGroup(item) ||
      isConstantGroup(item)
    ) {
      return true;
    }
    const contexts = getSearchContexts(item, mappingDescription, schemaKind);
    return contexts.some((context) =>
      context.toLowerCase().includes(searchString.toLowerCase()),
    );
  };
}

function getSearchContexts(
  item: MappingTableItem,
  mappingDescription: MappingDescription,
  schemaKind: SchemaKind,
): string[] {
  return schemaKind === SchemaKind.SOURCE
    ? getSourceSearchContexts(mappingDescription, item)
    : getTargetSearchContexts(mappingDescription, item);
}

function getSourceSearchContexts(
  mappingDescription: MappingDescription,
  item: MappingTableItem,
): string[] {
  if (isAttributeItem(item)) {
    const required = item.attribute.required ? "required" : "optional";
    const mappingActionTargets = item.actions
      .map((action) => action.target)
      .map((target) =>
        MappingActions.resolveReference(target, true, mappingDescription),
      )
      .filter((reference) => !!reference)
      .filter(isAttributeDetail)
      .map((reference) => reference.path.slice(-1)?.pop()?.name ?? "");
    return [
      item.attribute.name,
      MetadataUtil.getString(item.attribute, DESCRIPTION_KEY) ?? "",
      item.attribute.defaultValue ?? "",
      ...mappingActionTargets,
      required,
    ].filter((i) => !!i);
  } else if (isConstantItem(item)) {
    return [
      item.constant.name,
      MetadataUtil.getString(item.constant, DESCRIPTION_KEY) ?? "",
      ...getConstantValueSearchContexts(item.constant),
    ];
  } else {
    return [];
  }
}

function getConstantValueSearchContexts(constant: Constant): string[] {
  const supplier = constant.valueSupplier;
  return supplier.kind === "given"
    ? [supplier.value]
    : [
        supplier.generator.name,
        GENERATORS.find((g) => g.name === supplier.generator.name)?.title ?? "",
        ...(supplier.generator.parameters ?? []),
      ];
}

function getTargetSearchContexts(
  mappingDescription: MappingDescription,
  item: MappingTableItem,
): string[] {
  if (!isAttributeItem(item)) {
    return [];
  }

  const transformations = item.actions
    .map((action) => action.transformation)
    .filter((t) => !!t);
  const transformationNames = transformations.map((t) => t.name);
  const transformationTitles = transformationNames.map(
    (name) => TRANSFORMATIONS.find((t) => t.name === name)?.title ?? "",
  );
  const transformationParameters = transformations.flatMap(
    (t) => t.parameters ?? [],
  );
  const required = item.attribute.required ? "required" : "optional";
  const mappingActionSources = item.actions
    .flatMap((action) => action.sources)
    .map((source) =>
      MappingActions.resolveReference(source, false, mappingDescription),
    )
    .filter((reference) => !!reference)
    .map((reference) =>
      isAttributeDetail(reference)
        ? (reference.path.slice(-1)?.pop()?.name ?? "")
        : reference.name,
    );
  const mappingActionDescriptions = item.actions.map(
    (action) => MetadataUtil.getString(action, DESCRIPTION_KEY) ?? "",
  );
  return [
    item.attribute.name,
    MetadataUtil.getString(item.attribute, DESCRIPTION_KEY) ?? "",
    ...transformationNames,
    ...transformationTitles,
    ...transformationParameters,
    ...mappingActionSources,
    ...mappingActionDescriptions,
    required,
  ].filter((value) => !!value);
}

function updateConstantValueToMatchType(
  valueSupplier: ValueSupplier,
  type: DataType,
): ValueSupplier {
  switch (type?.name) {
    case "boolean":
      return {
        kind: "given",
        value: (
          valueSupplier?.kind === "given" && valueSupplier?.value === "true"
        ).toString(),
      };
    case "number":
      return {
        kind: "given",
        value:
          valueSupplier?.kind === "given"
            ? parseFloat(valueSupplier?.value).toString()
            : "0",
      };
    default:
      return valueSupplier;
  }
}

export function getXmlNamespaces(
  type: DataType,
  definitions: TypeDefinition[],
): XmlNamespace[] {
  let resolveResult = DataTypes.resolveType(type, definitions);
  if (!resolveResult.type) {
    return [];
  }
  resolveResult = DataTypes.resolveArrayItemType(
    resolveResult.type,
    resolveResult.definitions,
  );
  if (!resolveResult.type) {
    return [];
  }
  const xmlNamespaces = MetadataUtil.getTypedValue(
    resolveResult.type,
    METADATA_SOURCE_XML_NAMESPACES_KEY,
    isXmlNamespaces,
    () => [],
  );
  return xmlNamespaces ?? [];
}

export function buildElementReference(
  item: ConstantItem | AttributeItem,
): ConstantReference | AttributeReference {
  return isConstantItem(item)
    ? {
        type: "constant" as const,
        constantId: item.constant.id,
      }
    : {
        type: "attribute" as const,
        kind: item.kind,
        path: item.path.map((i) => i.id),
      };
}

export const MappingTableView: React.FC<MappingTableViewProps> = ({
  elementId,
  mapping,
  readonlySource,
  readonlyTarget,
  onChange,
  ...otherProps
}) => {
  const { showModal } = useModalsContext();
  const [messageApi, contextHolder] = message.useMessage();
  const [selectedSchema, setSelectedSchema] = useState<SchemaKind>(
    SchemaKind.TARGET,
  );
  const [controlsStateMap, setControlsStateMap] = useState<
    Map<SchemaKind, TableControlsState>
  >(
    new Map<SchemaKind, TableControlsState>([
      [
        SchemaKind.SOURCE,
        {
          searchString: "",
          filters: {},
          sorts: {},
          selectedColumns: ["type", "optionality", "defaultValue", "targets"],
        },
      ],
      [
        SchemaKind.TARGET,
        {
          searchString: "",
          filters: {},
          sorts: {},
          selectedColumns: ["type", "optionality", "sources", "transformation"],
        },
      ],
    ]),
  );
  const [columns, setColumns] = useState<
    TableProps<MappingTableItem>["columns"]
  >([]);
  const {
    mappingDescription,
    clearConstants,
    clearTree,
    removeConstant,
    removeAttribute,
    updateBodyType,
    exportDataType,
    addConstant,
    addAttribute,
    updateAttribute,
    updateConstant,
    updateActions,
    createOrUpdateMappingActionForTarget,
    createOrUpdateMappingActionsForSource,
    updateXmlNamespaces,
  } = useMappingDescription({ mapping, onChange });

  const [tableItems, setTableItems] = useState<MappingTableItem[]>([]);
  const [readonly, setReadonly] = useState<boolean>(false);
  const [bodyFormat, setBodyFormat] = useState<string>(SourceFormat.JSON);

  useEffect(() => {
    setReadonly(
      (selectedSchema === SchemaKind.SOURCE && !!readonlySource) ||
        (selectedSchema === SchemaKind.TARGET && !!readonlyTarget),
    );
  }, [readonlySource, readonlyTarget, selectedSchema]);

  useEffect(() => {
    const items = buildMappingTableItems(mappingDescription, selectedSchema);
    const predicate = buildMappingTableItemPredicate(
      mappingDescription,
      selectedSchema,
      controlsStateMap.get(selectedSchema)?.searchString ?? "",
    );
    const filteredItems = filterMappingTableItems(items, predicate);
    setTableItems(filteredItems);
  }, [mappingDescription, selectedSchema, controlsStateMap]);

  useEffect(() => {
    const format = mappingDescription[selectedSchema].body
      ? (MetadataUtil.getString(
          mappingDescription[selectedSchema].body,
          METADATA_DATA_FORMAT_KEY,
        ) ?? "")
      : SourceFormat.JSON;
    setBodyFormat(format);
  }, [mappingDescription, selectedSchema]);

  const updateControlsState = useCallback(
    (changes: Partial<TableControlsState>) => {
      const defaultState = {
        searchString: "",
        selectedColumns:
          selectedSchema === SchemaKind.SOURCE
            ? ["type", "optionality", "defaultValue", "targets"]
            : ["type", "optionality", "sources", "transformation"],
        filters: {},
        sorts: {},
      };
      setControlsStateMap((currentMap) => {
        const newState = {
          ...defaultState,
          ...currentMap.get(selectedSchema),
          ...changes,
        };
        return new Map<SchemaKind, TableControlsState>(currentMap).set(
          selectedSchema,
          newState,
        );
      });
    },
    [selectedSchema],
  );

  const [searchString, setSearchString] = useState<string>("");

  useEffect(() => {
    setSearchString(controlsStateMap.get(selectedSchema)?.searchString ?? "");
  }, [selectedSchema, controlsStateMap]);

  const clearTreeForItem = useCallback(
    (item: MappingTableItem) => {
      if (isConstantGroup(item)) {
        clearConstants();
      } else if (isConstantItem(item)) {
        // Do nothing
      } else {
        const path = isAttributeItem(item) ? item.path : [];
        clearTree(selectedSchema, item.kind, path);
      }
    },
    [clearConstants, clearTree, selectedSchema],
  );

  const exportElement = useCallback(
    (item: MappingTableItem) => {
      const type = isAttributeItem(item)
        ? item.resolvedType
        : isBodyGroup(item)
          ? (item.type ?? DataTypes.nullType())
          : DataTypes.nullType();
      const definitions = isAttributeItem(item) ? item.typeDefinitions : [];
      exportDataType(type, definitions);
    },
    [exportDataType],
  );

  const tryAddElement = useCallback(
    (item: MappingTableItem) => {
      if (isConstantGroup(item)) {
        addConstant({}, (error: unknown) => {
          const content =
            error instanceof Error ? error.message : "Failed to add constant";
          void messageApi.open({ type: "error", content });
        });
      } else if (isConstantItem(item)) {
        // Do nothing
      } else {
        const path = isAttributeItem(item) ? item.path : [];
        addAttribute(selectedSchema, item.kind, path, {}, (error: unknown) => {
          const content =
            error instanceof Error ? error.message : "Failed to add attribute";
          void messageApi.open({ type: "error", content });
        });
      }
    },
    [addAttribute, addConstant, messageApi, selectedSchema],
  );

  const tryUpdateAttribute = useCallback(
    (kind: AttributeKind, path: Attribute[], changes: Partial<Attribute>) => {
      updateAttribute(selectedSchema, kind, path, changes, (error: unknown) => {
        const content =
          error instanceof Error ? error.message : "Failed to update attribute";
        void messageApi.open({ type: "error", content });
      });
    },
    [messageApi, selectedSchema, updateAttribute],
  );

  const tryUpdateConstant = useCallback(
    (id: string, changes: Partial<Constant>) => {
      updateConstant(id, changes, (error: unknown) => {
        const content =
          error instanceof Error ? error.message : "Failed to update constant";
        void messageApi.open({ type: "error", content });
      });
    },
    [messageApi, updateConstant],
  );

  const buildColumns =
    useCallback((): TableProps<MappingTableItem>["columns"] => {
      return [
        {
          key: "name",
          title: "Name",
          render: (_value: unknown, item: MappingTableItem) => {
            return isBodyGroup(item) ? (
              <Space>
                <span>body</span>
                {mappingDescription[selectedSchema].body ? (
                  <Select<string>
                    size={"small"}
                    value={bodyFormat}
                    variant="borderless"
                    options={[
                      { value: SourceFormat.JSON, label: "JSON" },
                      { value: SourceFormat.XML, label: "XML" },
                    ]}
                    onChange={(value) => {
                      if (!mappingDescription[selectedSchema].body) {
                        return;
                      }
                      const type = MetadataUtil.setValue(
                        mappingDescription[selectedSchema].body,
                        METADATA_DATA_FORMAT_KEY,
                        value,
                      );
                      updateBodyType(selectedSchema, type);
                    }}
                  />
                ) : (
                  <></>
                )}
              </Space>
            ) : isConstantGroup(item) ? (
              <span>constants</span>
            ) : isHeaderGroup(item) ? (
              <span>headers</span>
            ) : isPropertyGroup(item) ? (
              <span>properties</span>
            ) : isAttributeItem(item) ? (
              readonly ? (
                item.attribute.name
              ) : (
                <InlineEdit<{ name: string }>
                  values={{ name: item.attribute.name }}
                  editor={<TextValueEdit name={"name"} />}
                  viewer={item.attribute.name}
                  initialActive={!item.attribute.name}
                  onSubmit={({ name }) => {
                    tryUpdateAttribute(item.kind, item.path, { name });
                  }}
                />
              )
            ) : isConstantItem(item) ? (
              readonly ? (
                item.constant.name
              ) : (
                <InlineEdit<{ name: string }>
                  values={{ name: item.constant.name }}
                  editor={<TextValueEdit name={"name"} />}
                  viewer={item.constant.name}
                  initialActive={!item.constant.name}
                  onSubmit={({ name }) => {
                    tryUpdateConstant(item.constant.id, { name });
                  }}
                />
              )
            ) : (
              <></>
            );
          },
          onCell: (item: MappingTableItem) => {
            return (isConstantItem(item) && !item.constant.name) ||
              (isAttributeItem(item) && !item.attribute.name)
              ? { className: styles["invalid-value"] }
              : {};
          },
          sorter: (
            i0: MappingTableItem,
            i1: MappingTableItem,
            sortOrder: SortOrder | undefined,
          ) => {
            if (isAttributeItem(i0) || isConstantItem(i0)) {
              const name1 = isAttributeItem(i0)
                ? i0.attribute.name
                : i0.constant.name;
              const name2 = isAttributeItem(i0)
                ? (i1 as AttributeItem).attribute.name
                : (i1 as ConstantItem).constant.name;
              return name1.localeCompare(name2);
            } else {
              return compareGroupItems(i0, i1, sortOrder ?? null);
            }
          },
          sortDirections: ["ascend" as const, "descend" as const, null],
          sortOrder:
            controlsStateMap.get(selectedSchema)?.sorts.columnKey === "name"
              ? controlsStateMap.get(selectedSchema)?.sorts.order
              : null,
          filterDropdown: (props: FilterDropdownProps) => (
            <TextColumnFilterDropdown {...props} />
          ),
          onFilter: (value: boolean | React.Key, item: MappingTableItem) => {
            return (
              (!isAttributeItem(item) && !isConstantItem(item)) ||
              getTextColumnFilterFn((i: MappingTableItem) =>
                isAttributeItem(i)
                  ? i.attribute.name
                  : isConstantItem(i)
                    ? i.constant.name
                    : "",
              )(value, item)
            );
          },
          filteredValue:
            controlsStateMap.get(selectedSchema)?.filters.name || null,
        },
        {
          key: "type",
          title: "Type",
          render: (_value: unknown, item: MappingTableItem) => {
            if (isBodyGroup(item)) {
              return (
                <InlineTypeEdit
                  type={item.type}
                  definitions={
                    item.type ? DataTypes.getTypeDefinitions(item.type) : []
                  }
                  readonly={readonly}
                  onSubmit={(type) => {
                    updateBodyType(selectedSchema, type);
                  }}
                />
              );
            } else if (isConstantItem(item)) {
              return (
                <InlineTypeEdit
                  type={item.constant.type}
                  definitions={[]}
                  readonly={readonly}
                  disableObjectType={true}
                  disableArrayTypes={true}
                  onSubmit={(type) => {
                    if (type) {
                      tryUpdateConstant(item.constant.id, {
                        ...item.constant,
                        type,
                        valueSupplier: updateConstantValueToMatchType(
                          item.constant.valueSupplier,
                          type,
                        ),
                      });
                    }
                  }}
                />
              );
            } else if (isAttributeItem(item)) {
              return (
                <InlineTypeEdit
                  type={item.attribute.type}
                  definitions={item.typeDefinitions}
                  readonly={readonly}
                  onSubmit={(type) => {
                    if (type) {
                      tryUpdateAttribute(item.kind, item.path, {
                        type,
                        defaultValue: undefined,
                      });
                    }
                  }}
                />
              );
            }
          },
          sorter: (
            i0: MappingTableItem,
            i1: MappingTableItem,
            sortOrder: SortOrder | undefined,
          ) => {
            if (isAttributeItem(i0) || isConstantItem(i0)) {
              const name1 = isAttributeItem(i0)
                ? DataTypes.buildTypeName(i0.attribute.type, i0.typeDefinitions)
                : DataTypes.buildTypeName(i0.constant.type, []);
              const name2 = isAttributeItem(i0)
                ? DataTypes.buildTypeName(
                    (i1 as AttributeItem).attribute.type,
                    i0.typeDefinitions,
                  )
                : DataTypes.buildTypeName(
                    (i1 as ConstantItem).constant.type,
                    [],
                  );
              return name1.localeCompare(name2);
            } else {
              return compareGroupItems(i0, i1, sortOrder ?? null);
            }
          },
          sortDirections: ["ascend" as const, "descend" as const, null],
          sortOrder:
            controlsStateMap.get(selectedSchema)?.sorts.columnKey === "type"
              ? controlsStateMap.get(selectedSchema)?.sorts.order
              : null,
          filterDropdown: (props: FilterDropdownProps) => (
            <EnumColumnFilterDropdown
              options={[
                { value: "string", label: "string" },
                { value: "number", label: "number" },
                { value: "boolean", label: "boolean" },
                { value: "object", label: "object" },
                { value: "array", label: "array" },
              ]}
              {...props}
            />
          ),
          onFilter: (value: boolean | React.Key, item: MappingTableItem) => {
            return (
              !(isAttributeItem(item) || isConstantItem(item)) ||
              getEnumColumnFilterFn((i) =>
                isAttributeItem(i)
                  ? i.resolvedType.name
                  : isConstantItem(i)
                    ? i.constant.type.name
                    : "",
              )(value, item)
            );
          },
          filteredValue:
            controlsStateMap.get(selectedSchema)?.filters.type || null,
        },
        ...(selectedSchema === SchemaKind.TARGET
          ? [
              {
                key: "optionality",
                title: "Optionality",
                render: (_value: unknown, item: MappingTableItem) => {
                  return isAttributeItem(item) ? (
                    readonly ? (
                      item.attribute.required ? (
                        "required"
                      ) : (
                        "optional"
                      )
                    ) : (
                      <InlineEdit<{ optionality: string }>
                        values={{
                          optionality: String(!!item.attribute.required),
                        }}
                        editor={
                          <SelectEdit
                            name="optionality"
                            options={[
                              {
                                value: "false",
                                label: "optional",
                              },
                              { value: "true", label: "required" },
                            ]}
                          />
                        }
                        viewer={item.attribute.required ? "required" : "optional"}
                        onSubmit={({ optionality }) => {
                          tryUpdateAttribute(item.kind, item.path, {
                            required: optionality === "true",
                          });
                        }}
                      />
                    )
                  ) : (
                    <></>
                  );
                },
                sorter: (
                  i0: MappingTableItem,
                  i1: MappingTableItem,
                  sortOrder: SortOrder | undefined,
                ) => {
                  if (isAttributeItem(i0)) {
                    const s1 = i0.attribute.required ? "required" : "optional";
                    const s2 = (i1 as AttributeItem).attribute.required
                      ? "required"
                      : "optional";
                    return s1.localeCompare(s2);
                  } else {
                    return compareGroupItems(i0, i1, sortOrder ?? null);
                  }
                },
                sortDirections: ["ascend" as const, "descend" as const, null],
                sortOrder:
                  controlsStateMap.get(selectedSchema)?.sorts.columnKey ===
                  "optionality"
                    ? controlsStateMap.get(selectedSchema)?.sorts.order
                    : null,
                filterDropdown: (props: FilterDropdownProps) => (
                  <EnumColumnFilterDropdown
                    options={[
                      { value: "false", label: "optional" },
                      { value: "true", label: "required" },
                    ]}
                    {...props}
                  />
                ),
                onFilter: (
                  value: boolean | React.Key,
                  item: MappingTableItem,
                ) => {
                  return (
                    !isAttributeItem(item) ||
                    getEnumColumnFilterFn((i: AttributeItem) =>
                      String(i.attribute.required ?? false),
                    )(value, item)
                  );
                },
                filteredValue:
                  controlsStateMap.get(selectedSchema)?.filters.optionality ||
                  null,
              },
            ]
          : []),
        {
          key: "description",
          title: "Description",
          render: (_value: unknown, item: MappingTableItem) => {
            return isAttributeItem(item) ? (
              readonly ? (
                (MetadataUtil.getString(item.attribute, DESCRIPTION_KEY) ??
                PLACEHOLDER)
              ) : (
                <InlineEdit<{ description: string }>
                  values={{
                    description:
                      MetadataUtil.getString(item.attribute, DESCRIPTION_KEY) ??
                      "",
                  }}
                  editor={<TextValueEdit name="description" rules={[]} />}
                  viewer={
                    MetadataUtil.getString(item.attribute, DESCRIPTION_KEY) ??
                    PLACEHOLDER
                  }
                  onSubmit={({ description }) => {
                    tryUpdateAttribute(item.kind, item.path, {
                      metadata: MetadataUtil.upsert(
                        item.attribute.metadata,
                        DESCRIPTION_KEY,
                        description ? description : undefined,
                      ),
                    });
                  }}
                />
              )
            ) : isConstantItem(item) ? (
              readonly ? (
                (MetadataUtil.getString(item.constant, DESCRIPTION_KEY) ??
                PLACEHOLDER)
              ) : (
                <InlineEdit<{ description: string }>
                  values={{
                    description:
                      MetadataUtil.getString(item.constant, DESCRIPTION_KEY) ??
                      "",
                  }}
                  editor={<TextValueEdit name="description" rules={[]} />}
                  viewer={
                    MetadataUtil.getString(item.constant, DESCRIPTION_KEY) ??
                    PLACEHOLDER
                  }
                  onSubmit={({ description }) => {
                    tryUpdateConstant(item.constant.id, {
                      metadata: MetadataUtil.upsert(
                        item.constant.metadata,
                        DESCRIPTION_KEY,
                        description ? description : undefined,
                      ),
                    });
                  }}
                />
              )
            ) : (
              <></>
            );
          },
          sorter: (
            i0: MappingTableItem,
            i1: MappingTableItem,
            sortOrder: SortOrder | undefined,
          ) => {
            if (isAttributeItem(i0) || isConstantItem(i0)) {
              const description1 = isAttributeItem(i0)
                ? MetadataUtil.getString(i0.attribute, DESCRIPTION_KEY) || ""
                : MetadataUtil.getString(i0.constant, DESCRIPTION_KEY) || "";
              const description2 = isAttributeItem(i0)
                ? MetadataUtil.getString(
                    (i1 as AttributeItem).attribute,
                    DESCRIPTION_KEY,
                  ) || ""
                : MetadataUtil.getString(
                    (i1 as ConstantItem).constant,
                    DESCRIPTION_KEY,
                  ) || "";
              return description1.localeCompare(description2);
            } else {
              return compareGroupItems(i0, i1, sortOrder ?? null);
            }
          },
          sortDirections: ["ascend" as const, "descend" as const, null],
          sortOrder:
            controlsStateMap.get(selectedSchema)?.sorts.columnKey ===
            "description"
              ? controlsStateMap.get(selectedSchema)?.sorts.order
              : null,
          filterDropdown: (props: FilterDropdownProps) => (
            <TextColumnFilterDropdown {...props} />
          ),
          onFilter: (value: boolean | React.Key, item: MappingTableItem) => {
            return (
              (!isAttributeItem(item) && !isConstantItem(item)) ||
              getTextColumnFilterFn((i: MappingTableItem) =>
                isAttributeItem(i)
                  ? (MetadataUtil.getString(i.attribute, DESCRIPTION_KEY) ?? "")
                  : isConstantItem(i)
                    ? (MetadataUtil.getString(i.constant, DESCRIPTION_KEY) ??
                      "")
                    : "",
              )(value, item)
            );
          },
          filteredValue:
            controlsStateMap.get(selectedSchema)?.filters.description || null,
        },
        {
          key: "defaultValue",
          title: "Default value",
          render: (_value: unknown, item: MappingTableItem) => {
            return isAttributeItem(item) ? (
              item.resolvedType.name === "string" ||
              item.resolvedType.name === "boolean" ||
              item.resolvedType.name === "number" ? (
                readonly ? (
                  (item.attribute.defaultValue ?? PLACEHOLDER)
                ) : (
                  <InlineEdit<{ value: string | undefined }>
                    values={{
                      value:
                        item.resolvedType.name === "boolean" &&
                        item.attribute.defaultValue === undefined
                          ? ""
                          : item.attribute.defaultValue,
                    }}
                    editor={
                      <DefaultValueEdit
                        name={"value"}
                        type={item.resolvedType.name}
                      />
                    }
                    viewer={item.attribute.defaultValue ?? PLACEHOLDER}
                    onSubmit={({ value }) => {
                      tryUpdateAttribute(item.kind, item.path, {
                        defaultValue:
                          item.resolvedType.name === "boolean" && value === ""
                            ? undefined
                            : value,
                      });
                    }}
                  />
                )
              ) : (
                <></>
              )
            ) : isConstantItem(item) ? (
              <ConstantValue
                className={inlineEditStyles["inlineEditValueWrap"]}
                valueSupplier={item.constant.valueSupplier}
                onClick={() => {
                  if (readonly) {
                    return;
                  }
                  showModal({
                    component: (
                      <ConstantValueEditDialog
                        type={item.constant.type}
                        valueSupplier={item.constant.valueSupplier}
                        onSubmit={(valueSupplier) => {
                          tryUpdateConstant(item.constant.id, {
                            valueSupplier,
                          });
                        }}
                      />
                    ),
                  });
                }}
              />
            ) : (
              <></>
            );
          },
          sorter: (
            i0: MappingTableItem,
            i1: MappingTableItem,
            sortOrder: SortOrder | undefined,
          ) => {
            if (isAttributeItem(i0)) {
              const value1 = i0.attribute.defaultValue ?? "";
              const value2 = (i1 as AttributeItem).attribute.defaultValue ?? "";
              return value1.localeCompare(value2);
            } else if (isConstantItem(i0)) {
              const value1 =
                i0.constant.valueSupplier.kind === "given"
                  ? i0.constant.valueSupplier.value
                  : i0.constant.valueSupplier.generator.name +
                    " " +
                    i0.constant.valueSupplier.generator.parameters.join(" ");
              const c1 = i1 as ConstantItem;
              const value2 =
                c1.constant.valueSupplier.kind === "given"
                  ? c1.constant.valueSupplier.value
                  : c1.constant.valueSupplier.generator.name +
                    " " +
                    c1.constant.valueSupplier.generator.parameters.join(" ");
              return value1.localeCompare(value2);
            } else {
              return compareGroupItems(i0, i1, sortOrder ?? null);
            }
          },
          sortDirections: ["ascend" as const, "descend" as const, null],
          sortOrder:
            controlsStateMap.get(selectedSchema)?.sorts.columnKey ===
            "defaultValue"
              ? controlsStateMap.get(selectedSchema)?.sorts.order
              : null,
          filterDropdown: (props: FilterDropdownProps) => (
            <TextColumnFilterDropdown {...props} />
          ),
          onFilter: (value: boolean | React.Key, item: MappingTableItem) => {
            return (
              !isAttributeItem(item) ||
              getTextColumnFilterFn(
                (i: AttributeItem) => i.attribute.defaultValue ?? "",
              )(value, item)
            );
          },
          filteredValue:
            controlsStateMap.get(selectedSchema)?.filters.defaultValue || null,
        },
        ...(selectedSchema === SchemaKind.SOURCE
          ? [
              {
                key: "targets",
                title: "Targets",
                render: (_value: unknown, item: MappingTableItem) => {
                  return isAttributeItem(item) || isConstantItem(item) ? (
                    <InlineElementReferencesEdit
                      readonly={readonly}
                      mappingDescription={mappingDescription}
                      isTarget={true}
                      values={item.actions.map((a) => a.target)}
                      onSubmit={(values) => {
                        const source = buildElementReference(item);
                        const targets = values.filter((reference) =>
                          MappingUtil.isAttributeReference(reference),
                        );
                        createOrUpdateMappingActionsForSource(source, targets);
                      }}
                    />
                  ) : (
                    <></>
                  );
                },
                filterDropdown: (props: FilterDropdownProps) => (
                  <ElementReferenceColumnFilterDropdown
                    enableConstLocation={false}
                    {...props}
                  />
                ),
                onFilter: (
                  value: React.Key | boolean,
                  item: MappingTableItem,
                ) => {
                  return (
                    (!isAttributeItem(item) && !isConstantItem(item)) ||
                    getElementReferenceColumnFilterFn(
                      (item: AttributeItem | ConstantItem) => {
                        return item.actions.map((action) => action.target);
                      },
                      true,
                      mappingDescription,
                    )(value, item)
                  );
                },
                filteredValue:
                  controlsStateMap.get(selectedSchema)?.filters.targets || null,
              },
            ]
          : [
              {
                key: "sources",
                title: "Sources",
                render: (_value: unknown, item: MappingTableItem) => {
                  return isAttributeItem(item) ? (
                    <InlineElementReferencesEdit
                      readonly={readonly}
                      mappingDescription={mappingDescription}
                      isTarget={false}
                      values={item.actions.flatMap((a) => a.sources)}
                      onSubmit={(values) => {
                        const target = {
                          type: "attribute" as const,
                          kind: item.kind,
                          path: item.path.map((i) => i.id),
                        };
                        createOrUpdateMappingActionForTarget(
                          item.actions,
                          target,
                          values,
                        );
                      }}
                    />
                  ) : (
                    <></>
                  );
                },
                filterDropdown: (props: FilterDropdownProps) => (
                  <ElementReferenceColumnFilterDropdown
                    enableConstLocation={true}
                    {...props}
                  />
                ),
                onFilter: (
                  value: React.Key | boolean,
                  item: MappingTableItem,
                ) => {
                  return (
                    (!isAttributeItem(item) && !isConstantItem(item)) ||
                    getElementReferenceColumnFilterFn(
                      (item: AttributeItem | ConstantItem) => {
                        return item.actions.flatMap((action) => action.sources);
                      },
                      false,
                      mappingDescription,
                    )(value, item)
                  );
                },
                filteredValue:
                  controlsStateMap.get(selectedSchema)?.filters.sources || null,
              },
              {
                key: "transformation",
                title: "Transformation",
                render: (_value: unknown, item: MappingTableItem) => {
                  return isAttributeItem(item) ? (
                    item.actions.map((action, index) => (
                      <div
                        key={index}
                        className={inlineEditStyles["inlineEditValueWrap"]}
                        onClick={() => {
                          if (readonly) {
                            return;
                          }
                          showModal({
                            component: (
                              <TransformationContext.Provider
                                value={{
                                  mappingDescription: mappingDescription,
                                  action,
                                }}
                              >
                                <TransformationEditDialog
                                  transformation={action.transformation}
                                  onSubmit={(transformation) => {
                                    updateActions((a) => {
                                      return a.id === action.id
                                        ? {
                                            ...a,
                                            transformation,
                                          }
                                        : a;
                                    });
                                  }}
                                />
                              </TransformationContext.Provider>
                            ),
                          });
                        }}
                      >
                        <TransformationValue
                          key={index}
                          transformation={action.transformation}
                          errors={verifyMappingAction(
                            action,
                            mappingDescription,
                          )}
                        />
                      </div>
                    ))
                  ) : (
                    <></>
                  );
                },
                onCell: (item: MappingTableItem) => {
                  return isAttributeItem(item) &&
                    item.actions.flatMap((action) =>
                      verifyMappingAction(action, mappingDescription),
                    ).length > 0
                    ? { className: styles["invalid-value"] }
                    : {};
                },
                sorter: (
                  i0: MappingTableItem,
                  i1: MappingTableItem,
                  sortOrder: SortOrder | undefined,
                ) => {
                  if (isAttributeItem(i0)) {
                    const value1 = i0.actions
                      .map((action) => action.transformation)
                      .filter((transformation) => !!transformation)
                      .map((transformation) =>
                        [
                          transformation.name,
                          ...transformation.parameters,
                        ].join(" "),
                      )
                      .join();
                    const value2 = (i1 as AttributeItem).actions
                      .map((action) => action.transformation)
                      .filter((transformation) => !!transformation)
                      .map((transformation) =>
                        [
                          transformation.name,
                          ...transformation.parameters,
                        ].join(" "),
                      )
                      .join();
                    return value1.localeCompare(value2);
                  } else if (isConstantItem(i0)) {
                    return 0;
                  } else {
                    return compareGroupItems(i0, i1, sortOrder ?? null);
                  }
                },
                sortDirections: ["ascend" as const, "descend" as const, null],
                sortOrder:
                  controlsStateMap.get(selectedSchema)?.sorts.columnKey ===
                  "transformation"
                    ? controlsStateMap.get(selectedSchema)?.sorts.order
                    : null,
                filterDropdown: (props: FilterDropdownProps) => (
                  <TransformationColumnFilterDropdown {...props} />
                ),
                onFilter: (
                  value: React.Key | boolean,
                  item: MappingTableItem,
                ) => {
                  return (
                    !isAttributeItem(item) ||
                    getTransformationColumnFilterFn((i: AttributeItem) =>
                      i.actions.flatMap((action) => action.transformation),
                    )(value, item)
                  );
                },
                filteredValue:
                  controlsStateMap.get(selectedSchema)?.filters
                    .transformation || null,
              },
              {
                key: "transformationDescription",
                title: "Transformation description",
                render: (_value: unknown, item: MappingTableItem) => {
                  if (isAttributeItem(item) && item.actions.length > 0) {
                    const description = item.actions
                      .map((action) =>
                        MetadataUtil.getString(action, DESCRIPTION_KEY),
                      )
                      .filter((description) => !!description)
                      .join(" ");
                    return readonly ? (
                      description || PLACEHOLDER
                    ) : (
                      <InlineEdit<{ description: string }>
                        values={{ description }}
                        editor={<TextValueEdit name="description" rules={[]} />}
                        viewer={description || PLACEHOLDER}
                        onSubmit={({ description }) => {
                          updateActions((action) => {
                            return item.actions.some((a) => a.id === action.id)
                              ? MetadataUtil.setValue(
                                  action,
                                  DESCRIPTION_KEY,
                                  description,
                                )
                              : action;
                          });
                        }}
                      />
                    );
                  } else {
                    return <></>;
                  }
                },
                sorter: (
                  i0: MappingTableItem,
                  i1: MappingTableItem,
                  sortOrder: SortOrder | undefined,
                ) => {
                  if (isAttributeItem(i0)) {
                    const value1 = i0.actions
                      .map(
                        (action) =>
                          MetadataUtil.getString(action, DESCRIPTION_KEY) ?? "",
                      )
                      .join();
                    const value2 = (i1 as AttributeItem).actions
                      .map(
                        (action) =>
                          MetadataUtil.getString(action, DESCRIPTION_KEY) ?? "",
                      )
                      .join();
                    return value1.localeCompare(value2);
                  } else if (isConstantItem(i0)) {
                    return 0;
                  } else {
                    return compareGroupItems(i0, i1, sortOrder ?? null);
                  }
                },
                sortDirections: ["ascend" as const, "descend" as const, null],
                sortOrder:
                  controlsStateMap.get(selectedSchema)?.sorts.columnKey ===
                  "transformationDescription"
                    ? controlsStateMap.get(selectedSchema)?.sorts.order
                    : null,
                filterDropdown: (props: FilterDropdownProps) => (
                  <TextColumnFilterDropdown {...props} />
                ),
                onFilter: (
                  value: boolean | React.Key,
                  item: MappingTableItem,
                ) => {
                  return (
                    !isAttributeItem(item) ||
                    getTextColumnFilterFn((i: MappingTableItem) =>
                      isAttributeItem(i)
                        ? i.actions
                            .map(
                              (action) =>
                                MetadataUtil.getString(
                                  action,
                                  DESCRIPTION_KEY,
                                ) ?? "",
                            )
                            .join()
                        : "",
                    )(value, item)
                  );
                },
                filteredValue:
                  controlsStateMap.get(selectedSchema)?.filters
                    .transformationDescription || null,
              },
            ]),
        {
          key: "actions",
          title: "",
          width: 40,
          align: "right" as const,
          className: "actions-column",
          render: (_value: unknown, item: MappingTableItem) => {
            return (
              <MappingTableItemActionButton
                elementId={elementId}
                item={item}
                readonly={readonly}
                enableEdit={false}
                enableXmlNamespaces={bodyFormat === SourceFormat.XML.toString()}
                schemaKind={selectedSchema}
                onLoad={(type) => {
                  if (isBodyGroup(item)) {
                    updateBodyType(selectedSchema, type);
                  } else if (isAttributeItem(item)) {
                    tryUpdateAttribute(item.kind, item.path, {
                      type,
                    });
                  }
                }}
                onExport={() => exportElement(item)}
                onUpdateXmlNamespaces={(namespaces) =>
                  updateXmlNamespaces(
                    selectedSchema,
                    isAttributeItem(item) ? item.path : [],
                    namespaces,
                  )
                }
                onAdd={() => tryAddElement(item)}
                onClear={() => clearTreeForItem(item)}
                onDelete={() => {
                  if (isConstantItem(item)) {
                    removeConstant(item.constant.id);
                  } else if (isAttributeItem(item)) {
                    removeAttribute(selectedSchema, item.kind, item.path);
                  }
                }}
              />
            );
          },
        },
      ].map((column) => ({
        ...column,
        hidden:
          !alwaysVisibleColumns.includes(column.key) &&
          !controlsStateMap
            .get(selectedSchema)
            ?.selectedColumns?.includes(column.key),
      }));
    }, [
      controlsStateMap,
      selectedSchema,
      mappingDescription,
      bodyFormat,
      readonly,
      updateBodyType,
      tryUpdateAttribute,
      tryUpdateConstant,
      showModal,
      createOrUpdateMappingActionsForSource,
      createOrUpdateMappingActionForTarget,
      updateActions,
      elementId,
      exportElement,
      updateXmlNamespaces,
      tryAddElement,
      clearTreeForItem,
      removeConstant,
      removeAttribute,
    ]);

  useEffect(() => {
    setColumns(buildColumns());
  }, [buildColumns]);

  return (
    <>
      {contextHolder}
      <Flex vertical gap={16} style={{ height: "100%" }} {...otherProps}>
        <Flex vertical={false} align={"center"} justify={"center"} gap={8}>
          <Radio.Group
            block
            options={[
              { label: "Source", value: "source" },
              { label: "Target", value: "target" },
            ]}
            defaultValue={SchemaKind.TARGET}
            optionType="button"
            buttonStyle="solid"
            onChange={(event) =>
              setSelectedSchema(event.target.value as SchemaKind)
            }
          />
          <Search
            placeholder="Full text search"
            allowClear
            value={searchString}
            onChange={(event) => {
              setSearchString(event.target.value);
            }}
            onSearch={(value) => {
              updateControlsState({ searchString: value });
            }}
          />
          <Dropdown
            menu={{
              items: (columns ?? [])
                .filter((column) => column.key !== "actions")
                .map((column) => ({
                  key: column.key?.toString() ?? "",
                  label: column.title as string,
                  disabled: alwaysVisibleColumns.includes(
                    column.key?.toString() ?? "",
                  ),
                })),
              selectable: true,
              multiple: true,
              selectedKeys:
                controlsStateMap.get(selectedSchema)?.selectedColumns ?? [],
              onSelect: ({ selectedKeys }) => {
                updateControlsState({ selectedColumns: selectedKeys });
              },
              onDeselect: ({ selectedKeys }) =>
                updateControlsState({ selectedColumns: selectedKeys }),
            }}
          >
            <Button icon={<OverridableIcon name="settings" />} />
          </Dropdown>
          <Dropdown
            menu={{
              items: [
                {
                  key: "saveAsMarkdown",
                  icon: <OverridableIcon name="fileMarkdown" />,
                  label: "Save as markdown",
                  onClick: () => {
                    exportMappingAsMarkdown(mappingDescription);
                  },
                },
                {
                  key: "clearFilters",
                  icon: <OverridableIcon name="clear" />,
                  label: "Clear filters",
                  onClick: () => {
                    updateControlsState({ filters: {} });
                  },
                },
                {
                  key: "clearSorts",
                  icon: <OverridableIcon name="clear" />,
                  label: "Clear sorters",
                  onClick: () => {
                    updateControlsState({ sorts: {} });
                  },
                },
              ],
            }}
            trigger={["click"]}
            placement="bottomLeft"
          >
            <Button icon={<OverridableIcon name="more" />} />
          </Dropdown>
        </Flex>
        <Table<MappingTableItem>
          className="flex-table"
          size="small"
          columns={columns}
          dataSource={tableItems}
          rowKey="id"
          pagination={false}
          scroll={{ y: "" }}
          expandable={{
            defaultExpandedRowKeys: [
              "constant-group",
              "header-group",
              "property-group",
              "body-group",
            ],
          }}
          rowClassName={(item: MappingTableItem) => {
            return isHeaderGroup(item) ||
              isConstantGroup(item) ||
              isPropertyGroup(item) ||
              isBodyGroup(item)
              ? styles["group-row"]
              : "";
          }}
          onChange={(_pagination, filters, sorts) => {
            updateControlsState({
              filters,
              sorts: Array.isArray(sorts) ? undefined : sorts,
            });
          }}
        ></Table>
      </Flex>
    </>
  );
};
