import React, { ReactNode, useEffect } from "react";
import {
  Attribute,
  AttributeKind,
  AttributeReference,
  Constant,
  ConstantReference,
  MappingDescription,
  TypeDefinition,
} from "../../mapper/model/model";
import { Flex, TreeSelectProps } from "antd";
import { ElementReference } from "./ElementReference";
import { InlineEdit } from "../InlineEdit";
import { TreeSelectEdit } from "../table/TreeSelectEdit";
import { Attributes } from "../../mapper/util/attributes";
import { DataTypes } from "../../mapper/util/types";
import { Key } from "antd/lib/table/interface";
import { parseJson } from "../../misc/json-helper";
import { MappingUtil } from "../../mapper/util/mapping";
import styles from "./ElementReference.module.css";

type InlineElementReferencesEditProps = {
  mappingDescription: MappingDescription;
  isTarget: boolean;
  values: (AttributeReference | ConstantReference)[];
  readonly?: boolean;
  onSubmit?: (values: (AttributeReference | ConstantReference)[]) => void;
};

function buildTreeNodeValue<T extends object>(v: T): string {
  return JSON.stringify(v, Object.keys(v).sort());
}

function parseTreeNodeValue(
  value: string,
): ConstantReference | AttributeReference {
  return parseJson(
    value,
    (v) =>
      MappingUtil.isObjConstantReference(v) ||
      MappingUtil.isAttributeReference(v),
  );
}

function buildConstantTreeDataNode(
  constant: Constant,
): NonNullable<TreeSelectProps["treeData"]>[0] {
  const reference: ConstantReference = {
    type: "constant",
    constantId: constant.id,
  };
  return {
    title: constant.name,
    label: getConstantNodeLabel(constant),
    value: buildTreeNodeValue(reference),
  };
}

function getNodeLabel(attribute: Attribute, kind: AttributeKind): ReactNode {
  return (
    <>
      <span className={styles["element-type"]}>{kind[0].toUpperCase()}</span>
      <span className={styles["element-name"]}>{attribute.name}</span>
    </>
  );
}

function getConstantNodeLabel(constant: Constant): ReactNode {
  return (
    <>
      <span className={styles["element-type"]}>C</span>
      <span className={styles["element-name"]}>{constant.name}</span>
    </>
  );
}

function buildTreeDataNode(
  attribute: Attribute,
  kind: AttributeKind,
  path: Attribute[],
  typeDefinitions: TypeDefinition[],
): NonNullable<TreeSelectProps["treeData"]>[0] {
  const p = [...path, attribute];
  const resolveResult = DataTypes.resolveType(attribute.type, typeDefinitions);
  const reference: AttributeReference = {
    type: "attribute",
    kind,
    path: p.map((a) => a.id),
  };
  return {
    title: attribute.name,
    value: buildTreeNodeValue(reference),
    label: getNodeLabel(attribute, kind),
    children: Attributes.getChildAttributes(
      attribute,
      resolveResult.definitions,
    ).map((child) =>
      buildTreeDataNode(child, kind, p, resolveResult.definitions),
    ),
  };
}

function buildTreeData(
  mappingDescription: MappingDescription,
  isTarget: boolean,
): TreeSelectProps["treeData"] {
  const schema = isTarget
    ? mappingDescription.target
    : mappingDescription.source;
  return [
    ...(isTarget
      ? []
      : [
          {
            title: "Constants",
            key: "constants",
            value: "constants",
            checkable: false,
            children: mappingDescription.constants.map((constant) =>
              buildConstantTreeDataNode(constant),
            ),
          },
        ]),
    {
      title: "Headers",
      key: "headers",
      value: "headers",
      checkable: false,
      children: schema.headers.map((header) =>
        buildTreeDataNode(header, "header", [], []),
      ),
    },
    {
      title: "Properties",
      key: "properties",
      value: "properties",
      checkable: false,
      children: schema.properties.map((property) =>
        buildTreeDataNode(property, "property", [], []),
      ),
    },
    {
      title: "Body",
      key: "body",
      value: "body",
      checkable: false,
      children: schema.body
        ? Attributes.getChildAttributes(
            Attributes.buildAttribute("", "", schema.body),
            DataTypes.getTypeDefinitions(schema.body),
          ).map((a) =>
            buildTreeDataNode(
              a,
              "body",
              [],
              DataTypes.getTypeDefinitions(schema.body!),
            ),
          )
        : undefined,
    },
  ];
}

export const InlineElementReferencesEdit: React.FC<
  InlineElementReferencesEditProps
> = ({ mappingDescription, readonly, isTarget, values, onSubmit }) => {
  const [treeData, setTreeData] = React.useState<TreeSelectProps["treeData"]>(
    [],
  );

  useEffect(() => {
    setTreeData(buildTreeData(mappingDescription, isTarget));
  }, [mappingDescription, isTarget]);

  const view = (
    <Flex wrap gap={"small"}>
      {values.map((reference, index) => (
        <ElementReference
          key={index}
          isTarget={isTarget}
          closable={!readonly}
          mapping={mappingDescription}
          reference={reference}
          onClose={() => {
            const references = values
              .slice(0, index)
              .concat(values.slice(index + 1));
            onSubmit?.(references);
          }}
        />
      ))}
    </Flex>
  );

  return readonly ? (
    view
  ) : (
    <InlineEdit<{ values: { value: string }[] }>
      values={{ values: values.map((v) => ({ value: buildTreeNodeValue(v) })) }}
      editor={
        <TreeSelectEdit<Key>
          selectProps={{
            className: "mapper-element-reference-select",
            multiple: true,
            treeData,
            treeCheckable: true,
            treeCheckStrictly: true,
            allowClear: true,
            treeNodeLabelProp: "label",
            defaultOpen: true,
          }}
          name={"values"}
        />
      }
      viewer={view}
      onSubmit={({ values }) => {
        const references = values.map((i) => parseTreeNodeValue(i.value));
        onSubmit?.(references);
      }}
    />
  );
};
