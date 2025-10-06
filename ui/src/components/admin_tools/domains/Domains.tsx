import Spin from "antd/lib/spin";
import Title from "antd/lib/typography/Title";
import DomainsTable from "./DomainsTable";
import { useDomains } from "../../../hooks/useDomains";
import commonStyles from "../CommonStyle.module.css";
import React from "react";
import { Flex } from "antd";
import { Icon } from "../../../IconProvider.tsx";

export const Domains: React.FC = () => {
  const { domains, isLoading } = useDomains();

  return (
    <Flex vertical className={commonStyles["container"]}>
      <Title level={4} className={commonStyles["title"]}>
        <Icon name="appstore" className={commonStyles["icon"]} />
        Domains
      </Title>
      {isLoading ? (
        <Spin size="large" />
      ) : (
        <DomainsTable domains={domains || []} isLoading={isLoading} />
      )}
    </Flex>
  );
};
