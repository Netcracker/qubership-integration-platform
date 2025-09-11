import React, { useEffect, useState, createContext, useContext } from "react";
import {
  Tabs,
  Typography,
  Breadcrumb,
  Flex
} from "antd";
import { useParams, useLocation, useNavigate } from "react-router-dom";
import { formatTimestamp } from "../../misc/format-utils.ts";
import { ServiceParametersTab } from "./ServiceParametersTab";
import { ServiceApiSpecsTab } from "./ServiceApiSpecsTab";
import { ServiceEnvironmentsTab } from "./ServiceEnvironmentsTab";
import { api } from "../../api/api";
import { IntegrationSystem, IntegrationSystemType, BaseEntity } from "../../api/apiTypes";
import styles from "./Services.module.css";

const { Title } = Typography;

const sidePadding = 32;
export const ServiceContext = createContext<IntegrationSystem | null>(null);
export const useServiceContext = () => useContext(ServiceContext);

export const ChainsContext = createContext<BaseEntity[] | null>(null);
export const useChainsContext = () => useContext(ChainsContext);

export const ServiceParametersPage: React.FC = () => {
  const { systemId, groupId, specId } = useParams<{ systemId: string; groupId?: string; specId?: string }>();
  const location = useLocation();
  const state = location.state as { type?: IntegrationSystemType; name?: string } | undefined;
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
      void api.getService(systemId)
        .then((service) => {
          setSystem(service);
        })
        .catch((error) => {
          console.error('ServiceParametersPage: Error loading service', error);
        });
    }
  }, [systemId, state]);

  useEffect(() => {
    console.log('ServiceParametersPage: URL changed, refreshing service data');
    if (systemId) {
      void api.getService(systemId)
        .then((service) => {
          setSystem(service);
        })
        .catch((error) => {
          console.error('ServiceParametersPage: Error reloading service', error);
        });
    }
  }, [location.pathname, systemId]);

  useEffect(() => {
    if (groupId && systemId) {
      void api.getApiSpecifications(systemId).then(groups => {
        const group = groups.find(g => g.id === groupId);
        setGroupName(group?.name || null);
      });
    } else {
      setGroupName(null);
    }
  }, [groupId, systemId]);

  useEffect(() => {
    if (groupId && specId && systemId) {
      void api.getSpecificationModel(systemId, groupId).then(models => {
        const model = models.find(m => m.id === specId);
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

  const getTypeLabel = (type?: IntegrationSystemType) => {
    switch (type) {
      case IntegrationSystemType.EXTERNAL: return "External Services";
      case IntegrationSystemType.INTERNAL: return "Internal Services";
      case IntegrationSystemType.IMPLEMENTED: return "Implemented Services";
      default: return "Services";
    }
  };

  const getTabHash = (type?: IntegrationSystemType) => {
    switch (type) {
      case IntegrationSystemType.EXTERNAL: return "external";
      case IntegrationSystemType.INTERNAL: return "internal";
      case IntegrationSystemType.IMPLEMENTED: return "implemented";
      default: return "implemented";
    }
  };

  const getActiveTab = (pathname: string): string => {
    if (pathname.includes('/parameters')) return 'parameters';
    if (pathname.includes('/environments')) return 'environments';
    if (pathname.includes('/specificationGroups')) return 'api-specs';
    return 'api-specs'; // default
  };

  const activeTab = getActiveTab(location.pathname);

  const handleTabChange = (key: string) => {
    let path = '';
    if (key === 'parameters') path = `/services/systems/${systemId}/parameters`;
    else if (key === 'api-specs') path = `/services/systems/${systemId}/specificationGroups`;
    else if (key === 'environments') path = `/services/systems/${systemId}/environments`;
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
      children: (
        <ServiceApiSpecsTab />
      ),
    },
    {
      key: "environments",
      label: "Environments",
      children: (
        <ServiceEnvironmentsTab
          formatTimestamp={formatTimestamp}
          setSystem={(system) => setSystem(system as IntegrationSystem | null)}
        />
      ) },
  ];

  return (
    <ServiceContext.Provider value={system}>
      <ChainsContext.Provider value={chains}>
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
              background: "#fff",
              zIndex: 2,
              paddingLeft: sidePadding,
            }}
          >
            <Breadcrumb>
              <Breadcrumb.Item>
                <a
                  onClick={e => {
                    e.preventDefault();
                    void navigate(`/services#${getTabHash(system?.type)}`);
                  }}
                  href={`/services#${getTabHash(system?.type)}`}
                >
                  {getTypeLabel(system?.type)}
                </a>
              </Breadcrumb.Item>
              <Breadcrumb.Item>
                <a
                  onClick={e => {
                    e.preventDefault();
                    void navigate(`/services/systems/${systemId}/specificationGroups`);
                  }}
                  href={`/services/systems/${systemId}/specificationGroups`}
                >
                  {system?.name || systemId || "..."}
                </a>
              </Breadcrumb.Item>
              {groupId && groupName && (
                <Breadcrumb.Item>
                  <a
                    onClick={e => {
                      e.preventDefault();
                      void navigate(`/services/systems/${systemId}/specificationGroups/${groupId}/specifications`);
                    }}
                    href={`/services/systems/${systemId}/specificationGroups/${groupId}/specifications`}
                  >
                    {groupName}
                  </a>
                </Breadcrumb.Item>
              )}
              {specId && specName && (
                <Breadcrumb.Item>
                  <span>{specName}</span>
                </Breadcrumb.Item>
              )}
            </Breadcrumb>

            <Flex justify="space-between" align="center" style={{ margin: "4px 0 8px", fontSize: 20 }}>
              <Title
                level={5}
                className={styles["variables-title"]}
                style={{ margin: 0, fontSize: 20 }}
              >
                Common Parameters
              </Title>
            </Flex>

            <Tabs
              items={tabItems}
              activeKey={activeTab}
              onChange={handleTabChange}
            />
          </div>
        </div>
      </ChainsContext.Provider>
    </ServiceContext.Provider>
  );
};
