import { Table } from "antd";

const Admin = () => {
  const data = [
    { id: "test", name: "test 1", description: "test 11" },
    { id: "test2", name: "test 2", description: "test 21" },
  ];
  const columns = [
    { title: "ID", dataIndex: "id", key: "id" },
    { title: "Name", dataIndex: "name", key: "name" },
    { title: "Description", dataIndex: "description", key: "description" },
  ];

  return (
    <Table dataSource={data} columns={columns} pagination={false} rowKey="id" />
  );
};

export default Admin;
