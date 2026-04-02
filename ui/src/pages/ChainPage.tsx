import "@xyflow/react/dist/style.css";
import { Breadcrumb, Col, Flex, Row, Result, Button, Tabs, Tag } from "antd";
import { ChainHeaderActionsContextProvider } from "./ChainHeaderActionsContext.tsx";
import { Outlet, useLocation, useNavigate, useParams } from "react-router";
import { useChain } from "../hooks/useChain.tsx";
import styles from "./Chain.module.css";
import {
  type FC,
  type ReactNode,
  createContext,
  useCallback,
  useEffect,
  useState,
} from "react";
import { Chain } from "../api/apiTypes.ts";
import { BreadcrumbProps } from "antd/es/breadcrumb/Breadcrumb";
import { isVsCode } from "../api/rest/vscodeExtensionApi.ts";
import { OverridableIcon } from "../icons/IconProvider.tsx";
import { useChainFullscreenContext } from "./ChainFullscreenContext.tsx";

export type ChainContextData = {
  chain: Chain | undefined;
  update: (changes: Partial<Chain>) => Promise<void>;
  refresh: () => Promise<void>;
};

export const ChainContext = createContext<ChainContextData | undefined>(
  undefined,
);

const ChainPage = () => {
  const { chainId, sessionId } = useParams();
  const [pathItems, setPathItems] = useState<BreadcrumbProps["items"]>([]);
  const [headerActions, setHeaderActions] = useState<ReactNode>(null);

  const location = useLocation();
  const { pathname } = location;
  const navigate = useNavigate();
  const activeKey = getActiveTabKey(pathname);

  const { chain, setChain, updateChain, getChain, isLoading, error } =
    useChain(chainId);

  const refreshChain = useCallback(async () => {
    if (chainId) {
      const updatedChain = await getChain();
      if (updatedChain) {
        setChain(updatedChain);
      }
    }
  }, [chainId, getChain, setChain]);

  useEffect(() => {
    const link = (href: string, content: React.ReactNode) => (
      <a
        href={href}
        onClick={(e) => {
          e.preventDefault();
          void navigate(href);
        }}
      >
        {content}
      </a>
    );

    const navigationItems = Object.entries(chain?.navigationPath ?? [])
      .reverse()
      .map(([key, value], index, arr) => ({
        title:
          index < arr.length - 1 ? link(`/chains?folder=${key}`, value) : value,
      }));

    setPathItems([
      { title: link("/chains", <OverridableIcon name="home" />) },
      ...navigationItems,
      ...(sessionId
        ? [
            {
              title: link(`/chains/${chainId}/sessions`, "Sessions"),
            },
            { title: sessionId },
          ]
        : []),
    ]);
  }, [chain, chainId, sessionId, navigate]);

  const handleTabChange = (key: string) => {
    void navigate(`/chains/${chainId}/${key}`);
  };

  const tabItems = [
    { key: "graph", label: "Graph" },
    ...(isVsCode
      ? []
      : [
          { key: "snapshots", label: "Snapshots" },
          { key: "deployments", label: "Deployments" },
          { key: "sessions", label: "Sessions" },
          {
            key: "logging-settings",
            label: "Logging",
          },
        ]),
    { key: "masking", label: "Masking" },
    { key: "properties", label: "Properties" },
  ];

  if (isLoading && !chain) {
    return (
      <Flex className={styles.stretched} gap={"middle"} vertical>
        <Row className={styles.stretched}>
          <Col span={24}>
            <div style={{ textAlign: "center", padding: "50px" }}>
              Loading chain...
            </div>
          </Col>
        </Row>
      </Flex>
    );
  }

  if (error || !chain) {
    return (
      <Flex className={styles.stretched} gap={"middle"} vertical>
        <Row className={styles.stretched}>
          <Col span={24}>
            <Result
              status="404"
              title="Chain Not Found"
              subTitle={`Chain with ID "${chainId}" does not exist.`}
              extra={[
                <Button
                  type="primary"
                  key="back"
                  onClick={() => void navigate("/chains")}
                >
                  Back to Chains
                </Button>,
              ]}
            />
          </Col>
        </Row>
      </Flex>
    );
  }

  return (
    <ChainHeaderActionsContextProvider value={{ setActions: setHeaderActions }}>
      <ChainContext.Provider
        value={{
          chain,
          update: async (changes) => updateChain(changes).then(setChain),
          refresh: refreshChain,
        }}
      >
        <Flex className={styles.stretched} gap={4} vertical>
          <ChainPageHeader
            activeKey={activeKey}
            tabItems={tabItems}
            headerActions={headerActions}
            pathItems={pathItems}
            onTabChange={handleTabChange}
            showUnsavedChanges={!isVsCode && chain.unsavedChanges}
          />
          <Flex className={styles.stretched}>
            <Outlet />
          </Flex>
        </Flex>
      </ChainContext.Provider>
    </ChainHeaderActionsContextProvider>
  );
};

type ChainPageHeaderProps = {
  activeKey: string;
  tabItems: { key: string; label: string }[];
  headerActions: ReactNode;
  pathItems: BreadcrumbProps["items"];
  onTabChange: (key: string) => void;
  showUnsavedChanges: boolean;
};

const ChainPageHeader: FC<ChainPageHeaderProps> = ({
  activeKey,
  tabItems,
  headerActions,
  pathItems,
  onTabChange,
  showUnsavedChanges,
}) => {
  const fullscreenCtx = useChainFullscreenContext();
  if (fullscreenCtx?.fullscreen) return null;

  if (isVsCode) {
    return (
      <Tabs
        className={styles.chainPageTabs as string}
        activeKey={activeKey}
        onChange={onTabChange}
        items={tabItems}
        style={{ marginBottom: 0 }}
        tabBarExtraContent={
          <div className={styles.chainTabBarExtra as string}>{headerActions}</div>
        }
      />
    );
  }

  const tabsBarExtra = (
    <div className={styles.chainTabBarExtra as string}>{headerActions}</div>
  );

  return (
    <>
      <Row
        className={styles.chainPageHeaderRow as string}
        justify="space-between"
        align="middle"
        style={{ minHeight: 32 }}
      >
        <Col flex="auto">
          <Breadcrumb
            items={pathItems}
            className={styles.breadcrumb}
            style={{ marginLeft: 8 }}
          />
        </Col>
        {showUnsavedChanges ? (
          <Col flex="none">
            <Tag
              color="warning"
              className={styles.unsavedChangesTag as string}
              data-testid="chain-unsaved-changes"
            >
              Unsaved changes
            </Tag>
          </Col>
        ) : null}
      </Row>
      <Tabs
        className={styles.chainPageTabs as string}
        activeKey={activeKey}
        onChange={onTabChange}
        items={tabItems}
        tabBarExtraContent={tabsBarExtra}
      />
    </>
  );
};

function getActiveTabKey(path: string) {
  const segment = path?.split("/")[3];
  return segment ?? "graph";
}

export default ChainPage;
