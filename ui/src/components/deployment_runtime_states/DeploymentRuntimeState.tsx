import { Button, Popover, theme } from "antd";
import { DeploymentStatus, RuntimeState } from "../../api/apiTypes.ts";
import { useModalsContext } from "../../Modals.tsx";
import React, { useMemo, useState } from "react";
import { ExclamationCircleFilled, InfoCircleOutlined } from "@ant-design/icons";
import { ErrorDetails } from "../modal/ErrorDetails.tsx";
import { getDeploymentStatusTone } from "../../theme/semanticColors";
import { capitalize } from "../../misc/format-utils.ts";
import styles from "./DeploymentRuntimeState.module.css";

type DeploymentRuntimeStateProps = {
  name: string;
  service: string;
  timestamp: number;
  runtimeState: RuntimeState;
};

const PULSING_STATUSES = new Set<string>([
  DeploymentStatus.FAILED,
  DeploymentStatus.PROCESSING,
]);

export const DeploymentRuntimeState: React.FC<DeploymentRuntimeStateProps> = ({
  name,
  service,
  timestamp,
  runtimeState,
}) => {
  const { showModal } = useModalsContext();
  const { token } = theme.useToken();
  const [popoverOpen, setPopoverOpen] = useState(false);

  const hasDetails = Boolean(runtimeState.error || runtimeState.stacktrace);
  const status = runtimeState.status;
  const shouldPulse = PULSING_STATUSES.has(status) || hasDetails;

  const tone = useMemo(
    () => getDeploymentStatusTone(status, token),
    [status, token],
  );

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
          status={status}
        />
      ),
    });
  };

  const pillStyle: React.CSSProperties & Record<string, string> = {
    "--dot-color": tone.accent,
    "--pill-bg": tone.bg,
    "--pill-border": tone.border,
    "--pill-border-strong": tone.borderHover,
    "--pill-fg": tone.text,
    "--pill-focus": tone.borderHover,
  };

  const pill = (
    <span
      className={`${styles.pill} ${hasDetails ? styles.clickable : ""}`}
      style={pillStyle}
      onClick={hasDetails ? openDetails : undefined}
      onKeyDown={
        hasDetails
          ? (event) => {
              if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                openDetails();
              }
            }
          : undefined
      }
      role={hasDetails ? "button" : undefined}
      tabIndex={hasDetails ? 0 : undefined}
      aria-label={
        hasDetails
          ? `${label} — click to view error details`
          : `Status: ${label}`
      }
    >
      <span
        className={`${styles.dot} ${shouldPulse ? styles.pulse : ""}`}
        aria-hidden
      />
      <span className={styles.label}>{label}</span>
      {hasDetails ? (
        <InfoCircleOutlined className={styles.infoIcon} aria-hidden />
      ) : null}
    </span>
  );

  if (!hasDetails) {
    return pill;
  }

  const popoverTitle = (
    <div className={styles.popoverHeader} style={pillStyle}>
      <span className={styles.popoverHeaderDot} aria-hidden />
      <span>{label}</span>
    </div>
  );

  const popoverContent = (
    <div className={styles.popover}>
      <div className={styles.popoverSectionLabel}>
        {runtimeState.error ? "Error" : "Stack trace"}
      </div>
      <div className={styles.popoverMessage}>
        {runtimeState.error || runtimeState.stacktrace}
      </div>
      <div className={styles.popoverActions} style={pillStyle}>
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
      title={popoverTitle}
      content={popoverContent}
      trigger={["hover", "focus"]}
      placement="topLeft"
      mouseEnterDelay={0.15}
      mouseLeaveDelay={0.1}
      align={{ offset: [0, 2] }}
      open={popoverOpen}
      onOpenChange={setPopoverOpen}
    >
      {pill}
    </Popover>
  );
};
