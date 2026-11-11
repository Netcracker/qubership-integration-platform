import React from "react";
import { Button, Flex, Tooltip, Typography } from "antd";
import { OverridableIcon } from "../../../icons/IconProvider.tsx";
import { MaasPageHeaderProps } from "./types.ts";
import styles from "./Maas.module.css";

const { Title } = Typography;

export const MaasPageHeader: React.FC<MaasPageHeaderProps> = ({
  title,
  exportInProgress,
  isFormValid,
  onExport,
}) => {
  return (
    <Flex
      vertical={false}
      justify="space-between"
      align="center"
      className={styles["header"]}
    >
      <Title level={4} className={styles["titleHeader"]}>
        {title}
      </Title>
      <Tooltip title="Explore declarative file for deployer">
        <span>
          <Button
            aria-label="Export declarative file"
            size="small"
            icon={<OverridableIcon name="cloudDownload" />}
            loading={exportInProgress}
            disabled={exportInProgress || !isFormValid}
            onClick={onExport}
          />
        </span>
      </Tooltip>
    </Flex>
  );
};
