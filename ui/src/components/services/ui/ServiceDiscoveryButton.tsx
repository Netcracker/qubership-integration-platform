import { Button, Progress, Space, Tooltip } from "antd";
import { OverridableIcon } from "../../../icons/IconProvider";
import { useNotificationService } from "../../../hooks/useNotificationService";
import { api } from "../../../api/api";
import React, { useCallback, useEffect, useRef, useState } from "react";
import { DiscoveryResponse } from "../../../api/apiTypes";

type ProgressStatus = "normal" | "success" | "exception";

type DiscoveryStatus = {
  status: ProgressStatus;
  progress: number;
};

type ServiceDiscoveryButtonProps = {
  onSystemsDiscovered: (systemIds: string[]) => void;
};

export const ServiceDiscoveryButton: React.FC<ServiceDiscoveryButtonProps> = ({
  onSystemsDiscovered,
}) => {
  const notificationService = useNotificationService();
  const pollingTimerRef = useRef<ReturnType<typeof setTimeout>>();
  const [inProgress, setInProgress] = useState<boolean>(false);
  const [status, setStatus] = useState<DiscoveryStatus>({
    status: "normal",
    progress: 0,
  });

  useEffect(() => {
    const init = async () => {
      const result: number = await api.isAutodiscoveryInProgress();
      if (result !== 100) {
        updateProgress(result);
        await waitDiscoveryProgress();
      }
    };
    void init();
    return () => clearTimeout(pollingTimerRef.current);
  }, []);

  useEffect(() => {
    if (status.progress === 100) {
      setInProgress(false);
    } else if (status.progress > 0) {
      setInProgress(true);
    }
  }, [status.progress]);

  const updateProgress = useCallback(
    (progress: number, status: ProgressStatus = "normal") => {
      setStatus({
        status: status,
        progress: progress,
      });
    },
    [setStatus],
  );

  const updateStatus = useCallback(
    (status: ProgressStatus) => {
      setStatus({
        status: status,
        progress: 100,
      });
    },
    [setStatus],
  );

  const showNotification = useCallback(
    (message: string, isWarning: boolean) => {
      if (isWarning) {
        notificationService.warning(message);
      } else {
        notificationService.info(message);
      }
    },
    [notificationService],
  );

  const showErrorNotification = useCallback(
    (error: unknown, message = "Autodiscovery process was failed") => {
      notificationService.errorWithDetails(message, "", error);
    },
    [notificationService],
  );

  const resultDiscovery = useCallback(
    (response: DiscoveryResponse) => {
      updateStatus("success");

      let isWarning: boolean = false;
      let endMessage = "Autodiscovery process completed";
      if (response.errorMessages.length) {
        isWarning = true;
        endMessage += " with following errors:\n";
        response.errorMessages.forEach((error) => {
          endMessage += `${error.serviceName}: ${error.message}\n`;
        });
      } else endMessage += ".";

      if (
        response.discoveredSystemIds.length +
          response.discoveredGroupIds.length +
          response.discoveredSpecificationIds.length ===
        0
      ) {
        endMessage = endMessage + " There are no specification updates.";
      }

      showNotification(endMessage, isWarning);

      onSystemsDiscovered([
        ...response.discoveredSystemIds,
        ...response.updatedSystemsIds,
      ]);
    },
    [showNotification, updateStatus, onSystemsDiscovered],
  );

  const waitDiscoveryProgress = useCallback(async () => {
    try {
      const result: number = await api.isAutodiscoveryInProgress();

      if (result === 100) {
        setInProgress(false);
        await api
          .getAutodiscoveryResult()
          .then((result) => {
            resultDiscovery(result);
          })
          .catch((error) => {
            updateStatus("exception");
            showErrorNotification(error);
          });
      } else {
        updateProgress(result);
        pollingTimerRef.current = setTimeout(
          () => void waitDiscoveryProgress(),
          1000,
        );
      }
    } catch (error) {
      updateStatus("exception");
      showErrorNotification(error);
    }
  }, [resultDiscovery, updateProgress, updateStatus, showErrorNotification]);

  const startDiscovery = useCallback(async () => {
    setInProgress(true);
    updateProgress(0);
    await api
      .runServiceDiscovery()
      .then(async () => {
        notificationService.info("Autodiscovery started");
        await waitDiscoveryProgress();
      })
      .catch((error) => {
        updateStatus("exception");
        showErrorNotification(error, "Start of Autodiscovery was failed");
      });
  }, [
    notificationService,
    updateProgress,
    updateStatus,
    waitDiscoveryProgress,
    showErrorNotification,
  ]);

  return (
    <Space>
      {inProgress ? (
        <Progress
          percent={status.progress}
          status={status.status}
          size={20}
          type="circle"
          format={(number) => `Autodiscovery started: ${number}%`}
        />
      ) : (
        <Tooltip title="Service Discovery">
          <Button
            disabled={inProgress}
            icon={<OverridableIcon name="cloudSync" />}
            onClick={() => void startDiscovery()}
          />
        </Tooltip>
      )}
    </Space>
  );
};
