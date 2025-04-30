import React, { useContext, useState } from "react";
import { FloatButton, Form, Input, Modal, notification, Table } from "antd";
import { useSnapshots } from "../hooks/useSnapshots.tsx";
import { useParams } from "react-router";
import {
  DeleteOutlined,
  FileTextOutlined,
  MoreOutlined,
  PlusOutlined,
  RollbackOutlined,
} from "@ant-design/icons";
import { TableProps } from "antd/lib/table";
import { EntityLabel, Snapshot } from "../api/apiTypes.ts";
import { formatTimestamp } from "../misc/format-utils.ts";
import { EntityLabels } from "../components/labels/EntityLabels.tsx";
import FloatButtonGroup from "antd/lib/float-button/FloatButtonGroup";
import { TableRowSelection } from "antd/lib/table/interface";
import { api } from "../api/api.ts";
import { SnapshotXmlView } from "../components/modal/SnapshotXml.tsx";
import { useModalsContext } from "../Modals.tsx";
import {
  getTextColumnFilterFn,
  getTextListColumnFilterFn,
  TextColumnFilterDropdown,
} from "../components/table/TextColumnFilterDropdown.tsx";
import { getTimestampColumnFilterFn } from "../components/table/TimestampColumnFilterDropdown.tsx";
import { SnapshotsCompare } from "../components/modal/SnapshotsCompare.tsx";
import { SnapshotSequenceDiagram } from "../components/modal/SnapshotSequenceDiagram.tsx";
import { InlineEdit, InlineEditContext } from "../components/InlineEdit.tsx";

type TextValueEditProps = {
  name: string;
};

const TextValueEdit: React.FC<TextValueEditProps> = ({ name }) => {
  const inlineEditContext = useContext(InlineEditContext);
  const form = Form.useFormInstance();

  return (
    <Form.Item
      name={name}
      rules={[{ required: true, message: "Value is required." }]}
      style={{ marginBottom: 0 }}
    >
      <Input
        autoFocus
        onPressEnter={() => form.submit()}
        onBlur={() => inlineEditContext?.toggle()}
      />
    </Form.Item>
  );
};

export const Snapshots: React.FC = () => {
  const { chainId } = useParams<{ chainId: string }>();
  const { isLoading, snapshots, setSnapshots } = useSnapshots(chainId);
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const { showModal } = useModalsContext();

  const onCreateBtnClick = async () => {
    if (!chainId) return;
    await createSnapshot(chainId);
  };

  const onDeleteBtnClick = async () => {
    if (selectedRowKeys.length === 0) return;
    Modal.confirm({
      title: "Delete Snapshots",
      content: `Are you sure you want to delete ${selectedRowKeys.length} snapshot(s) and related deployments?`,
      onOk: async () => deleteSelectedSnapshots(),
    });
  };

  const deleteSelectedSnapshots = async () => {
    try {
      const ids = selectedRowKeys.map((key) => key.toString());
      await api.deleteSnapshots(ids);
      setSnapshots(
        snapshots?.filter(
          (snapshot) => !ids.some((id) => snapshot.id === id),
        ) ?? [],
      );
    } catch (error) {
      notification.error({
        message: "Request failed",
        description: "Failed to delete snapshot",
      });
    }
  };

  const createSnapshot = async (chainId: string) => {
    try {
      const snapshot = await api.createSnapshot(chainId);
      setSnapshots([...(snapshots ?? []), snapshot]);
    } catch (error) {
      notification.error({
        message: "Request failed",
        description: "Failed to create snapshot",
      });
    }
  };

  const updateSnapshot = async (
    snapshotId: string,
    name: string,
    labels: EntityLabel[],
  ) => {
    try {
      const snapshot = await api.updateSnapshot(snapshotId, name, labels);
      setSnapshots(snapshots?.map((s) => (s.id === snapshotId ? snapshot : s)));
    } catch (error) {
      notification.error({
        message: "Request failed",
        description: "Failed to update snapshot",
      });
    }
  };

  const onCompareBtnClick = async () => {
    if (selectedRowKeys.length !== 2) return;
    const [oneId, otherId] = selectedRowKeys.map((key) => key.toString());
    showModal({
      component: <SnapshotsCompare oneId={oneId} otherId={otherId} />,
    });
  };

  const onRollbackBtnClick = async () => {
    if (selectedRowKeys.length !== 1) return;
    console.log({ selectedRowKeys });
    Modal.confirm({
      title: "Revert to Snapshot",
      content: (
        <>
          Are you sure you want to revert to this snapshot?
          <br />
          All unsaved changes in the chain will be permanently lost!
        </>
      ),
      onOk: async () => revertToSnapshot(selectedRowKeys[0].toString()),
    });
  };

  const revertToSnapshot = async (id: string) => {
    if (!chainId) return;
    try {
      await api.revertToSnapshot(chainId, id);
    } catch (error) {
      notification.error({
        message: "Request failed",
        description: "Failed to revert to snapshot",
      });
    }
  };

  const onViewDiagramBtnClick = async () => {
    if (selectedRowKeys.length !== 1) return;
    const snapshotId = selectedRowKeys[0].toString();
    showModal({
      component: <SnapshotSequenceDiagram snapshotId={snapshotId} />,
    });
  };

  const onViewXmlBtnClick = async () => {
    if (selectedRowKeys.length !== 1) return;
    const snapshotId = selectedRowKeys[0].toString();
    showModal({
      component: <SnapshotXmlView snapshotId={snapshotId} />,
    });
  };

  const columns: TableProps<Snapshot>["columns"] = [
    {
      title: "Name",
      dataIndex: "name",
      key: "name",
      sorter: (a, b) => a.name.localeCompare(b.name),
      filterDropdown: (props) => <TextColumnFilterDropdown {...props} />,
      onFilter: getTextColumnFilterFn((snapshot) => snapshot.name),
      render: (_, snapshot) => (
        <InlineEdit<{ name: string }>
          values={{ name: snapshot.name }}
          editor={<TextValueEdit name={"name"} />}
          viewer={snapshot.name}
          onSubmit={async ({ name }) => {
            await updateSnapshot(snapshot.id, name, snapshot.labels);
          }}
        />
      ),
    },
    {
      title: "Labels",
      dataIndex: "labels",
      key: "labels",
      filterDropdown: (props) => (
        <TextColumnFilterDropdown {...props} enableExact />
      ),
      onFilter: getTextListColumnFilterFn((snapshot) =>
        snapshot.labels.map((l) => l.name),
      ),
      render: (_, snapshot) => <EntityLabels labels={snapshot.labels} />,
    },
    {
      title: "Created By",
      dataIndex: "createdBy",
      key: "createdBy",
      render: (_, snapshot) => <>{snapshot.createdBy.username}</>,
      sorter: (a, b) =>
        a.createdBy.username.localeCompare(b.createdBy.username),
      filterDropdown: (props) => <TextColumnFilterDropdown {...props} />,
      onFilter: getTextColumnFilterFn(
        (snapshot) => snapshot.createdBy.username,
      ),
    },
    {
      title: "Created At",
      dataIndex: "createdWhen",
      key: "createdWhen",
      render: (_, snapshot) => <>{formatTimestamp(snapshot.createdWhen)}</>,
      sorter: (a, b) => a.createdWhen - b.createdWhen,
      filterDropdown: (props) => <TextColumnFilterDropdown {...props} />,
      onFilter: getTimestampColumnFilterFn((snapshot) => snapshot.createdWhen),
    },
    {
      title: "Modified By",
      dataIndex: "modifiedBy",
      key: "modifiedBy",
      render: (_, snapshot) => <>{snapshot.modifiedBy.username}</>,
      sorter: (a, b) =>
        a.modifiedBy.username.localeCompare(b.modifiedBy.username),
      filterDropdown: (props) => <TextColumnFilterDropdown {...props} />,
      onFilter: getTextColumnFilterFn(
        (snapshot) => snapshot.modifiedBy.username,
      ),
    },
    {
      title: "Modified At",
      dataIndex: "modifiedWhen",
      key: "modifiedWhen",
      render: (_, snapshot) => <>{formatTimestamp(snapshot.modifiedWhen)}</>,
      sorter: (a, b) => a.modifiedWhen - b.modifiedWhen,
      filterDropdown: (props) => <TextColumnFilterDropdown {...props} />,
      onFilter: getTimestampColumnFilterFn((snapshot) => snapshot.modifiedWhen),
    },
  ];

  const onSelectChange = (newSelectedRowKeys: React.Key[]) => {
    setSelectedRowKeys(newSelectedRowKeys);
  };

  const rowSelection: TableRowSelection<Snapshot> = {
    type: "checkbox",
    selectedRowKeys,
    onChange: onSelectChange,
  };

  return (
    <>
      <Table
        columns={columns}
        rowSelection={rowSelection}
        dataSource={snapshots}
        pagination={false}
        loading={isLoading}
        rowKey="id"
        scroll={{ y: "calc(100vh - 200px)" }}
      />
      <FloatButtonGroup trigger="hover" icon={<MoreOutlined />}>
        <FloatButton icon={<FileTextOutlined />} onClick={onViewXmlBtnClick} />
        <FloatButton icon={<>⭾</>} onClick={onViewDiagramBtnClick} />
        <FloatButton icon={<>⇄</>} onClick={onCompareBtnClick} />
        <FloatButton icon={<RollbackOutlined />} onClick={onRollbackBtnClick} />
        <FloatButton icon={<DeleteOutlined />} onClick={onDeleteBtnClick} />
        <FloatButton icon={<PlusOutlined />} onClick={onCreateBtnClick} />
      </FloatButtonGroup>
    </>
  );
};
