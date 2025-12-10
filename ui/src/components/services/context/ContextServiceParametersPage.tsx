import { Breadcrumb, Tabs } from "antd";
import { useParams } from "react-router";
import { formatTimestamp } from "../../../misc/format-utils";
import styles from "../Services.module.css";
import {
  ServiceParametersPageHeader,
  ServiceParametersPageLayout,
  sidePadding,
} from "../ServiceParametersPage";
import {
  ServiceNameBreadcrumbItem,
  ServiceTypeBreadcrumbItem,
} from "../ServiceBreadcrumb";
import { ContextSystem, IntegrationSystemType } from "../../../api/apiTypes";
import { ContextServiceParametersTab } from "./ContextServiceParametersTab";
import { useEffect, useState } from "react";
import { api } from "../../../api/api";

export const ContextServiceParametersPage: React.FC = () => {
  const { systemId } = useParams<{
    systemId: string;
  }>();
  const [system, setSystem] = useState<ContextSystem>();

  useEffect(() => {
    if (systemId) {
      void api
        .getContextService(systemId)
        .then((service) => {
          setSystem(service);
        })
        .catch((error) => {
          console.error(
            "ContextServiceParametersPage: Error reloading service",
            error,
          );
        });
    }
  }, [systemId]);

  const activeTab = "parameters";

  const tabItems = [
    {
      key: "parameters",
      label: "Parameters",
      children: (
        <ContextServiceParametersTab
          systemId={systemId || ""}
          activeTab={activeTab}
          formatTimestamp={formatTimestamp}
          sidePadding={sidePadding}
          styles={styles}
        />
      ),
    },
  ];

  const breadcrumbItems = [
    {
      title: <ServiceTypeBreadcrumbItem type={IntegrationSystemType.CONTEXT} />,
    },
    {
      title: (
        <ServiceNameBreadcrumbItem
          type={IntegrationSystemType.CONTEXT}
          id={systemId}
          name={system?.name}
        />
      ),
    },
  ];

  return (
    <ServiceParametersPageLayout>
      <Breadcrumb items={breadcrumbItems} />
      <ServiceParametersPageHeader />
      <Tabs items={tabItems} activeKey={activeTab} />
    </ServiceParametersPageLayout>
  );
};
