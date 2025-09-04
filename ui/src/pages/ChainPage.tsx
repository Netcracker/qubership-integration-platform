import "@xyflow/react/dist/style.css";
import { Breadcrumb, Col, Flex, Radio, RadioChangeEvent, Row, Result, Button } from "antd";
import { Outlet, useLocation, useNavigate, useParams } from "react-router";
import { useChain } from "../hooks/useChain.tsx";
import styles from "./Chain.module.css";
import { createContext, useEffect, useState } from "react";
import { Chain } from "../api/apiTypes.ts";
import { BreadcrumbProps } from "antd/es/breadcrumb/Breadcrumb";
import { HomeOutlined } from "@ant-design/icons";

export type ChainContextData = {
  chain: Chain | undefined;
  update: (changes: Partial<Chain>) => Promise<void>;
};

export const ChainContext = createContext<ChainContextData | undefined>(
  undefined,
);

function buildPathItems(path: Map<string, string>): BreadcrumbProps["items"] {
  const entries = Object.entries(path).reverse();
  const items = entries.map(([key, value]: [string, string], index) => ({
    title: value,
    href: index < entries.length - 1 ? `/chains?folder=${key}` : undefined,
  }));
  return [
    {
      href: "/chains",
      title: <HomeOutlined />,
    },
    ...items,
  ];
}

const ChainPage = () => {
  const { chainId, sessionId } = useParams();
  const [pathItems, setPathItems] = useState<BreadcrumbProps["items"]>([]);

  const location = useLocation();
  const { pathname } = location;
  const navigate = useNavigate();
  const activeKey = getActiveTabKey(pathname);

  const { chain, setChain, updateChain, isLoading, error } = useChain(chainId);

  useEffect(() => {
    const items: BreadcrumbProps["items"] = [
      ...(buildPathItems(chain?.navigationPath ?? new Map<string, string>()) ??
        []),
      ...(sessionId
        ? [
            { title: "Sessions", href: `/chains/${chainId}/sessions` },
            { title: sessionId },
          ]
        : []),
    ];
    setPathItems(items);
  }, [chain, chainId, sessionId]);

  const handlePageChange = (event: RadioChangeEvent) => {
    void navigate(`${event.target.value}`); // Update the URL with the selected tab key
  };

  if (isLoading && !chain) {
    return (
      <Flex className={styles.stretched} gap={"middle"} vertical>
        <Row className={styles.stretched}>
          <Col span={24}>
            <div style={{ textAlign: 'center', padding: '50px' }}>
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
              subTitle={`Chain with ID "${chainId}" does not exist.`
              }
              extra={[
                <Button type="primary" key="back" onClick={() => void navigate("/chains")}>
                  Back to Chains
                </Button>
              ]}
            />
          </Col>
        </Row>
      </Flex>
    );
  }

  return (
    <Flex className={styles.stretched} gap={"middle"} vertical>
      <Row justify="space-between" align="middle">
        <Col>
          <Breadcrumb items={pathItems} />
        </Col>
        <Col>
          <Radio.Group
            value={activeKey}
            onChange={handlePageChange}
            defaultValue="graph"
            optionType="button"
            buttonStyle="solid"
          >
            <Radio.Button value="graph">Graph</Radio.Button>
            <Radio.Button value="snapshots">Snapshots</Radio.Button>
            <Radio.Button value="deployments">Deployments</Radio.Button>
            <Radio.Button value="sessions">Sessions</Radio.Button>
            <Radio.Button value="logging-settings">
              Logging
            </Radio.Button>
            <Radio.Button value="masking">
              Masking
            </Radio.Button>
            <Radio.Button value="properties">Properties</Radio.Button>
          </Radio.Group>
        </Col>
      </Row>
      <Row className={styles.stretched}>
        <Col span={24}>
          <ChainContext.Provider
            value={{
              chain,
              update: async (changes) => updateChain(changes).then(setChain),
            }}
          >
            <Outlet />
          </ChainContext.Provider>
        </Col>
      </Row>
    </Flex>
  );
};

function getActiveTabKey(path: string) {
  const segment = path?.split("/")[3];
  return segment ?? "graph";
}

export default ChainPage;
