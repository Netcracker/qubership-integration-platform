import { Table, FloatButton } from "antd";
import { useNavigate } from "react-router";
import { PlusOutlined } from "@ant-design/icons";
import { ChainCreate } from "../components/modal/ChainCreate.tsx";
import { useChains } from "../hooks/useChains.tsx";
import { useModalsContext } from "../Modals.tsx";

const Chains = () => {
  const navigate = useNavigate();
  const { chains, isLoading, loadChains, columns } = useChains();
  const { showModal } = useModalsContext();

  return (
    <>
      <Table
        size="small"
        dataSource={chains}
        columns={columns}
        pagination={false}
        onRow={(record) => {
          return {
            onClick: (_) => {
              navigate(`/chains/${record.id}`);
              console.log(record);
            },
          };
        }}
        rowKey="id"
        rowClassName="clickable-row"
        loading={isLoading}
      />
      <FloatButton
        icon={<PlusOutlined />}
        onClick={() =>
          showModal({
            component: <ChainCreate loadChains={loadChains} />,
          })
        }
      />
    </>
  );
};

export default Chains;
