import {
  Breadcrumb,
  Button,
  Flex,
  message,
  Modal,
  Table,
} from "antd";
import { useNavigate, useSearchParams } from "react-router";
import { useModalsContext } from "../Modals.tsx";
import {
  BulkDeploymentSnapshotAction,
  CatalogItemType,
  ChainCreationRequest,
  ChainItem,
  FolderItem,
  ListFolderRequest,
  UpdateFolderRequest,
} from "../api/apiTypes.ts";
import React, { useCallback, useEffect, useMemo, useState } from "react";
import { api } from "../api/api.ts";
import { TableProps } from "antd/lib/table";
import { formatTimestamp } from "../misc/format-utils.ts";
import { EntityLabels } from "../components/labels/EntityLabels.tsx";
import { TableRowSelection } from "antd/lib/table/interface";
import { CompactSearch } from "../components/table/CompactSearch.tsx";
import type { BreadcrumbProps } from "antd/es/breadcrumb/Breadcrumb";
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
import { exportAdditionsForChains } from "../misc/export-additions.ts";
import { ImportChains } from "../components/modal/ImportChains.tsx";
import { useNotificationService } from "../hooks/useNotificationService.tsx";
import { useChainFilters } from "../hooks/useChainFilter.ts";
import { GenerateDdsModal } from "../components/modal/GenerateDdsModal.tsx";
import { DdsPreview } from "../components/modal/DdsPreview.tsx";
import styles from "./Chains.module.css";
import { OverridableIcon } from "../icons/IconProvider.tsx";
import {
  DeployChains,
  DeployOptions,
} from "../components/modal/DeployChains.tsx";
import { toStringIds } from "../misc/selection-utils.ts";
import { ProtectedButton } from "../permissions/ProtectedButton.tsx";
import {
  ProtectedDropdown,
  ProtectedMenuItem,
} from "../permissions/ProtectedDropdown.tsx";
import { MenuInfo } from "rc-menu/lib/interface";
import { useColumnSettingsBasedOnColumnsType } from "../components/table/useColumnSettingsButton.tsx";
import { useTableDragDrop } from "../hooks/useTableDragDrop.ts";

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
  const [folderPath, setFolderPath] = useState<FolderItem[]>([]);
  const [expandedRowKeys, setExpandedRowKeys] = useState<React.Key[]>([]);
  const [operation, setOperation] = useState<Operation | undefined>(undefined);
  const [searchString, setSearchString] = useState<string>("");
  const notificationService = useNotificationService();
  const { filters, filterButton } = useChainFilters();

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

    await getPathToFolder(folderId).then((path) => setFolderPath(path));
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

  const deployChains = async (
    chainIds: string[],
    domains: string[],
    snapshotAction: BulkDeploymentSnapshotAction,
  ) => {
    if (chainIds.length === 0) {
      return [];
    }
    setIsLoading(true);
    try {
      return await api.bulkDeploy({ chainIds, domains, snapshotAction });
    } catch (error) {
      notificationService.requestFailed("Failed to deploy chains", error);
      throw error;
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

  const isDestinationInView = useCallback(
    (destinationFolderId?: string): boolean => {
      const currentFolderId = getFolderId();
      // Moving to the current folder level (e.g. from expanded subfolder via breadcrumb)
      if (destinationFolderId === currentFolderId) {
        return true;
      }
      // Moving to a visible subfolder in the table
      return (
        destinationFolderId !== undefined &&
        folderItems.some((i) => i.id === destinationFolderId)
      );
    },
    [getFolderId, folderItems],
  );

  const moveChain = async (chainId: string, destinationFolderId?: string) => {
    setIsLoading(true);
    try {
      const chain = await api.moveChain(chainId, destinationFolderId);
      if (isDestinationInView(destinationFolderId)) {
        setFolderItems((prev) =>
          prev.map((i) =>
            i.id === chainId
              ? { itemType: CatalogItemType.CHAIN, ...chain }
              : i,
          ),
        );
      } else {
        setFolderItems((prev) => prev.filter((i) => i.id !== chainId));
      }
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
      if (isDestinationInView(destinationFolderId)) {
        setFolderItems((prev) =>
          prev.map((item) =>
            item.id === folderId
              ? { ...item, ...response, parentId: destinationFolderId }
              : item,
          ),
        );
      } else {
        const ids = new Set<string>([folderId]);
        traverseElementsDepthFirst(tableItems, (element, path) => {
          if (path.some((i) => i.id === folderId)) {
            ids.add(element.id);
          }
        });
        setFolderItems((prev) => prev.filter((i) => !ids.has(i.id)));
      }
    } catch (error) {
      notificationService.requestFailed("Failed to move folder", error);
    } finally {
      setIsLoading(false);
    }
  };

  const {
    dropTargetId,
    isDragging,
    dropBreadcrumbId,
    getBreadcrumbDropProps,
    onRow,
  } = useTableDragDrop({
    tableItems,
    onMoveChain: moveChain,
    onMoveFolder: moveFolder,
    disabled: isLoading,
  });

  const breadcrumbItems: BreadcrumbProps["items"] = useMemo(() => {
    const dropClass = (dropId: string) =>
      dropBreadcrumbId === dropId ? styles.breadcrumbDropTarget : undefined;

    const handleKeyDown =
      (handler: () => void) => (e: React.KeyboardEvent) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          handler();
        }
      };

    const homeItem = {
      title: (
        <span
          {...(isDragging ? getBreadcrumbDropProps(undefined) : {})}
          className={[
            styles.breadcrumbItem,
            !isDragging ? styles.breadcrumbItemClickable : undefined,
            isDragging ? dropClass("root") : undefined,
          ]
            .filter(Boolean)
            .join(" ")}
          {...(!isDragging
            ? {
                role: "link" as const,
                tabIndex: 0,
                onClick: () => void navigate("/chains"),
                onKeyDown: handleKeyDown(() => void navigate("/chains")),
              }
            : {})}
        >
          <OverridableIcon name="home" />
        </span>
      ),
    };
    const pathItems = folderPath.map((folder, index) => {
      const isClickable = !isDragging && index < folderPath.length - 1;
      const handleClick = () =>
        void navigate(`/chains?folder=${folder.id}`);
      return {
        title: (
          <span
            {...(isDragging ? getBreadcrumbDropProps(folder.id) : {})}
            className={[
              styles.breadcrumbItem,
              isClickable ? styles.breadcrumbItemClickable : undefined,
              isDragging ? dropClass(folder.id) : undefined,
            ]
              .filter(Boolean)
              .join(" ")}
            {...(isClickable
              ? {
                  role: "link" as const,
                  tabIndex: 0,
                  onClick: handleClick,
                  onKeyDown: handleKeyDown(handleClick),
                }
              : {})}
          >
            {folder.name}
          </span>
        ),
      };
    });
    return [homeItem, ...pathItems];
  }, [
    folderPath,
    isDragging,
    dropBreadcrumbId,
    getBreadcrumbDropProps,
    navigate,
  ]);

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
      const chainsFile =
        ids.size === 0
          ? await api.exportAllChains()
          : await api.exportChains(
              Array.from(ids.values()),
              options.exportSubchains,
            );
      const data = [chainsFile];

      data.push(
        ...(await exportAdditionsForChains({
          api,
          chainIdsForUsedSystems: Array.from(ids.values()),
          options: {
            exportServices: options.exportServices,
            exportVariables: options.exportVariables,
          },
        })),
      );

      const nonEmptyData = data.filter((d) => d.size !== 0);
      const archiveData = await mergeZipArchives(nonEmptyData);
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
    return exportChainsWithOptions(toStringIds(selectedRowKeys));
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

  const onImportBtnClick = () => {
    showModal({
      component: (
        <ImportChains
          onSuccess={() => {
            updateFolderItems().catch(() => undefined);
          }}
        />
      ),
    });
  };

  const onDeployBtnClick = () => {
    if (selectedRowKeys.length > 0) {
      showModal({
        component: (
          <DeployChains
            chainCount={selectedRowKeys.length}
            onSubmit={(options: DeployOptions) => deploySelectedChains(options)}
          />
        ),
      });
    }
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
            const items = folderItems.filter((item) => ids.includes(item.id));
            const folderIds = items
              .filter((item) => item.itemType === CatalogItemType.FOLDER)
              .map((item) => item.id);
            const chainIds = items
              .filter((item) => item.itemType === CatalogItemType.CHAIN)
              .map((item) => item.id);
            exportChains(folderIds, chainIds, options).catch(() => undefined);
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

  const deploySelectedChains = async (options: DeployOptions) => {
    const chainIds = folderItems
      .filter(
        (i) =>
          selectedRowKeys.includes(i.id) &&
          i.itemType === CatalogItemType.CHAIN,
      )
      .map((i) => i.id);
    return deployChains(chainIds, options.domains, options.snapshotAction);
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
        ).then(() =>
          messageApi.info("Link to a folder was copied to the clipboard"),
        );
      case "copyChainLink":
        return copyToClipboard(
          `${window.location.origin}/chains/${item.id}`,
        ).then(() =>
          messageApi.info("Link to a chain was copied to the clipboard"),
        );
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

  const folderMenuItems: ProtectedMenuItem[] = [
    {
      label: "Create New Folder",
      key: "createNewFolder",
      require: { folder: ["create"] },
    },
    {
      label: "Create New Chain",
      key: "createNewChain",
      require: { chain: ["create"] },
    },
    { type: "divider" },
    { label: "Expand All", key: "expandAll" },
    { label: "Collapse All", key: "collapseAll" },
    { type: "divider" },
    { label: "Copy Link", key: "copyFolderLink" },
    { label: "Edit", key: "editFolder", require: { folder: ["update"] } },
    { label: "Export", key: "export", require: { chain: ["export"] } },
    { type: "divider" },
    { label: "Cut", key: "cut", require: { folder: ["delete"] } },
    {
      label: "Paste",
      key: "paste",
      disabled: operation === undefined,
      require: { anyOf: [{ folder: ["update"] }, { chain: ["create"] }] },
    },
    { label: "Delete", key: "deleteFolder", require: { folder: ["delete"] } },
  ];

  const chainMenuItems: ProtectedMenuItem[] = [
    { label: "Copy Link", key: "copyChainLink" },
    { label: "Edit", key: "editChain", require: { chain: ["update"] } },
    { label: "Export", key: "export", require: { chain: ["export"] } },
    {
      label: "Generate DDS",
      key: "generateDDS",
      require: { chain: ["read"] },
    },
    { type: "divider" },
    { label: "Cut", key: "cut", require: { chain: ["delete"] } },
    { label: "Copy", key: "copy", require: { chain: ["create"] } },
    {
      label: "Duplicate",
      key: "duplicateChain",
      require: { chain: ["create"] },
    },
    { label: "Delete", key: "deleteChain", require: { chain: ["delete"] } },
  ];

  const columns: TableProps<ChainTableItem>["columns"] = [
    {
      title: "Name",
      key: "name",
      dataIndex: "name",
      sorter: compareChainTableItemsByTypeAndName,
      render: (_, item) => (
        <Flex vertical={false} gap={4}>
          {item.itemType === CatalogItemType.FOLDER ? (
            <OverridableIcon name="folder" />
          ) : (
            <OverridableIcon name="file" />
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
      hidden: true,
    },
    {
      title: "Description",
      key: "description",
      dataIndex: "description",
      sorter: (a, b) => a.description.localeCompare(b.description),
    },
    {
      title: "Status",
      key: "status",
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
      render: (_, item) =>
        item.itemType === CatalogItemType.CHAIN ? (
          <EntityLabels labels={(item as ChainItem).labels} />
        ) : null,
    },
    {
      title: "Created By",
      dataIndex: "createdBy",
      key: "createdBy",
      hidden: true,
      render: (_, item) => <>{item.createdBy?.username}</>,
      sorter: (a, b) =>
        (a.createdBy?.username ?? "").localeCompare(
          b.createdBy?.username ?? "",
        ),
    },
    {
      title: "Created At",
      dataIndex: "createdWhen",
      key: "createdWhen",
      hidden: true,
      render: (_, item) => (
        <>{item.createdWhen ? formatTimestamp(item.createdWhen) : "-"}</>
      ),
      sorter: (a, b) => (a.createdWhen ?? 0) - (b.createdWhen ?? 0),
    },
    {
      title: "Modified By",
      dataIndex: "modifiedBy",
      key: "modifiedBy",
      hidden: true,
      render: (_, item) => <>{item.modifiedBy?.username ?? "-"}</>,
      sorter: (a, b) =>
        (a.modifiedBy?.username ?? "").localeCompare(
          b.modifiedBy?.username ?? "",
        ),
    },
    {
      title: "Modified At",
      dataIndex: "modifiedWhen",
      key: "modifiedWhen",
      hidden: true,
      render: (_, item) => (
        <>{item.modifiedWhen ? formatTimestamp(item.modifiedWhen) : "-"}</>
      ),
      sorter: (a, b) => (a.modifiedWhen ?? 0) - (b.modifiedWhen ?? 0),
    },
    {
      title: "",
      key: "actions",
      width: 40,
      className: "actions-column",
      render: (_, item) => (
        <>
          <ProtectedDropdown
            menu={{
              items:
                item.itemType === CatalogItemType.FOLDER
                  ? folderMenuItems
                  : chainMenuItems,
              // @ts-expect-error Some mistake with types: onClick presents in menu props.
              onClick: ({ key }: MenuInfo) =>
                void onContextMenuItemClick(item, key),
            }}
            trigger={["click"]}
            placement="bottomRight"
          >
            <Button
              size="small"
              type="text"
              icon={<OverridableIcon name="more" />}
            />
          </ProtectedDropdown>
        </>
      ),
    },
  ];

  const { orderedColumns, columnSettingsButton } =
    useColumnSettingsBasedOnColumnsType<ChainTableItem>("chainsTable", columns);

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
      <Flex vertical gap={16} className={styles.container}>
        <Flex vertical={false} gap={4} align="center">
          {breadcrumbItems && breadcrumbItems.length > 0 ? (
            <Breadcrumb items={breadcrumbItems} className={styles.breadcrumb} />
          ) : null}
          <div style={{ flex: 1 }} />
          <CompactSearch
            value={searchString}
            onChange={setSearchString}
            placeholder="Full text search"
            allowClear
            style={{ width: 500, flex: "none" }}
          />
          {filterButton}
          {columnSettingsButton}
          <ProtectedButton
            require={{ chain: ["read"] }}
            tooltipProps={{
              title: "Compare selected chains",
              placement: "bottom",
            }}
            buttonProps={{ icon: <>⇄</>, disabled: true }}
          />
          <ProtectedButton
            require={{ chain: ["create"] }}
            tooltipProps={{ title: "Paste", placement: "bottom" }}
            buttonProps={{
              iconName: "carryOut",
              onClick: () => {
                Promise.resolve(pasteItem(getFolderId())).catch(
                  () => undefined,
                );
              },
            }}
          />
          <ProtectedButton
            require={{ deployment: ["create"] }}
            tooltipProps={{
              title: "Deploy selected chains",
              placement: "bottom",
            }}
            buttonProps={{
              iconName: "send",
              onClick: onDeployBtnClick,
            }}
          />
          <ProtectedButton
            require={{ chain: ["import"] }}
            tooltipProps={{
              title: "Export selected chains",
              placement: "bottom",
            }}
            buttonProps={{
              iconName: "cloudDownload",
              onClick: onExportBtnClick,
            }}
          />
          <ProtectedButton
            require={{ chain: ["export"] }}
            tooltipProps={{ title: "Import chains", placement: "bottom" }}
            buttonProps={{
              iconName: "cloudUpload",
              onClick: onImportBtnClick,
            }}
          />
          <ProtectedButton
            require={{ chain: ["delete"] }}
            tooltipProps={{
              title: "Delete selected chains and folders",
              placement: "bottom",
            }}
            buttonProps={{
              iconName: "delete",
              onClick: onDeleteBtnClick,
            }}
          />
          <ProtectedDropdown
            menu={{
              items: [
                {
                  key: "folder",
                  label: "New Folder",
                  onClick: () => onCreateFolderBtnClick(getFolderId()),
                  require: { folder: ["create"] },
                },
                {
                  key: "chain",
                  label: "New Chain",
                  onClick: () => onCreateChainBtnClick(getFolderId()),
                  require: { chain: ["create"] },
                },
              ],
            }}
            trigger={["click"]}
          >
            <Button type="primary">
              Create <OverridableIcon name="down" />
            </Button>
          </ProtectedDropdown>
        </Flex>
        <Table<ChainTableItem>
          className="flex-table"
          size="small"
          dataSource={tableItems}
          columns={orderedColumns}
          rowSelection={rowSelection}
          pagination={false}
          scroll={{ y: "" }}
          rowKey="id"
          rowClassName={(record) =>
            [
              "clickable-row",
              dropTargetId === record.id ? styles.dropTarget : "",
            ]
              .filter(Boolean)
              .join(" ")
          }
          onRow={onRow}
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
      </Flex>
    </>
  );
};

export default Chains;
