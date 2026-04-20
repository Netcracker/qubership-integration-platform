import React, { useState } from "react";
import { Button, Popover } from "antd";
import { ExclamationCircleFilled } from "@ant-design/icons";
import { RuntimeState } from "../../api/apiTypes.ts";
import { capitalize } from "../../misc/format-utils.ts";
import { useModalsContext } from "../../Modals.tsx";
import { ErrorDetails } from "../modal/ErrorDetails.tsx";
import { DeploymentStatusTag } from "./DeploymentStatusTag.tsx";
import styles from "./DeploymentRuntimeState.module.css";

type DeploymentStateTagProps = {
  name: string;
  service: string;
  timestamp: number;
  runtimeState: RuntimeState;
};

export const DeploymentStateTag: React.FC<DeploymentStateTagProps> = ({
  name,
  service,
  timestamp,
  runtimeState,
}) => {
  const { showModal } = useModalsContext();
  const [popoverOpen, setPopoverOpen] = useState(false);

  const hasDetails = Boolean(runtimeState.error || runtimeState.stacktrace);
  const label = capitalize(name);

  const openDetails = () => {
    if (!hasDetails) return;
    setPopoverOpen(false);
    showModal({
      component: (
        <ErrorDetails
          service={service}
          timestamp={timestamp}
          message={runtimeState.error}
          stacktrace={runtimeState.stacktrace}
          status={runtimeState.status}
        />
      ),
    });
  };

  const tag = (
    <DeploymentStatusTag
      status={runtimeState.status}
      text={label}
      style={hasDetails ? { cursor: "pointer" } : undefined}
      onClick={hasDetails ? openDetails : undefined}
    />
  );

  if (!hasDetails) {
    return tag;
  }

  const popoverContent = (
    <div className={styles.popover}>
      <div className={styles.popoverSectionLabel}>
        {runtimeState.error ? "Error" : "Stack trace"}
      </div>
      <div className={styles.popoverMessage}>
        {runtimeState.error || runtimeState.stacktrace}
      </div>
      <div className={styles.popoverActions}>
        <Button
          type="link"
          size="small"
          className={styles.popoverLink}
          icon={<ExclamationCircleFilled />}
          onClick={openDetails}
        >
          Show details
        </Button>
      </div>
    </div>
  );

  return (
    <Popover
      title={label}
      content={popoverContent}
      trigger={["hover", "focus"]}
      placement="topLeft"
      mouseEnterDelay={0.15}
      mouseLeaveDelay={0.1}
      align={{ offset: [0, 0] }}
      open={popoverOpen}
      onOpenChange={setPopoverOpen}
    >
      {tag}
    </Popover>
  );
};
