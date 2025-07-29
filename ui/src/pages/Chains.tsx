import {
  Breadcrumb,
  Button,
  Dropdown,
  Flex,
  FloatButton,
  MenuProps,
  message,
  Modal,
  Table,
} from "antd";
import { useNavigate, useSearchParams } from "react-router";
import {
  CarryOutOutlined,
  CloudDownloadOutlined,
  CloudUploadOutlined,
  DeleteOutlined,
  FileAddOutlined,
  FileOutlined,
  FolderAddOutlined,
  FolderOutlined,
  HomeOutlined,
  MoreOutlined,
  SendOutlined,
  SettingOutlined,
} from "@ant-design/icons";
import { useModalsContext } from "../Modals.tsx";
import {
  CatalogItemType,
  ChainCreationRequest,
  ChainItem,
  FolderFilter,
  FolderItem,
  ListFolderRequest,
  UpdateFolderRequest,
} from "../api/apiTypes.ts";
import React, { useCallback, useEffect, useState } from "react";
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
import { DeploymentsCumulativeState } from "../components/deployment_runtime_states/DeploymentsCumulativeState.tsx";
import { FolderEdit, FolderEditMode } from "../components/modal/FolderEdit.tsx";
import { ChainCreate } from "../components/modal/ChainCreate.tsx";
import { copyToClipboard } from "../misc/clipboard-util.ts";
import { traverseElementsDepthFirst } from "../misc/tree-utils.ts";
import {
  ExportChainOptions,
  ExportChains,
} from "../components/modal/ExportChains.tsx";
import { downloadFile, mergeZipArchives } from "../misc/download-utils.ts";
import { ImportChains } from "../components/modal/ImportChains.tsx";
import { useNotificationService } from "../hooks/useNotificationService.tsx";
import { commonVariablesApi } from "../api/admin-tools/variables/commonVariablesApi.ts";
import { Filter } from "../components/table/filter/Filter.tsx";
import { useChainFilters } from "../hooks/useChainFilter.ts";
import { FilterButton } from "../components/table/filter/FilterButton.tsx";
import { FilterItemState } from "../components/table/filter/FilterItem.tsx";
import { GenerateDdsModal } from "../components/modal/GenerateDdsModal.tsx";
import { DdsPreview } from "../components/modal/DdsPreview.tsx";

type ChainTableItem = (FolderItem | ChainItem) & {
  children?: ChainTableItem[];
};

function buildTableItems(
  folderItems: (FolderItem | ChainItem)[],
): ChainTableItem[] {
  const itemMap = new Map<string, ChainTableItem>(
    folderItems.map((item) => [
      item.id,
      item.itemType === CatalogItemType.FOLDER
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
      parentItem!.children = parentItem?.children
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
    const values = Object.values(CatalogItemType);
    return values.indexOf(a.itemType) - values.indexOf(b.itemType);
  } else {
    return a.name.localeCompare(b.name);
  }
}

function buildPathItems(path: FolderItem[]): BreadcrumbProps["items"] {
  const items = path.map((folder, index) => ({
    title: folder.name,
    href: index < path.length - 1 ? `/chains?folder=${folder.id}` : undefined,
  }));
  return [
    {
      href: "/chains",
      title: <HomeOutlined />,
    },
    ...items,
  ];
}

type OperationType = "move" | "copy";

type Operation = {
  item: ChainTableItem;
  operation: OperationType;
};

const Chains = () => {
  const navigate = useNavigate();
  const { showModal } = useModalsContext();
  const [messageApi, contextHolder] = message.useMessage();
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
  const [expandedRowKeys, setExpandedRowKeys] = useState<React.Key[]>([]);
  const [operation, setOperation] = useState<Operation | undefined>(undefined);
  const [searchString, setSearchString] = useState<string>("");
  const notificationService = useNotificationService();
  const { filterColumns, filterItemStates, setFilterItemStates } =
    useChainFilters();
  const [filters, setFilters] = useState<FolderFilter[]>([]);

  const getFolderId = useCallback((): string | undefined => {
    return searchParams.get("folder") ?? undefined;
  }, [searchParams]);

  const getPathToFolder = useCallback(
    async (folderId: string | undefined) => {
      if (!folderId) {
        return [];
      }
      setIsLoading(true);
      try {
        return await api.getPathToFolder(folderId);
      } catch (error) {
        notificationService.requestFailed(
          "Failed to get path to folder",
          error,
        );
        return [];
      } finally {
        setIsLoading(false);
      }
    },
    [notificationService],
  );

  const listFolder = useCallback(
    async (folderId: string | undefined) => {
      const request: ListFolderRequest = {
        folderId,
        filters: filters,
        searchString,
      };
      setIsLoading(true);
      try {
        return await api.listFolder(request);
      } catch (error) {
        notificationService.requestFailed(
          "Failed to get folder content",
          error,
        );
        return [];
      } finally {
        setIsLoading(false);
      }
    },
    [notificationService, searchString, filters],
  );

  const updateFolderItems = useCallback(async () => {
    const folderId = getFolderId();
    await listFolder(folderId).then((items) => {
      setIsLoading(true);
      setExpandedRowKeys([]);
      setLoadedFolders(new Set());
      setFolderItems(items);
      setIsLoading(false);
    });

    await getPathToFolder(folderId).then((path) =>
      setPathItems(buildPathItems(path)),
    );
  }, [getFolderId, getPathToFolder, listFolder]);

  const openFolder = async (folderId: string) => {
    return listFolder(folderId).then((response) => {
      if (!response) {
        return;
      }
      setFolderItems([
        ...response,
        ...folderItems.filter(
          (item) => !response.some((i) => i.id === item.id),
        ),
      ]);
      const s = new Set(loadedFolders);
      s.add(folderId);
      setLoadedFolders(s);
    });
  };

  const createFolder = async (
    request: UpdateFolderRequest,
    openFolder: boolean,
    newTab: boolean,
  ) => {
    setIsLoading(true);
    try {
      const response = await api.createFolder(request);
      setFolderItems([...folderItems, response]);
      if (openFolder) {
        const path = `/chains?folder=${response.id}`;
        if (newTab) {
          window.open(path, "_blank", "noreferrer");
        } else {
          await navigate(path);
        }
      }
    } catch (error) {
      notificationService.requestFailed("Failed to create folder", error);
    } finally {
      setIsLoading(false);
    }
  };

  const updateFolder = async (
    folderId: string,
    changes: UpdateFolderRequest,
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
      notificationService.requestFailed("Failed to update folder", error);
    } finally {
      setIsLoading(false);
    }
  };

  const deleteFolder = async (folderId: string) => {
    setIsLoading(true);
    try {
      await api.deleteFolder(folderId);
      const ids = new Set<string>([folderId]);
      traverseElementsDepthFirst(tableItems, (element, path) => {
        if (path.some((i) => i.id === folderId)) {
          ids.add(element.id);
        }
      });
      setFolderItems(folderItems.filter((i) => !ids.has(i.id)));
    } catch (error) {
      notificationService.requestFailed("Failed to delete folder", error);
    } finally {
      setIsLoading(false);
    }
  };

  const deleteFolders = async (folderIds: string[]) => {
    if (folderIds.length === 0) {
      return;
    }
    setIsLoading(true);
    try {
      await api.deleteFolders(folderIds);
      const ids = new Set<string>(folderIds);
      traverseElementsDepthFirst(tableItems, (element, path) => {
        if (path.some((i) => folderIds.includes(i.id))) {
          ids.add(element.id);
        }
      });
      setFolderItems(folderItems.filter((i) => !ids.has(i.id)));
    } catch (error) {
      notificationService.requestFailed("Failed to delete folders", error);
    } finally {
      setIsLoading(false);
    }
  };

  const createChain = async (
    request: ChainCreationRequest,
    openChain: boolean,
    newTab: boolean,
  ) => {
    setIsLoading(true);
    try {
      const chain = await api.createChain(request);
      setFolderItems([
        ...folderItems,
        { itemType: CatalogItemType.CHAIN, ...chain },
      ]);
      if (openChain) {
        const path = `/chains/${chain.id}`;
        if (newTab) {
          window.open(path, "_blank", "noreferrer");
        } else {
          await navigate(path);
        }
      }
    } catch (error) {
      notificationService.requestFailed("Failed to create chain", error);
    } finally {
      setIsLoading(false);
    }
  };

  const deleteChain = async (chainId: string) => {
    setIsLoading(true);
    try {
      await api.deleteChain(chainId);
      setFolderItems(folderItems.filter((item) => item.id !== chainId));
    } catch (error) {
      notificationService.requestFailed("Failed to delete chain", error);
    } finally {
      setIsLoading(false);
    }
  };

  const deleteChains = async (chainIds: string[]) => {
    if (chainIds.length === 0) {
      return;
    }
    setIsLoading(true);
    try {
      await api.deleteChains(chainIds);
      setFolderItems(folderItems.filter((item) => !chainIds.includes(item.id)));
    } catch (error) {
      notificationService.requestFailed("Failed to delete chains", error);
    } finally {
      setIsLoading(false);
    }
  };

  const duplicateChain = async (chainId: string) => {
    setIsLoading(true);
    try {
      const chain = await api.duplicateChain(chainId);
      setFolderItems([
        ...folderItems,
        { itemType: CatalogItemType.CHAIN, ...chain },
      ]);
    } catch (error) {
      notificationService.requestFailed("Failed to duplicate chain", error);
    } finally {
      setIsLoading(false);
    }
  };

  const copyChain = async (chainId: string, destinationFolderId?: string) => {
    setIsLoading(true);
    try {
      const chain = await api.copyChain(chainId, destinationFolderId);
      setFolderItems([
        ...folderItems,
        { itemType: CatalogItemType.CHAIN, ...chain },
      ]);
    } catch (error) {
      notificationService.requestFailed("Failed to copy chain", error);
    } finally {
      setIsLoading(false);
    }
  };

  const moveChain = async (chainId: string, destinationFolderId?: string) => {
    setIsLoading(true);
    try {
      const chain = await api.moveChain(chainId, destinationFolderId);
      setFolderItems(
        folderItems.map((i) =>
          i.id === chainId ? { itemType: CatalogItemType.CHAIN, ...chain } : i,
        ),
      );
    } catch (error) {
      notificationService.requestFailed("Failed to move chain", error);
    } finally {
      setIsLoading(false);
    }
  };

  const moveFolder = async (folderId: string, destinationFolderId?: string) => {
    setIsLoading(true);
    try {
      const response = await api.moveFolder(folderId, destinationFolderId);
      setFolderItems(
        folderItems.map((item) =>
          item.id === folderId ? { ...item, ...response } : item,
        ),
      );
    } catch (error) {
      notificationService.requestFailed("Failed to move folder", error);
    } finally {
      setIsLoading(false);
    }
  };

  const pasteItem = async (destinationFolderId?: string) => {
    if (!operation) {
      return;
    }
    switch (operation.operation) {
      case "copy":
        return operation.item.itemType === CatalogItemType.CHAIN
          ? copyChain(operation.item.id, destinationFolderId).then(() =>
              setOperation(undefined),
            )
          : null;
      case "move":
        return operation.item.itemType === CatalogItemType.CHAIN
          ? moveChain(operation.item.id, destinationFolderId).then(() =>
              setOperation(undefined),
            )
          : moveFolder(operation.item.id, destinationFolderId).then(() =>
              setOperation(undefined),
            );
    }
  };

  const exportChains = async (
    folderIds: string[],
    chainIds: string[],
    options: ExportChainOptions,
  ) => {
    try {
      const ids = new Set(chainIds);
      const chainsInFolders = await Promise.all(
        folderIds.map((folderId) => api.getNestedChains(folderId)),
      );
      chainsInFolders
        .flatMap((chains) => chains)
        .forEach((chain) => ids.add(chain.id));
      if (ids.size === 0) {
        return;
      }
      const chainsFile =
        ids.size === 0
          ? await api.exportAllChains()
          : await api.exportChains(
              Array.from(ids.values()),
              options.exportSubchains,
            );
      const data = [chainsFile];

      if (options.exportServices) {
        const usedServices = await api.getServicesUsedByChains(
          Array.from(ids.values()),
        );
        if (usedServices.length > 0) {
          const serviceIds = usedServices.map((i) => i.systemId);
          const modelIds = usedServices.flatMap(
            (i) => i.usedSystemModelIds ?? [],
          );
          const servicesData = await api.exportServices(serviceIds, modelIds);
          data.push(servicesData);
        }
      }

      if (options.exportVariables) {
        const variablesData = await commonVariablesApi.exportVariables(
          [],
          true,
        );
        data.push(variablesData);
      }

      const archiveData = await mergeZipArchives(data);
      const file = new File([archiveData], chainsFile.name, {
        type: "application/zip",
      });
      downloadFile(file);
    } catch (error) {
      notificationService.requestFailed("Failed to export chains", error);
    }
  };

  const showEditFolderModal = (
    mode: FolderEditMode,
    name?: string,
    description?: string,
    parentOrItemId?: string,
  ) => {
    showModal({
      component: (
        <FolderEdit
          mode={mode}
          name={name}
          description={description}
          onSubmit={async (name, description, openFolder, newTab) => {
            return mode === "create"
              ? createFolder(
                  { name, description, parentId: parentOrItemId },
                  openFolder,
                  newTab,
                )
              : updateFolder(parentOrItemId ?? "", { name, description });
          }}
        />
      ),
    });
  };

  const showGenerateDdsModal = (chainId: string) => {
    showModal({
      component: (
        <GenerateDdsModal
          onSubmit={(templateId, fileName) => {
            showDdsModal(chainId, templateId, fileName);
          }}
        />
      ),
    });
  };

  const showDdsModal = (
    chainId: string,
    templateId: string,
    fileName: string,
  ) => {
    showModal({
      component: (
        <DdsPreview
          chainId={chainId}
          templateId={templateId}
          fileName={fileName}
        />
      ),
    });
  };

  const onCreateFolderBtnClick = (parentId?: string) => {
    showEditFolderModal("create", undefined, undefined, parentId);
  };

  const onCreateChainBtnClick = (parentId?: string) => {
    showModal({
      component: (
        <ChainCreate
          onSubmit={async (request, openChain, newTab) => {
            return createChain({ ...request, parentId }, openChain, newTab);
          }}
        />
      ),
    });
  };

  const onExportBtnClick = () => {
    return exportChainsWithOptions(selectedRowKeys.map((k) => k.toString()));
  };

  const onDeleteBtnClick = () => {
    if (selectedRowKeys.length > 0) {
      Modal.confirm({
        title: "Delete selected",
        content: `Are you sure you want to delete selected folders and chains?`,
        onOk: async () => deleteSelectedFoldersAndChains(),
      });
    }
  };

  const addFilter = () => {
    showModal({
      component: (
        <Filter
          filterColumns={filterColumns}
          filterItemStates={filterItemStates}
          onApplyFilters={applyFilters}
        />
      ),
    });
  };

  const applyFilters = (filterItems: FilterItemState[]) => {
    setFilterItemStates(filterItems);

    const f = filterItems.map(
      (filterItem): FolderFilter => ({
        column: filterItem.columnValue!,
        condition: filterItem.conditionValue!,
        value: filterItem.value,
      }),
    );
    setFilters(f);
  };

  const onImportBtnClick = () => {
    showModal({
      component: <ImportChains onSuccess={() => void updateFolderItems()} />,
    });
  };

  const exportChainsWithOptions = (ids: string[]) => {
    const multiple =
      ids.length !== 1 ||
      folderItems.find((i) => i.id === ids[0])?.itemType ===
        CatalogItemType.FOLDER;
    showModal({
      component: (
        <ExportChains
          multiple={multiple}
          onSubmit={(options) => {
            const items = folderItems.filter((i) => ids.includes(i.id));
            const folderIds = items
              .filter((i) => i.itemType === CatalogItemType.FOLDER)
              .map((i) => i.id);
            const chainIds = items
              .filter((i) => i.itemType === CatalogItemType.CHAIN)
              .map((i) => i.id);
            void exportChains(folderIds, chainIds, options);
          }}
        />
      ),
    });
  };

  const deleteSelectedFoldersAndChains = async () => {
    const folderIds = folderItems
      .filter(
        (i) =>
          selectedRowKeys.includes(i.id) &&
          i.itemType === CatalogItemType.FOLDER,
      )
      .map((i) => i.id);
    await deleteFolders(folderIds);
    const chainIds = folderItems
      .filter(
        (i) =>
          selectedRowKeys.includes(i.id) &&
          i.itemType === CatalogItemType.CHAIN,
      )
      .map((i) => i.id);
    await deleteChains(chainIds);
  };

  const onContextMenuItemClick = async (item: FolderItem, key: React.Key) => {
    switch (key) {
      case "createNewFolder":
        return showEditFolderModal("create", undefined, undefined, item.id);
      case "createNewChain":
        return onCreateChainBtnClick(item.id);
      case "editFolder":
        return showEditFolderModal(
          "update",
          item.name,
          item.description,
          item.id,
        );
      case "editChain":
        return navigate(`/chains/${item.id}`);
      case "copyFolderLink":
        return copyToClipboard(
          `${window.location.origin}/chains?folder=${item.id}`,
        ).then(() => {
          messageApi.info("Link to a folder was copied to the clipboard");
        });
      case "copyChainLink":
        return copyToClipboard(
          `${window.location.origin}/chains/${item.id}`,
        ).then(() => {
          messageApi.info("Link to a chain was copied to the clipboard");
        });
      case "deleteFolder":
        return Modal.confirm({
          title: "Delete Folder",
          content: `Are you sure you want to delete "${item.name}" folder?`,
          onOk: async () => deleteFolder(item.id),
        });
      case "deleteChain":
        return Modal.confirm({
          title: "Delete Chain",
          content: `Are you sure you want to delete "${item.name}" chain?`,
          onOk: async () => deleteChain(item.id),
        });
      case "duplicateChain":
        return duplicateChain(item.id);
      case "cut":
        return setOperation({ item, operation: "move" });
      case "copy":
        return setOperation({ item, operation: "copy" });
      case "paste":
        return pasteItem(item.id);
      case "export":
        return exportChainsWithOptions([item.id]);
      case "generateDDS":
        return showGenerateDdsModal(item.id);
      default:
        return Modal.error({ title: "Not implemented yet" });
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
    { label: "Copy Link", key: "copyFolderLink" },
    { label: "Edit", key: "editFolder" },
    { label: "Export", key: "export" },
    { type: "divider" },
    { label: "Cut", key: "cut" },
    { label: "Paste", key: "paste", disabled: operation === undefined },
    { label: "Delete", key: "deleteFolder" },
  ];

  const chainMenuItems: MenuProps["items"] = [
    { label: "Copy Link", key: "copyChainLink" },
    { label: "Edit", key: "editChain" },
    { label: "Export", key: "export" },
    { label: "Generate DDS", key: "generateDDS" },
    { type: "divider" },
    { label: "Cut", key: "cut" },
    { label: "Copy", key: "copy" },
    { label: "Duplicate", key: "duplicateChain" },
    { label: "Delete", key: "deleteChain" },
  ];

  const columnVisibilityMenuItems: MenuProps["items"] = [
    { label: "ID", key: "id" },
    { label: "Description", key: "description" },
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
          {item.itemType === CatalogItemType.FOLDER ? (
            <FolderOutlined />
          ) : (
            <FileOutlined />
          )}
          <a
            onClick={(event) => {
              event.stopPropagation();
              void navigate(
                item.itemType === CatalogItemType.FOLDER
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
      title: "Status",
      key: "status",
      hidden: !selectedKeys.includes("status"),
      render: (_, item) => (
        <>
          {item.itemType === CatalogItemType.FOLDER ? null : (
            <DeploymentsCumulativeState chainId={item.id} />
          )}
        </>
      ),
    },
    {
      title: "Labels",
      key: "labels",
      dataIndex: "labels",
      hidden: !selectedKeys.includes("labels"),
      filterDropdown: (props) => (
        <TextColumnFilterDropdown {...props} enableExact />
      ),
      render: (_, item) =>
        item.itemType === CatalogItemType.CHAIN ? (
          <EntityLabels labels={(item as ChainItem).labels} />
        ) : null,
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
                item.itemType === CatalogItemType.FOLDER
                  ? folderMenuItems
                  : chainMenuItems,
              onClick: ({ key }) => void onContextMenuItemClick(item, key),
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

  useEffect(() => {
    void updateFolderItems();
  }, [updateFolderItems]);

  useEffect(() => {
    setTableItems(buildTableItems(folderItems));
  }, [folderItems]);

  return (
    <>
      {contextHolder}
      <Flex vertical gap={16} style={{ height: "100%" }}>
        {pathItems && pathItems.length > 0 ? (
          <Breadcrumb items={pathItems} />
        ) : null}
        <Flex vertical={false} gap={8}>
          <Search
            placeholder="Full text search"
            allowClear
            onSearch={(value) => setSearchString(value)}
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
          <FilterButton count={filterItemStates.length} onClick={addFilter} />
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
            expandedRowKeys,
            onExpandedRowsChange: (rowKeys) => {
              setExpandedRowKeys([...rowKeys]);
            },
            onExpand: (expanded, item) => {
              if (expanded && !loadedFolders.has(item.id)) {
                void openFolder(item.id);
              }
            },
          }}
        />
        <FloatButtonGroup trigger="hover" icon={<MoreOutlined />}>
          <FloatButton
            tooltip={{ title: "Deploy selected chains", placement: "left" }}
            icon={<SendOutlined />}
            // onClick={onDeployBtnClick}
          />
          <FloatButton
            tooltip={{ title: "Paste", placement: "left" }}
            icon={<CarryOutOutlined />}
            onClick={() => void pasteItem(getFolderId())}
          />
          <FloatButton
            tooltip={{ title: "Compare selected chains", placement: "left" }}
            icon={<>⇄</>}
            // onClick={onCompareBtnClick}
          />
          <FloatButton
            tooltip={{ title: "Import chains", placement: "left" }}
            icon={<CloudUploadOutlined />}
            onClick={onImportBtnClick}
          />
          <FloatButton
            tooltip={{ title: "Export selected chains", placement: "left" }}
            icon={<CloudDownloadOutlined />}
            onClick={onExportBtnClick}
          />
          <FloatButton
            tooltip={{
              title: "Delete selected chains and folders",
              placement: "left",
            }}
            icon={<DeleteOutlined />}
            onClick={onDeleteBtnClick}
          />
          <FloatButton
            tooltip={{ title: "Create folder", placement: "left" }}
            icon={<FolderAddOutlined />}
            onClick={() => onCreateFolderBtnClick(getFolderId())}
          />
          <FloatButton
            tooltip={{ title: "Create chain", placement: "left" }}
            icon={<FileAddOutlined />}
            onClick={() => onCreateChainBtnClick(getFolderId())}
          />
        </FloatButtonGroup>
      </Flex>
    </>
  );
};

export default Chains;
