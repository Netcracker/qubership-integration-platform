import React, { useEffect, useState } from "react";
import { Session, SessionElement } from "../../api/apiTypes.ts";
import { Button, Flex, Modal, Tabs, TabsProps, Tag } from "antd";
import { LeftOutlined, LinkOutlined, RightOutlined } from "@ant-design/icons";
import { useModalContext } from "../../ModalContextProvider.tsx";
import { PLACEHOLDER } from "../../misc/format-utils.ts";
import { SessionStatus } from "../sessions/SessionStatus.tsx";
import { SessionElementKVChanges } from "../sessions/SessionElementKVChanges.tsx";
import { SessionElementBodyView } from "../sessions/SessionElementBodyView.tsx";

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

function traverseElementsDepthFirst(
  elements: SessionElement[] | undefined,
  fn: (element: SessionElement) => void,
): void {
  for (const element of elements ?? []) {
    fn(element);
    traverseElementsDepthFirst(element.children, fn);
  }
}

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

export const SessionElementDetails: React.FC<SessionElementDetailsProps> = ({
  session,
  elementId,
}) => {
  const { closeContainingModal } = useModalContext();
  const [element, setElement] = useState<SessionElement | undefined>();
  const [elementOrderMap, setElementOrderMap] =
    useState<SessionElementOrderMap>(new Map());

  useEffect(() => {
    setElementOrderMap(buildElementOrderMap(session.sessionElements));
  }, [session]);

  useEffect(() => {
    setElement(elementOrderMap.get(elementId)?.current);
  }, [elementId, elementOrderMap]);

  const tabItems: TabsProps["items"] = [
    {
      key: "body",
      label: "Body",
      children: (
        <Flex vertical={false} gap={8} style={{ height: "100%" }}>
          <SessionElementBodyView
            style={{ flexGrow: 1, flexShrink: 1 }}
            title="Body Before"
            headers={element?.headersBefore ?? {}}
            body={element?.bodyBefore ?? ""}
          />
          <SessionElementBodyView
            style={{ flexGrow: 1, flexShrink: 1 }}
            title="Body After"
            headers={element?.headersAfter ?? {}}
            body={element?.bodyAfter ?? ""}
          />
        </Flex>
      ),
    },
    {
      key: "headers",
      label: "Headers",
      children: (
        <SessionElementKVChanges
          before={element?.headersBefore}
          after={element?.headersAfter}
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
          style={{ height: "100%" }}
        />
      ),
    },
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
