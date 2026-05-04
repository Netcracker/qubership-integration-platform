import DomainsTable from "./DomainsTable";
import { useDomains } from "../../../hooks/useDomains";
import commonStyles from "../CommonStyle.module.css";
import React from "react";
import { Flex } from "antd";

export const Domains: React.FC = () => {
  const { domains, isLoading } = useDomains();

  return (
    <Flex vertical className={commonStyles["container"]}>
      <DomainsTable domains={domains} isLoading={isLoading} />
    </Flex>
  );
};
