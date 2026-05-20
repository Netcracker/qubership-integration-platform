import { Change, ChangedSide } from "./compare/types.ts";
import {
  Chain,
  Connection,
  Element,
  EntityLabel,
} from "../../../api/apiTypes.ts";
import React, { useContext, useEffect, useState } from "react";
import { Descriptions, Space } from "antd";
import { EntityLabels } from "../../labels/EntityLabels.tsx";
import { ElementSchemasContext } from "./ElementSchemasProvider.tsx";
import { JSONSchema7 } from "json-schema";
import styles from "./ChangedEntityView.module.css";
import { traverseElementsDepthFirst } from "../../../misc/tree-utils.ts";

export function getElement(
  elementId: string,
  chain: Chain,
): Element | undefined {
  let element: Element | undefined = undefined;
  traverseElementsDepthFirst(chain.elements, (e) => {
    if (e.id === elementId) {
      element = e;
    }
  });
  return element;
}

export const LinkToChain: React.FC<{ chain?: Chain }> = ({
  chain,
}): React.ReactNode => {
  return chain ? (
    <a href={`/chains/${chain.id}`} target="_blank" rel="noreferrer">
      {chain.name}
    </a>
  ) : null;
};

export const LinkToElement: React.FC<{ element?: Element }> = ({
  element,
}): React.ReactNode => {
  return element ? (
    <a
      href={`/chains/${element.chainId}/graph/${element.id}`}
      target="_blank"
      rel="noreferrer"
    >
      {element.name}
    </a>
  ) : (
    <></>
  );
};

export function resolveChainPropertyTitle(name: string): string {
  switch (name) {
    case "labels":
      return "Labels";
    case "description":
      return "Description";
    case "deployAction":
      return "Deploy action";
    case "businessDescription":
      return "Business description";
    case "assumptions":
      return "Assumptions";
    case "outOfScope":
      return "Out of scope";
    case "overriddenByChainId":
      return "Overridden by chain ID";
    case "overriddenByChainName":
      return "Overridden by chain name";
    case "overridesChainId":
      return "Overrides chain ID";
    case "overridesChainName":
      return "Overrides chain name";
    case "name":
      return "Name";
    default:
      return name;
  }
}

export const ChangedValue: React.FC<{ value: unknown }> = ({
  value,
}): React.ReactNode => {
  return <div className={styles["changed-value"]}>{JSON.stringify(value)}</div>;
};

export const ChainProperty: React.FC<{ name: string; value: unknown }> = ({
  name,
  value,
}): React.ReactNode => {
  return (
    <Descriptions
      size="small"
      layout="horizontal"
      colon={true}
      column={1}
      items={[
        {
          key: "name",
          label: "Name",
          children: resolveChainPropertyTitle(name),
        },
        {
          key: "value",
          label: "Value",
          children:
            name === "labels" ? (
              <EntityLabels labels={(value ?? []) as EntityLabel[]} />
            ) : (
              <ChangedValue value={value} />
            ),
        },
      ]}
    />
  );
};

export function getElementPropertyTitle(
  schema: JSONSchema7,
  name: string,
): string {
  switch (name) {
    case "name":
      return "Name";
    case "description":
      return "Description";
    case "type":
      return "Type";
    case "swimlaneId":
      return "Swimlane";
    case "parentElementId":
      return "Parent";
    default:
      return getElementPropertyTitleFromSchema(schema, name) ?? name;
  }
}

export function getElementPropertyTitleFromSchema(
  schema: JSONSchema7,
  name: string,
): string | undefined {
  const directPropertySchema = schema.properties?.[name];
  if (directPropertySchema && typeof directPropertySchema === "object") {
    return directPropertySchema.title;
  } else {
    return [
      schema.allOf,
      schema.anyOf,
      schema.oneOf,
      [schema.then, schema.else].filter((s) => !!s),
    ]
      .filter((s) => !!s)
      .flatMap((s) => s)
      .filter((s) => !!s && typeof s === "object")
      .map((s) => getElementPropertyTitleFromSchema(s, name))
      .find((n) => !!n);
  }
}

export const ElementProperty: React.FC<{
  element?: Element;
  chain: Chain;
  name: string;
  value: unknown;
}> = ({ chain, element, name, value }): React.ReactNode => {
  const { getSchema } = useContext(ElementSchemasContext);
  const [title, setTitle] = useState<string>(name);

  useEffect(() => {
    setTitle(() => {
      if (element) {
        const schema = getSchema(element.type)?.properties?.properties;
        return schema && typeof schema === "object"
          ? getElementPropertyTitle(schema, name)
          : name;
      } else {
        return name;
      }
    });
  }, [element, getSchema, name]);
  return (
    <Descriptions
      size="small"
      layout="horizontal"
      colon={true}
      column={1}
      items={[
        ...(element
          ? [
              {
                key: "element",
                label: "Element",
                children: <LinkToElement element={element} />,
              },
            ]
          : []),
        {
          key: "name",
          label: "Name",
          children: title,
        },
        {
          key: "value",
          label: "Value",
          children:
            name === "labels" ? (
              <EntityLabels labels={(value ?? []) as EntityLabel[]} />
            ) : name === "parentElementId" || name === "swimlaneId" ? (
              <LinkToElement element={getElement(value as string, chain)} />
            ) : (
              <ChangedValue value={value} />
            ),
        },
      ]}
    />
  );
};

export const ConnectionView: React.FC<{
  connection: Connection;
  chain: Chain;
}> = ({ connection, chain }) => {
  return (
    <Space>
      <LinkToElement element={getElement(connection.from, chain)} />
      →
      <LinkToElement element={getElement(connection.to, chain)} />
    </Space>
  );
};

export type ChangedEntityViewProps = {
  change: Change;
  side: ChangedSide;
  chain: Chain;
};

export const ChangedEntityView: React.FC<ChangedEntityViewProps> = ({
  side,
  change,
  chain,
}): React.ReactNode => {
  if (!change[side]) {
    return "N/A";
  }
  switch (change.kind) {
    case "element":
      return <LinkToElement element={change[side]} />;
    case "chain-property":
      return (
        <ChainProperty name={change[side].name} value={change[side].value} />
      );
    case "element-property":
      return (
        <ElementProperty
          element={getElement(change[side].entityId, chain)}
          name={change[side].name}
          value={change[side].value}
          chain={chain}
        />
      );
    case "connection":
      return <ConnectionView connection={change[side]} chain={chain} />;
    default:
      return <></>;
  }
};
