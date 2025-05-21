import {
  Breadcrumb,
  Button,
  Dropdown,
  Flex,
  FloatButton,
  MenuProps,
  notification,
  Table,
} from "antd";
import { useNavigate, useSearchParams } from "react-router";
import {
  CarryOutOutlined,
  DeleteOutlined,
  ExportOutlined,
  FileAddOutlined,
  FileOutlined,
  FolderAddOutlined,
  FolderOutlined,
  HomeOutlined,
  ImportOutlined,
  MoreOutlined,
  SendOutlined,
  SettingOutlined,
} from "@ant-design/icons";
import { useModalsContext } from "../Modals.tsx";
import { Chain, FolderItem, FolderItemType } from "../api/apiTypes.ts";
import React, { useEffect, useState } from "react";
import { api } from "../api/api.ts";
import { TableProps } from "antd/lib/table";
import { TextColumnFilterDropdown } from "../components/table/TextColumnFilterDropdown.tsx";
import { formatTimestamp } from "../misc/format-utils.ts";
import { TimestampColumnFilterDropdown } from "../components/table/TimestampColumnFilterDropdown.tsx";
import { EntityLabels } from "../components/labels/EntityLabels.tsx";
import { TableRowSelection } from "antd/lib/table/interface";
import Search from "antd/lib/input/Search";
import FloatButtonGroup from "antd/lib/float-button/FloatButtonGroup";
import { BreadcrumbProps } from "antd/es/breadcrumb/Breadcrumb";
import { LabelsEdit } from "../components/table/LabelsEdit.tsx";
import { InlineEdit } from "../components/InlineEdit.tsx";
import { TextValueEdit } from "../components/table/TextValueEdit.tsx";

type ChainTableItem = FolderItem & {
  children?: ChainTableItem[];
};

function buildTableItems(folderItems: FolderItem[]): ChainTableItem[] {
  const itemMap = new Map<string, ChainTableItem>(
    folderItems.map((item) => [
      item.id,
      item.itemType === FolderItemType.FOLDER
        ? {
            ...item,
            children: [],
          }
        : { ...item },
    ]),
  );
  const items: ChainTableItem[] = [];
  Array.from(itemMap.values()).forEach((item) => {
    if (item.parentId && itemMap.has(item.parentId)) {
      const parentItem = itemMap.get(item.parentId);
      // @ts-ignore
      parentItem.children = parentItem?.children
        ? [...parentItem.children, item]
        : [item];
    } else {
      items.push(item);
    }
  });

  Array.from(itemMap.values()).forEach((item) =>
    item.children?.sort(compareChainTableItemsByTypeAndName),
  );
  items.sort(compareChainTableItemsByTypeAndName);

  return items;
}

function compareChainTableItemsByTypeAndName(
  a: ChainTableItem,
  b: ChainTableItem,
): number {
  if (a.itemType !== b.itemType) {
    const values = Object.values(FolderItemType);
    return values.indexOf(a.itemType) - values.indexOf(b.itemType);
  } else {
    return a.name.localeCompare(b.name);
  }
}

function buildPathItems(path: Map<string, string>): BreadcrumbProps["items"] {
  const entries = Object.entries(path).reverse();
  const items = entries.map(([key, value], index) => ({
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

const Chains = () => {
  const navigate = useNavigate();
  const { showModal } = useModalsContext();
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [folderItems, setFolderItems] = useState<FolderItem[]>([]);
  const [tableItems, setTableItems] = useState<ChainTableItem[]>([]);
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const [loadedFolders, setLoadedFolders] = useState<Set<string>>(new Set());
  const [searchParams] = useSearchParams();
  const [pathItems, setPathItems] = useState<BreadcrumbProps["items"]>([]);
  const [selectedKeys, setSelectedKeys] = useState<string[]>([
    "status",
    "labels",
    "createdBy",
    "createdWhen",
    "modifiedBy",
    "modifiedWhen",
  ]);

  useEffect(() => {
    const folderId = searchParams.get("folder");
    const itemsPromise = folderId
      ? getFolder(folderId).then((response) => {
          setPathItems(buildPathItems(response?.navigationPath ?? new Map()));
          return response?.items || [];
        })
      : getRootFolderItems().then((items) => {
          setPathItems([]);
          return items;
        });
    itemsPromise.then((items) => {
      setIsLoading(true);
      setFolderItems(items);
      setIsLoading(false);
    });
  }, [searchParams]);

  useEffect(() => {
    setTableItems(buildTableItems(folderItems));
  }, [folderItems]);

  const getRootFolderItems = async (): Promise<FolderItem[]> => {
    setIsLoading(true);
    try {
      return api.getRootFolder();
    } catch (error) {
      notification.error({
        message: "Request failed",
        description: "Failed to get root folder content",
      });
      return [];
    } finally {
      setIsLoading(false);
    }
  };

  const getFolder = async (folderId: string) => {
    setIsLoading(true);
    try {
      return api.getFolder(folderId);
    } catch (error) {
      notification.error({
        message: "Request failed",
        description: "Failed to get folder content",
      });
    } finally {
      setIsLoading(false);
    }
  };

  const openFolder = async (folderId: string) => {
    return getFolder(folderId).then((response) => {
      if (!response) {
        return;
      }
      setFolderItems([
        ...response.items,
        ...folderItems.filter(
          (item) => !response.items.some((i) => i.id === item.id),
        ),
      ]);
      const s = new Set(loadedFolders);
      s.add(folderId);
      setLoadedFolders(s);
    });
  };

  const updateChain = async (chainId: string, changes: Partial<Chain>) => {
    setIsLoading(true);
    try {
      const response = await api.updateChain(chainId, changes);
      setFolderItems(
        folderItems.map((item) =>
          item.id === chainId ? { ...item, ...response } : item,
        ),
      );
    } catch (error) {
      notification.error({
        message: "Request failed",
        description: "Failed to update chain",
      });
    } finally {
      setIsLoading(false);
    }
  };

  const updateFolder = async (
    folderId: string,
    changes: Partial<FolderItem>,
  ) => {
    setIsLoading(true);
    try {
      const response = await api.updateFolder(folderId, changes);
      setFolderItems(
        folderItems.map((item) =>
          item.id === folderId ? { ...item, ...response } : item,
        ),
      );
    } catch (error) {
      notification.error({
        message: "Request failed",
        description: "Failed to update folder",
      });
    } finally {
      setIsLoading(false);
    }
  };

  const onSelectChange = (newSelectedRowKeys: React.Key[]) => {
    setSelectedRowKeys(newSelectedRowKeys);
  };

  const folderMenuItems: MenuProps["items"] = [
    { label: "Create New Folder", key: "createNewFolder" },
    { label: "Create New Chain", key: "createNewChain" },
    { type: "divider" },
    { label: "Expand All", key: "expandAll" },
    { label: "Collapse All", key: "collapseAll" },
    { type: "divider" },
    { label: "Copy Link", key: "copyLink" },
    { label: "Export", key: "export" },
    { type: "divider" },
    { label: "Cut", key: "cut" },
    { label: "Paste", key: "paste" },
    { label: "Delete", key: "delete" },
  ];

  const chainMenuItems: MenuProps["items"] = [
    { label: "Copy Link", key: "copyLink" },
    { label: "Export", key: "export" },
    { label: "Generate DDS", key: "generateDDS" },
    { type: "divider" },
    { label: "Cut", key: "cut" },
    { label: "Copy", key: "copy" },
    { label: "Duplicate", key: "duplicate" },
    { label: "Delete", key: "delete" },
  ];

  const columnVisibilityMenuItems: MenuProps["items"] = [
    { label: "ID", key: "id" },
    { label: "Description", key: "description" },
    { label: "Business Description", key: "businessDescription" },
    { label: "Assumptions", key: "assumptions" },
    { label: "Out of Scope", key: "outOfScope" },
    { label: "Status", key: "status" },
    { label: "Labels", key: "labels" },
    { label: "Created At", key: "createdWhen" },
    { label: "Created By", key: "createdBy" },
    { label: "Modified At", key: "modifiedWhen" },
    { label: "Modified By", key: "modifiedBy" },
  ];

  const columns: TableProps<ChainTableItem>["columns"] = [
    {
      title: "Name",
      key: "name",
      dataIndex: "name",
      sorter: compareChainTableItemsByTypeAndName,
      filterDropdown: (props) => <TextColumnFilterDropdown {...props} />,
      render: (_, item) => (
        <Flex vertical={false} gap={4}>
          {item.itemType === FolderItemType.FOLDER ? (
            <FolderOutlined />
          ) : (
            <FileOutlined />
          )}
          <a
            onClick={(event) => {
              event.stopPropagation();
              navigate(
                item.itemType === FolderItemType.FOLDER
                  ? `/chains?folder=${item.id}`
                  : `/chains/${item.id}`,
              );
            }}
          >
            {item.name}
          </a>
        </Flex>
      ),
    },
    {
      title: "ID",
      key: "id",
      dataIndex: "id",
      hidden: !selectedKeys.includes("id"),
      filterDropdown: (props) => <TextColumnFilterDropdown {...props} />,
    },
    {
      title: "Description",
      key: "description",
      dataIndex: "description",
      hidden: !selectedKeys.includes("description"),
      sorter: (a, b) => a.description.localeCompare(b.description),
      filterDropdown: (props) => <TextColumnFilterDropdown {...props} />,
    },
    {
      title: "Business Description",
      key: "businessDescription",
      dataIndex: "businessDescription",
      hidden: !selectedKeys.includes("businessDescription"),
      sorter: (a, b) =>
        a.businessDescription.localeCompare(b.businessDescription),
      filterDropdown: (props) => <TextColumnFilterDropdown {...props} />,
    },
    {
      title: "Assumptions",
      key: "assumptions",
      dataIndex: "assumptions",
      hidden: !selectedKeys.includes("assumptions"),
      sorter: (a, b) => a.assumptions.localeCompare(b.assumptions),
      filterDropdown: (props) => <TextColumnFilterDropdown {...props} />,
    },
    {
      title: "Out of Scope",
      key: "outOfScope",
      dataIndex: "outOfScope",
      hidden: !selectedKeys.includes("outOfScope"),
      sorter: (a, b) => a.outOfScope.localeCompare(b.outOfScope),
      filterDropdown: (props) => <TextColumnFilterDropdown {...props} />,
    },
    {
      title: "Status",
      key: "status",
      hidden: !selectedKeys.includes("status"),
    },
    {
      title: "Labels",
      key: "labels",
      dataIndex: "labels",
      hidden: !selectedKeys.includes("labels"),
      filterDropdown: (props) => (
        <TextColumnFilterDropdown {...props} enableExact />
      ),
      render: (_, item) => <EntityLabels labels={item.labels} />,
    },
    {
      title: "Created By",
      dataIndex: "createdBy",
      key: "createdBy",
      hidden: !selectedKeys.includes("createdBy"),
      render: (_, item) => <>{item.createdBy.username}</>,
      sorter: (a, b) =>
        a.createdBy.username.localeCompare(b.createdBy.username),
      filterDropdown: (props) => <TextColumnFilterDropdown {...props} />,
      // onFilter: getTextColumnFilterFn(
      //   (item) => item.createdBy.username,
      // ),
    },
    {
      title: "Created At",
      dataIndex: "createdWhen",
      key: "createdWhen",
      hidden: !selectedKeys.includes("createdWhen"),
      render: (_, item) => <>{formatTimestamp(item.createdWhen)}</>,
      sorter: (a, b) => a.createdWhen - b.createdWhen,
      filterDropdown: (props) => <TimestampColumnFilterDropdown {...props} />,
      // onFilter: getTimestampColumnFilterFn((item) => item.createdWhen),
    },
    {
      title: "Modified By",
      dataIndex: "modifiedBy",
      key: "modifiedBy",
      hidden: !selectedKeys.includes("modifiedBy"),
      render: (_, item) => <>{item.modifiedBy.username}</>,
      sorter: (a, b) =>
        a.modifiedBy.username.localeCompare(b.modifiedBy.username),
      filterDropdown: (props) => <TextColumnFilterDropdown {...props} />,
      // onFilter: getTextColumnFilterFn(
      //   (item) => item.modifiedBy.username,
      // ),
    },
    {
      title: "Modified At",
      dataIndex: "modifiedWhen",
      key: "modifiedWhen",
      hidden: !selectedKeys.includes("modifiedWhen"),
      render: (_, item) => <>{formatTimestamp(item.modifiedWhen)}</>,
      sorter: (a, b) => a.modifiedWhen - b.modifiedWhen,
      filterDropdown: (props) => <TimestampColumnFilterDropdown {...props} />,
      // onFilter: getTimestampColumnFilterFn((item) => item.modifiedWhen),
    },
    {
      title: "Actions",
      key: "actions",
      width: 70,
      className: "actions-column",
      render: (_, item) => (
        <>
          <Dropdown
            menu={{
              items:
                item.itemType === FolderItemType.FOLDER
                  ? folderMenuItems
                  : chainMenuItems,
            }}
            trigger={["click"]}
            placement="bottomRight"
          >
            <Button size="small" type="text" icon={<MoreOutlined />} />
          </Dropdown>
        </>
      ),
    },
  ];

  const rowSelection: TableRowSelection<ChainTableItem> = {
    type: "checkbox",
    selectedRowKeys,
    checkStrictly: false,
    onChange: onSelectChange,
  };

  return (
    <Flex vertical gap={16} style={{ height: "100%" }}>
      {pathItems && pathItems.length > 0 ? (
        <Breadcrumb items={pathItems} />
      ) : null}
      <Flex vertical={false} gap={8}>
        <Search
          placeholder="Full text search"
          allowClear
          // onSearch={(value) => setFilters({ ...filters, searchString: value })}
        />
        <Dropdown
          menu={{
            items: columnVisibilityMenuItems,
            selectable: true,
            multiple: true,
            selectedKeys,
            onSelect: ({ selectedKeys }) => setSelectedKeys(selectedKeys),
            onDeselect: ({ selectedKeys }) => setSelectedKeys(selectedKeys),
          }}
        >
          <Button icon={<SettingOutlined />} />
        </Dropdown>
      </Flex>
      <Table<ChainTableItem>
        className="flex-table"
        size="small"
        dataSource={tableItems}
        columns={columns}
        rowSelection={rowSelection}
        pagination={false}
        scroll={{ y: "" }}
        rowKey="id"
        rowClassName="clickable-row"
        loading={isLoading}
        expandable={{
          onExpand: async (expanded, item) => {
            if (expanded && !loadedFolders.has(item.id)) {
              return openFolder(item.id);
            }
          },
        }}
      />
      <FloatButtonGroup trigger="hover" icon={<MoreOutlined />}>
        <FloatButton
          tooltip="Deploy selected chains"
          icon={<SendOutlined />}
          // onClick={onDeployBtnClick}
        />
        <FloatButton
          tooltip="Paste"
          icon={<CarryOutOutlined />}
          // onClick={onPasteBtnClick}
        />
        <FloatButton
          tooltip="Compare selected chains"
          icon={<>⇄</>}
          // onClick={onCompareBtnClick}
        />
        <FloatButton
          tooltip="Import chains"
          icon={<ImportOutlined />}
          // onClick={onImportBtnClick}
        />
        <FloatButton
          tooltip="Export selected chains"
          icon={<ExportOutlined />}
          // onClick={onExportBtnClick}
        />
        <FloatButton
          tooltip="Delete selected chains and folders"
          icon={<DeleteOutlined />}
          // onClick={onDeleteBtnClick}
        />
        <FloatButton
          tooltip="Create folder"
          icon={<FolderAddOutlined />}
          // onClick={onCreateChainBtnClick}
        />
        <FloatButton
          tooltip="Create chain"
          icon={<FileAddOutlined />}
          // onClick={onCreateFolderBtnClick}
        />
      </FloatButtonGroup>
    </Flex>
  );
};

export default Chains;
