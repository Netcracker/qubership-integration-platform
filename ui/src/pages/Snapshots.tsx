import React, { useState } from "react";
import { Button, Dropdown, Table } from "antd";
import { useSnapshots } from "../hooks/useSnapshots.tsx";
import { useParams } from "react-router";
import { useNavigate } from "react-router";
import { TableProps } from "antd/lib/table";
import { DiagramMode, EntityLabel, Snapshot } from "../api/apiTypes.ts";
import { formatTimestamp } from "../misc/format-utils.ts";
import { EntityLabels } from "../components/labels/EntityLabels.tsx";
import { TableRowSelection } from "antd/lib/table/interface";
import { api } from "../api/api.ts";
import { SnapshotXmlView } from "../components/modal/SnapshotXml.tsx";
import { useModalsContext } from "../Modals.tsx";
import {
  getTextColumnFilterFn,
  getTextListColumnFilterFn,
  TextColumnFilterDropdown,
} from "../components/table/TextColumnFilterDropdown.tsx";
import {
  getTimestampColumnFilterFn,
  TimestampColumnFilterDropdown,
} from "../components/table/TimestampColumnFilterDropdown.tsx";
import { SnapshotsCompare } from "../components/modal/SnapshotsCompare.tsx";
import { InlineEdit } from "../components/InlineEdit.tsx";
import { TextValueEdit } from "../components/table/TextValueEdit.tsx";
import { LabelsEdit } from "../components/table/LabelsEdit.tsx";
import { useNotificationService } from "../hooks/useNotificationService.tsx";
import { SequenceDiagram } from "../components/modal/SequenceDiagram.tsx";
import { OverridableIcon } from "../icons/IconProvider.tsx";
import { useRegisterChainHeaderActions } from "./ChainHeaderActionsContext.tsx";
import { ChainHeaderToolbar } from "../components/ChainHeaderToolbar.tsx";
import { TablePageLayout } from "../components/TablePageLayout.tsx";
import { filterOutByIds, toStringIds } from "../misc/selection-utils.ts";
import { confirmAndRun } from "../misc/confirm-utils.ts";

export const Snapshots: React.FC = () => {
  const { chainId } = useParams<{ chainId: string }>();
  const navigate = useNavigate();
  const { isLoading, snapshots, setSnapshots } = useSnapshots(chainId);
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const { showModal } = useModalsContext();
  const notificationService = useNotificationService();

  const onCreateBtnClick = () => {
    if (!chainId) return;
    void createSnapshot(chainId);
  };

  const onDeleteBtnClick = () => {
    if (selectedRowKeys.length === 0) return;
    confirmAndRun({
      title: "Delete Snapshots",
      content: `Are you sure you want to delete ${selectedRowKeys.length} snapshot(s) and related deployments?`,
      onOk: deleteSelectedSnapshots,
    });
  };

  const deleteSnapshotWithConfirmation = (snapshot: Snapshot) => {
    confirmAndRun({
      title: "Delete Snapshot",
      content: `Are you sure you want to delete snapshot and related deployments?`,
      onOk: () => deleteSnapshot(snapshot),
    });
  };

  const deleteSnapshot = async (snapshot: Snapshot) => {
    try {
      await api.deleteSnapshot(snapshot.id);
      setSnapshots(snapshots?.filter((s) => s.id !== snapshot.id) ?? []);
    } catch (error) {
      notificationService.requestFailed("Failed to delete snapshot", error);
    }
  };

  const deleteSelectedSnapshots = async () => {
    try {
      const ids = toStringIds(selectedRowKeys);
      await api.deleteSnapshots(ids);
      setSnapshots(filterOutByIds(snapshots, ids));
    } catch (error) {
      notificationService.requestFailed("Failed to delete snapshots", error);
    }
  };

  const createSnapshot = async (chainId: string) => {
    try {
      const snapshot = await api.createSnapshot(chainId);
      setSnapshots([...(snapshots ?? []), snapshot]);
    } catch (error) {
      notificationService.requestFailed("Failed to create snapshot", error);
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
      notificationService.requestFailed("Failed to update snapshot", error);
    }
  };

  const onCompareBtnClick = () => {
    if (selectedRowKeys.length !== 2) return;
    const [oneId, otherId] = toStringIds(selectedRowKeys);
    showModal({
      component: <SnapshotsCompare oneId={oneId} otherId={otherId} />,
    });
  };

  const revertToSnapshotWithConfirmation = (snapshot: Snapshot) => {
    confirmAndRun({
      title: "Revert to Snapshot",
      content: (
        <>
          Are you sure you want to revert to this snapshot?
          <br />
          All unsaved changes in the chain will be permanently lost!
        </>
      ),
      onOk: () => revertToSnapshot(snapshot.id),
    });
  };

  const revertToSnapshot = async (id: string) => {
    if (!chainId) return;
    try {
      await api.revertToSnapshot(chainId, id);
      void navigate(`/chains/${chainId}/graph`);
    } catch (error) {
      notificationService.requestFailed("Failed to revert to snapshot", error);
    }
  };

  const showSnapshotDiagram = (snapshot: Snapshot) => {
    showModal({
      component: (
        <SequenceDiagram
          title="Snapshot Sequence Diagram"
          fileNamePrefix={"snapshot"}
          entityId={snapshot.id}
          diagramProvider={() => {
            if (!chainId) {
              return Promise.reject(new Error("Chain is not specified"));
            }
            return api.getSnapshotSequenceDiagram(chainId, snapshot.id, [
              DiagramMode.FULL,
              DiagramMode.SIMPLE,
            ]);
          }}
        />
      ),
    });
  };

  const showSnapshotXml = (snapshot: Snapshot) => {
    showModal({
      component: <SnapshotXmlView snapshotId={snapshot.id} />,
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
      render: (_, snapshot) => (
        <InlineEdit<{ labels: string[] }>
          values={{
            labels: snapshot.labels
              ?.filter((l) => !l.technical)
              .map((l) => l.name),
          }}
          editor={<LabelsEdit name={"labels"} />}
          viewer={<EntityLabels labels={snapshot.labels} />}
          onSubmit={async ({ labels }) => {
            await updateSnapshot(
              snapshot.id,
              snapshot.name,
              labels.map((name) => ({ name, technical: false })),
            );
          }}
        />
      ),
    },
    {
      title: "Created By",
      dataIndex: "createdBy",
      key: "createdBy",
      render: (_, snapshot) => snapshot.createdBy?.username ?? "—",
      sorter: (a, b) =>
        (a.createdBy?.username ?? "").localeCompare(
          b.createdBy?.username ?? "",
        ),
      filterDropdown: (props) => <TextColumnFilterDropdown {...props} />,
      onFilter: getTextColumnFilterFn(
        (snapshot) => snapshot.createdBy?.username ?? "",
      ),
    },
    {
      title: "Created At",
      dataIndex: "createdWhen",
      key: "createdWhen",
      render: (_, snapshot) =>
        snapshot.createdWhen ? formatTimestamp(snapshot.createdWhen) : "-",
      sorter: (a, b) => (a.createdWhen ?? 0) - (b.createdWhen ?? 0),
      filterDropdown: (props) => <TimestampColumnFilterDropdown {...props} />,
      onFilter: getTimestampColumnFilterFn(
        (snapshot) => snapshot.createdWhen ?? 0,
      ),
    },
    {
      title: "Modified By",
      dataIndex: "modifiedBy",
      key: "modifiedBy",
      render: (_, snapshot) => snapshot.modifiedBy?.username ?? "—",
      sorter: (a, b) =>
        (a.modifiedBy?.username ?? "").localeCompare(
          b.modifiedBy?.username ?? "",
        ),
      filterDropdown: (props) => <TextColumnFilterDropdown {...props} />,
      onFilter: getTextColumnFilterFn(
        (snapshot) => snapshot.modifiedBy?.username ?? "",
      ),
    },
    {
      title: "Modified At",
      dataIndex: "modifiedWhen",
      key: "modifiedWhen",
      render: (_, snapshot) =>
        snapshot.modifiedWhen ? formatTimestamp(snapshot.modifiedWhen) : "-",
      sorter: (a, b) => (a.modifiedWhen ?? 0) - (b.modifiedWhen ?? 0),
      filterDropdown: (props) => <TimestampColumnFilterDropdown {...props} />,
      onFilter: getTimestampColumnFilterFn(
        (snapshot) => snapshot.modifiedWhen ?? 0,
      ),
    },
    {
      title: "",
      key: "actions",
      width: 40,
      className: "actions-column",
      render: (_, snapshot) => (
        <>
          <Dropdown
            menu={{
              items: [
                {
                  key: "delete",
                  icon: <OverridableIcon name="delete" />,
                  label: "Delete",
                  onClick: () => deleteSnapshotWithConfirmation(snapshot),
                },
                {
                  key: "revert",
                  icon: <OverridableIcon name="rollback" />,
                  label: "Revert to",
                  onClick: () => revertToSnapshotWithConfirmation(snapshot),
                },
                {
                  key: "showXml",
                  icon: <OverridableIcon name="fileText" />,
                  label: "Show XML",
                  onClick: () => showSnapshotXml(snapshot),
                },
                {
                  key: "showDiagram",
                  icon: <span className="anticon">⭾</span>,
                  label: "Show diagram",
                  onClick: () => showSnapshotDiagram(snapshot),
                },
              ],
            }}
            trigger={["click"]}
            placement="bottomRight"
          >
            <Button
              size="small"
              type="text"
              icon={<OverridableIcon name="more" />}
            />
          </Dropdown>
        </>
      ),
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

  useRegisterChainHeaderActions(
    <ChainHeaderToolbar
      buttons={[
        {
          title: "Create snapshot",
          type: "primary",
          iconName: "plus",
          onClick: onCreateBtnClick,
        },
        {
          title: "Delete selected snapshots",
          iconName: "delete",
          onClick: onDeleteBtnClick,
          disabled: selectedRowKeys.length === 0,
        },
        {
          title: "Compare selected snapshots",
          iconNode: <>⇄</>,
          onClick: onCompareBtnClick,
          disabled: selectedRowKeys.length !== 2,
        },
      ]}
    />,
    [selectedRowKeys.length],
  );

  return (
    <TablePageLayout>
      <Table
        size="small"
        columns={columns}
        rowSelection={rowSelection}
        dataSource={snapshots}
        pagination={false}
        loading={isLoading}
        rowKey="id"
        className="flex-table"
        style={{ flex: 1, minHeight: 0 }}
        scroll={{ y: "" }}
      />
    </TablePageLayout>
  );
};
