import "@xyflow/react/dist/style.css";
import { Breadcrumb, Col, Flex, Radio, RadioChangeEvent, Row } from "antd";
import {
  Outlet,
  useLocation,
  useNavigate,
  useParams,
} from "react-router";
import { useChain } from "../hooks/useChain.tsx";
import styles from "./Chain.module.css";

const Chain = () => {
  const { chainId } = useParams<string>();

  const location = useLocation();
  const { pathname } = location;
  const navigate = useNavigate();
  const activeKey = getActiveTabKey(pathname);

  const { chain } = useChain(chainId);

  const handlePageChange = (event: RadioChangeEvent) => {
    navigate(`${event.target.value}`); // Update the URL with the selected tab key
  };

  return (
    <Flex className={styles.stretched} gap={"middle"} vertical>
      <Row justify="space-between" align="middle">
        <Col>
          <Breadcrumb
            items={[
              { href: "/chains", title: "Chains" },
              { title: chain?.name },
            ]}
          />
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
              Logging Settings
            </Radio.Button>
          </Radio.Group>
        </Col>
      </Row>
      <Row className={styles.stretched}>
        <Col span={24}>
          <Outlet />
        </Col>
      </Row>
    </Flex>
  );
};

function getActiveTabKey(path: string) {
  const segment = path?.split("/")[3];
  return segment ?? "graph";
}

export default Chain;
