import DomainsTable from "./DomainsTable";
import layoutStyles from "./DomainsTablesLayout.module.css";
import { useDomains } from "../../../hooks/useDomains";
import commonStyles from "../CommonStyle.module.css";
import React from "react";
import { Flex } from "antd";

export const Domains: React.FC = () => {
  const { domains, isLoading, refresh } = useDomains();

  return (
    <Flex
      vertical
      className={`${commonStyles["container"]} ${layoutStyles.pageRoot}`}
    >
      <DomainsTable
        onRefresh={refresh}
        domains={domains}
        isLoading={isLoading}
      />
    </Flex>
  );
};
