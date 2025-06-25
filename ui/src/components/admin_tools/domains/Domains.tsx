import Spin from "antd/lib/spin";
import Title from "antd/lib/typography/Title";
import DomainsTable from "./DomainsTable";
import { useDomains } from "../../../hooks/useDomains";
import commonStyles from '../CommonStyle.module.css';
import { AppstoreOutlined } from "@ant-design/icons";
import React from "react";

const Domains: React.FC = () => {
    const { domains, isLoading } = useDomains();

    return (
        <div className={commonStyles["container"]}>
        <Title level={4} className={commonStyles["title"]}>
          <AppstoreOutlined className={commonStyles["icon"]} />
            Domains
        </Title>
        <div>
          {isLoading ? (
            <Spin size="large" />
          ) : (
            <DomainsTable domains={domains || []} isLoading={isLoading} />
          )}
        </div>
      </div>
    );
  };

  export default Domains;
