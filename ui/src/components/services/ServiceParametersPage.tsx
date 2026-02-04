import React, { useEffect, useState, createContext, useContext } from "react";
import { Tabs, Typography, Breadcrumb, Flex } from "antd";
import { useParams, useLocation, useNavigate } from "react-router-dom";
import { formatTimestamp } from "../../misc/format-utils.ts";
import { ServiceParametersTab } from "./ServiceParametersTab";
import { ServiceApiSpecsTab } from "./ServiceApiSpecsTab";
import { ServiceEnvironmentsTab } from "./ServiceEnvironmentsTab";
import { api } from "../../api/api";
import {
  IntegrationSystem,
  IntegrationSystemType,
  BaseEntity,
} from "../../api/apiTypes";
import styles from "./Services.module.css";
import {
  ServiceNameBreadcrumbItem,
  ServiceTypeBreadcrumbItem,
} from "./ServiceBreadcrumb.tsx";

const { Title } = Typography;

export const sidePadding = 32;
export const ServiceContext = createContext<IntegrationSystem | null>(null);
export const useServiceContext = () => useContext(ServiceContext);

export const ChainsContext = createContext<BaseEntity[] | null>(null);
export const useChainsContext = () => useContext(ChainsContext);

type ServiceParametersPageLayoutProps = {
  children?: React.ReactNode;
};

export const ServiceParametersPageLayout: React.FC<
  ServiceParametersPageLayoutProps
> = (props: ServiceParametersPageLayoutProps): JSX.Element => {
  return (
    <div
      className={styles["variables-container"]}
      style={{
        display: "flex",
        flexDirection: "column",
        height: "100%",
        minHeight: 0,
      }}
    >
      <div
        style={{
          flexShrink: 0,
          background: "var(--vscode-editor-background, #ffffff)",
          zIndex: 2,
          paddingLeft: sidePadding,
        }}
      >
        {props.children}
      </div>
    </div>
  );
};

export const ServiceParametersPageHeader: React.FC = () => {
  return (
    <Flex
      justify="space-between"
      align="center"
      style={{ margin: "4px 0 8px", fontSize: 20 }}
    >
      <Title
        level={5}
        className={styles["variables-title"]}
        style={{ margin: 0, fontSize: 20 }}
      >
        Common Parameters
      </Title>
    </Flex>
  );
};

export const ServiceParametersPage: React.FC = () => {
  const { systemId, groupId, specId } = useParams<{
    systemId: string;
    groupId?: string;
    specId?: string;
  }>();
  const location = useLocation();
  const state = location.state as
    | { type?: IntegrationSystemType; name?: string }
    | undefined;
  const [system, setSystem] = useState<IntegrationSystem | null>(null);
  const [chains, setChains] = useState<BaseEntity[] | null>(null);
  const [groupName, setGroupName] = useState<string | null>(null);
  const [specName, setSpecName] = useState<string | null>(null);
  const navigate = useNavigate();

  useEffect(() => {
    if (state?.type && state?.name) {
      setSystem({
        id: systemId || "",
        name: state.name,
        type: state.type,
        description: "",
        activeEnvironmentId: "",
        internalServiceName: "",
        protocol: "",
        extendedProtocol: "",
        specification: "",
        labels: [],
      });
    } else if (systemId) {
      void api
        .getService(systemId)
        .then((service) => {
          setSystem(service);
        })
        .catch((error) => {
          console.error("ServiceParametersPage: Error loading service", error);
        });
    }
  }, [systemId, state]);

  useEffect(() => {
    console.log("ServiceParametersPage: URL changed, refreshing service data");
    if (systemId) {
      void api
        .getService(systemId)
        .then((service) => {
          setSystem(service);
        })
        .catch((error) => {
          console.error(
            "ServiceParametersPage: Error reloading service",
            error,
          );
        });
    }
  }, [location.pathname, systemId]);

  useEffect(() => {
    if (groupId && systemId) {
      void api.getApiSpecifications(systemId).then((groups) => {
        const group = groups.find((g) => g.id === groupId);
        setGroupName(group?.name || null);
      });
    } else {
      setGroupName(null);
    }
  }, [groupId, systemId]);

  useEffect(() => {
    if (groupId && specId && systemId) {
      void api.getSpecificationModel(systemId, groupId).then((models) => {
        const model = models.find((m) => m.id === specId);
        setSpecName(model?.name || null);
      });
    } else {
      setSpecName(null);
    }
  }, [groupId, specId, systemId]);

  useEffect(() => {
    if (system && system.id) {
      void api.getChainsUsedByService(system.id).then(setChains);
    }
  }, [system, system?.id]);

  const getActiveTab = (pathname: string): string => {
    if (pathname.includes("/parameters")) return "parameters";
    if (pathname.includes("/environments")) return "environments";
    if (pathname.includes("/specificationGroups")) return "api-specs";
    return "api-specs"; // default
  };

  const activeTab = getActiveTab(location.pathname);

  const handleTabChange = (key: string) => {
    let path = "";
    if (key === "parameters") path = `/services/systems/${systemId}/parameters`;
    else if (key === "api-specs")
      path = `/services/systems/${systemId}/specificationGroups`;
    else if (key === "environments")
      path = `/services/systems/${systemId}/environments`;
    void navigate(path);
  };

  const tabItems = [
    {
      key: "parameters",
      label: "Parameters",
      children: (
        <Flex justify={"start"}>
          <ServiceParametersTab
            systemId={systemId || ""}
            activeTab={activeTab}
            formatTimestamp={formatTimestamp}
            sidePadding={sidePadding}
            styles={styles}
          />
        </Flex>
      ),
    },
    {
      key: "api-specs",
      label: "API Specifications",
      children: <ServiceApiSpecsTab />,
    },
    {
      key: "environments",
      label: "Environments",
      children: (
        <ServiceEnvironmentsTab
          formatTimestamp={formatTimestamp}
          setSystem={(system) => setSystem(system as IntegrationSystem | null)}
        />
      ),
    },
  ];

  const breadcrumbItems = [
    {
      title: <ServiceTypeBreadcrumbItem type={system?.type} />,
    },
    {
      title: (
        <ServiceNameBreadcrumbItem
          type={system?.type}
          id={systemId}
          name={system?.name}
        />
      ),
    },
    ...(groupId && groupName
      ? [
          {
            title: (
              <a
                onClick={(e) => {
                  e.preventDefault();
                  void navigate(
                    `/services/systems/${systemId}/specificationGroups/${groupId}/specifications`,
                  );
                }}
                href={`/services/systems/${systemId}/specificationGroups/${groupId}/specifications`}
              >
                {groupName}
              </a>
            ),
          },
        ]
      : []),
    ...(specId && specName
      ? [
          {
            title: <span>{specName}</span>,
          },
        ]
      : []),
  ];

  return (
    <ServiceContext.Provider value={system}>
      <ChainsContext.Provider value={chains}>
        <ServiceParametersPageLayout>
          <Breadcrumb items={breadcrumbItems} />
          <ServiceParametersPageHeader />
          <Tabs
            items={tabItems}
            activeKey={activeTab}
            onChange={handleTabChange}
          />
        </ServiceParametersPageLayout>
      </ChainsContext.Provider>
    </ServiceContext.Provider>
  );
};
