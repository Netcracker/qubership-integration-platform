import { ActionLog } from "../../api/apiTypes.ts";
import React, { ReactNode, useMemo } from "react";
import {
  capitalize,
  formatSnakeCased,
  formatTimestamp,
} from "../../misc/format-utils.ts";
import { DetailsSidebar, IdText, renderOptional } from "../DetailsSidebar.tsx";

export type ActionLogSidebarProps = {
  value: ActionLog | null | undefined;
  open: boolean;
  onClose: () => void;
  entityLinkRenderer: (v: ActionLog) => ReactNode;
  parentLinkRenderer: (v: ActionLog) => ReactNode;
};

export const ActionLogSidebar: React.FC<ActionLogSidebarProps> = ({
  value,
  open,
  onClose,
  entityLinkRenderer,
  parentLinkRenderer,
}): ReactNode => {
  const sections = useMemo(
    () => [
      {
        key: "general-info",
        title: "general info",
        items: [
          {
            label: "Action Time",
            value: renderOptional(value?.actionTime, formatTimestamp),
          },
          {
            label: "Initiator",
            value: renderOptional(value?.username),
          },
          {
            label: "Operation",
            value: renderOptional(value?.operation, (v) =>
              formatSnakeCased(capitalize(v)),
            ),
          },
          {
            label: "Entity ID",
            value: <IdText value={value?.entityId} />,
          },
          {
            label: "Entity Type",
            value: renderOptional(value?.entityType, (v) =>
              formatSnakeCased(capitalize(v)),
            ),
          },
          {
            label: "Entity Name",
            value: renderOptional(value, entityLinkRenderer),
          },
          {
            label: "Parent ID",
            value: <IdText value={value?.parentId} />,
          },
          {
            label: "Parent Name",
            value: renderOptional(value, parentLinkRenderer),
          },
          {
            label: "Request ID",
            value: <IdText value={value?.requestId} />,
          },
        ],
      },
    ],
    [value, entityLinkRenderer, parentLinkRenderer],
  );

  return (
    <DetailsSidebar
      title={"Action Details"}
      open={open}
      onClose={onClose}
      sections={sections}
    />
  );
};
