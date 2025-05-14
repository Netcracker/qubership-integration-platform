import React, { ReactNode, useEffect, useState } from "react";
import { Tag } from "antd";
import { ExecutionStatus } from "../../api/apiTypes";
import { formatSnakeCased } from "../../misc/format-utils.ts";
import { TagProps } from "antd/es/tag";
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  ExclamationCircleOutlined,
  SyncOutlined,
} from "@ant-design/icons";

type SessionStatusProps = {
  status: ExecutionStatus;
  withIcon?: boolean;
};
import { createStyles } from "antd-style";

const useStyle = createStyles(({ css }) => {
  return {
    sessionStatus: css`
      display: flex;
      flex-direction: row;
      max-width: fit-content;
      span:last-child {
        min-width: 0;
        text-overflow: ellipsis;
        overflow: hidden;
      }
    `,
  };
});

export const SessionStatus: React.FC<SessionStatusProps> = ({
  status,
  withIcon,
}) => {
  const [color, setColor] = useState<TagProps["color"] | undefined>(undefined);

  useEffect(() => {
    setColor(getStatusColor());
  }, [status]);

  const { styles } = useStyle();

  const getStatusColor = (): TagProps["color"] => {
    switch (status) {
      case ExecutionStatus.IN_PROGRESS:
        return "processing";
      case ExecutionStatus.COMPLETED_NORMALLY:
        return "success";
      case ExecutionStatus.COMPLETED_WITH_ERRORS:
        return "error";
      case ExecutionStatus.COMPLETED_WITH_WARNINGS:
        return "warning";
      case ExecutionStatus.CANCELLED_OR_UNKNOWN:
        return "cyan";
      default:
        return "default";
    }
  };

  const getIcon = (): ReactNode | undefined => {
    switch (status) {
      case ExecutionStatus.IN_PROGRESS:
        return <SyncOutlined spin />;
      case ExecutionStatus.COMPLETED_NORMALLY:
        return <CheckCircleOutlined />;
      case ExecutionStatus.COMPLETED_WITH_ERRORS:
        return <CloseCircleOutlined />;
      case ExecutionStatus.COMPLETED_WITH_WARNINGS:
        return <ExclamationCircleOutlined />;
      case ExecutionStatus.CANCELLED_OR_UNKNOWN:
        return undefined;
      default:
        return undefined;
    }
  };

  return (
    <Tag
      bordered
      icon={withIcon ? getIcon() : undefined}
      color={color}
      className={styles.sessionStatus}
    >
      {formatSnakeCased(status)}
    </Tag>
  );
};
