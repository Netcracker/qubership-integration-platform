import React, { useEffect, useState } from "react";
import { Session, SessionElement } from "../../api/apiTypes.ts";
import { Button, Flex, message, Modal, Tabs, TabsProps, Tag } from "antd";
import { LeftOutlined, LinkOutlined, RightOutlined } from "@ant-design/icons";
import { useModalContext } from "../../ModalContextProvider.tsx";
import { PLACEHOLDER } from "../../misc/format-utils.ts";
import { SessionStatus } from "../sessions/SessionStatus.tsx";
import {
  ColumnName,
  KVChangesTableItem,
  SessionElementKVChanges,
} from "../sessions/SessionElementKVChanges.tsx";
import { copyToClipboard } from "../../misc/clipboard-util.ts";
import { SessionElementBodyChangesView } from "../sessions/SessionElementBodyChangesView.tsx";
import { traverseElementsDepthFirst } from "../../misc/tree-utils.ts";

type SessionElementDetailsProps = {
  session: Session;
  elementId: string;
};

type SessionElementOrderMap = Map<
  string,
  {
    current: SessionElement;
    previous: SessionElement | undefined;
    next: SessionElement | undefined;
  }
>;

function buildElementOrderMap(
  elements: SessionElement[] | undefined,
): SessionElementOrderMap {
  const elementList: SessionElement[] = [];
  traverseElementsDepthFirst(elements, (element) => elementList.push(element));
  return new Map(
    elementList.map((element, index) => {
      const previous = elementList[index - 1];
      const next = elementList[index + 1];
      return [element.elementId, { current: element, previous, next }];
    }),
  );
}

function getTextToCopy<ValueType>(
  item: KVChangesTableItem<ValueType>,
  column: ColumnName,
  typeTextGetter?: (v: ValueType | undefined) => string | undefined,
  valueTextGetter?: (v: ValueType | undefined) => string | undefined,
): string | undefined {
  switch (column) {
    case "name":
      return item.name;
    case "typeBefore":
      return typeTextGetter ? typeTextGetter(item.before) : `${item.before}`;
    case "typeAfter":
      return typeTextGetter ? typeTextGetter(item.after) : `${item.after}`;
    case "valueBefore":
      return valueTextGetter ? valueTextGetter(item.before) : `${item.before}`;
    case "valueAfter":
      return valueTextGetter ? valueTextGetter(item.after) : `${item.after}`;
  }
}

export const SessionElementDetails: React.FC<SessionElementDetailsProps> = ({
  session,
  elementId,
}) => {
  const { closeContainingModal } = useModalContext();
  const [element, setElement] = useState<SessionElement | undefined>();
  const [elementOrderMap, setElementOrderMap] =
    useState<SessionElementOrderMap>(new Map());
  const [tabItems, setTabItems] = useState<TabsProps["items"]>([]);
  const [messageApi, contextHolder] = message.useMessage();

  useEffect(() => {
    setElementOrderMap(buildElementOrderMap(session.sessionElements));
  }, [session]);

  useEffect(() => {
    setElement(elementOrderMap.get(elementId)?.current);
  }, [elementId, elementOrderMap]);

  useEffect(() => {
    setTabItems(buildTabItems());
  }, [element]);

  const copyItemFieldToClipboard = async <ValueType = any,>(
    item: KVChangesTableItem<ValueType>,
    column: ColumnName,
    typeTextGetter?: (v: ValueType | undefined) => string | undefined,
    valueTextGetter?: (v: ValueType | undefined) => string | undefined,
  ): Promise<void> => {
    const text = getTextToCopy(item, column, typeTextGetter, valueTextGetter);
    if (text) {
      return copyToClipboard(text).then(() => {
        messageApi.info("Copied to clipboard");
        return;
      });
    }
  };

  const buildTabItems = (): TabsProps["items"] => [
    {
      key: "body",
      label: "Body",
      children: (
        <SessionElementBodyChangesView
          style={{ height:"100%" }}
          headersBefore={element?.headersBefore}
          headersAfter={element?.headersAfter}
          bodyBefore={element?.bodyBefore}
          bodyAfter={element?.bodyAfter}
        />
      ),
    },
    {
      key: "headers",
      label: "Headers",
      children: (
        <SessionElementKVChanges
          before={element?.headersBefore}
          after={element?.headersAfter}
          onColumnClick={(item, column) =>
            copyItemFieldToClipboard(item, column)
          }
          style={{ height: "100%" }}
        />
      ),
    },
    {
      key: "properties",
      label: "Exchange properties",
      children: (
        <SessionElementKVChanges
          addTypeColumns
          before={element?.propertiesBefore}
          after={element?.propertiesAfter}
          comparator={(p1, p2) =>
            (p1?.type.localeCompare(p2?.type ?? "") ||
              p1?.value.localeCompare(p2?.value ?? "")) ??
            0
          }
          typeRenderer={(property) => property.type}
          valueRenderer={(property) => property.value}
          onColumnClick={(item, column) =>
            copyItemFieldToClipboard(
              item,
              column,
              (v) => v?.type,
              (v) => v?.value,
            )
          }
          style={{ height: "100%" }}
        />
      ),
    },
    {
      key: "context",
      label: "Technical context",
      children: (
        <SessionElementKVChanges
          before={element?.contextBefore}
          after={element?.contextAfter}
          onColumnClick={(item, column) =>
            copyItemFieldToClipboard(item, column)
          }
          style={{ height: "100%" }}
        />
      ),
    },
    ...(element?.exceptionInfo
      ? [
          {
            key: "error",
            label: "Errors",
            children: (
              <Flex vertical style={{ height: "100%" }}>
                <h5>Message</h5>
                <div>{element.exceptionInfo.message}</div>
                <h5>Stacktrace</h5>
                <div
                  style={{
                    flexGrow: 1,
                    flexShrink: 1,
                    minHeight: 0,
                    overflow: "auto",
                    height: 0,
                  }}
                >
                  {element.exceptionInfo.stackTrace}
                </div>
              </Flex>
            ),
          },
        ]
      : []),
  ];

  return (
    <Modal
      title={
        <>
          <span style={{ marginRight: 8 }}>{element?.elementName}</span>
          <LinkOutlined
            onClick={() =>
              window.open(
                `/chains/${session.chainId}/graph/${element?.chainElementId}`,
                "_blank",
              )
            }
          />
        </>
      }
      centered
      open={true}
      onCancel={async () => closeContainingModal()}
      width={"90%"}
      footer={null}
    >
      {contextHolder}
      <Flex vertical style={{ height: "80vh", paddingTop: 8 }}>
        <Flex vertical={false} align="center" justify="space-between">
          <Flex vertical={false} gap={8}>
            <Button
              disabled={
                !element || !elementOrderMap.get(element.elementId)?.previous
              }
              iconPosition="start"
              icon={<LeftOutlined />}
              onClick={() =>
                setElement(
                  elementOrderMap.get(element?.elementId ?? "")?.previous,
                )
              }
            >
              Previous
            </Button>
            <Button
              disabled={
                !element || !elementOrderMap.get(element.elementId)?.next
              }
              iconPosition="end"
              icon={<RightOutlined />}
              onClick={() =>
                setElement(elementOrderMap.get(element?.elementId ?? "")?.next)
              }
            >
              Next
            </Button>
          </Flex>
          <Flex vertical={false} gap={8} align="center">
            {element?.executionStatus ? (
              <SessionStatus
                status={element?.executionStatus}
                suffix={`in ${element?.duration} ms`}
              />
            ) : (
              <span>{PLACEHOLDER}</span>
            )}
            <Tag>{session.snapshotName}</Tag>
          </Flex>
        </Flex>
        <Tabs items={tabItems} style={{ flexGrow: 1 }} className="flex-tabs" />
      </Flex>
    </Modal>
  );
};
