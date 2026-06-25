import React from "react";
import { Diagnostic } from "./Diagnostic";
import { Flex, Typography } from "antd";
import { OverridableIcon } from "../../icons/IconProvider";
import styles from "./DevTools.module.css";

const { Title } = Typography;

export const DiagnosticValidationPage: React.FC = () => {
  return (
    <Flex vertical className={styles["container"]}>
      <Flex vertical={false}>
        <Title level={4} className={styles["title"]}>
          <OverridableIcon name="barChart" className={styles["icon"]} />
          Diagnostic
        </Title>
      </Flex>
      <Diagnostic />
    </Flex>
  );
};
