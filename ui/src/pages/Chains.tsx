import { Breadcrumb, Button, Flex, message, Modal, Table } from "antd";
import { useNavigate, useSearchParams } from "react-router";
import { useModalsContext } from "../Modals.tsx";
import {
  BulkDeploymentSnapshotAction,
  CatalogItemType,
  ChainCreationRequest,
  ChainItem,
  DeployMode,
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
import {
  ChainCreate,
  type ChainMetadataUpdate,
} from "../components/modal/ChainCreate.tsx";
import { mergeUpdatedChainInFolderItems } from "./chains/mergeUpdatedChainInFolderItems.ts";
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
import styles from "./Chains.module.css";
import { OverridableIcon } from "../icons/IconProvider.tsx";
import {
  DeployChains,
  DeployRequest,
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
import { treeExpandIcon } from "../components/table/TreeExpandIcon.tsx";
import {
  attachResizeToColumns,
  sumScrollXForColumns,
  useTableColumnResize,
} from "../components/table/useTableColumnResize.tsx";
import { TableToolbar } from "../components/table/TableToolbar.tsx";
import commonStyles from "../components/admin_tools/CommonStyle.module.css";
import {
  createActionsColumnBase,
  disableResizeBeforeActions,
} from "../components/table/actionsColumn.ts";
import { ChainDetailsDrawer } from "../components/chains/ChainDetailsDrawer.tsx";
import { useGenerateDds } from "../hooks/useGenerateDds.tsx";

const CHAINS_EXPAND_COLUMN_WIDTH = 48;
const CHAINS_SELECTION_COLUMN_WIDTH = 48;
import { Domain } from "../components/SelectDomains.tsx";

type ChainTableItem = (FolderItem | ChainItem) & {
  children?: ChainTableItem[];
};

function buildTableItems(
  folderItems: (FolderItem | ChainItem)[],
): ChainTableItem[] {
  const itemMap = new Map<string, ChainTableItem>(
    folderItems.map((item) => [item.id, { ...item, children: [] }]),
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

const chainExpandIcon = treeExpandIcon<ChainTableItem>();

// antd's checkbox / expand button / dropdown trigger don't stop click
// propagation, so row onClick fires on them too. Filter those targets out
// so row click only opens the details drawer on empty row area.
const ROW_CLICK_IGNORE_SELECTOR =
  "a, button, input, label, .ant-checkbox, .ant-dropdown-trigger, .ant-table-row-expand-icon, .ant-table-selection-column";

function shouldIgnoreRowClick(target: EventTarget | null): boolean {
  return (
    target instanceof Element && !!target.closest(ROW_CLICK_IGNORE_SELECTOR)
  );
}

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
  const [detailsChain, setDetailsChain] = useState<ChainItem | null>(null);
  const [isDetailsOpen, setIsDetailsOpen] = useState<boolean>(false);
  const notificationService = useNotificationService();
  const { filters, filterButton } = useChainFilters();
  const { showGenerateDdsModal } = useGenerateDds();

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
      const hasChildren = response.some((item) => item.parentId === folderId);
      if (hasChildren) {
        setExpandedRowKeys((keys) => [...keys, folderId]);
      }
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

  const updateChainMetadata = async (
    chainId: string,
    update: ChainMetadataUpdate,
    openChain: boolean,
    newTab: boolean,
  ) => {
    setIsLoading(true);
    try {
      const chain = await api.updateChain(chainId, update);
      setFolderItems(
        mergeUpdatedChainInFolderItems(folderItems, chainId, chain),
      );
      if (openChain) {
        const path = `/chains/${chainId}`;
        if (newTab) {
          window.open(path, "_blank", "noreferrer");
        } else {
          await navigate(path);
        }
      }
    } catch (error) {
      notificationService.requestFailed("Failed to update chain", error);
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
    domains: Domain[],
    snapshotAction: BulkDeploymentSnapshotAction,
  ) => {
    if (chainIds.length === 0) {
      return [];
    }
    setIsLoading(true);
    try {
      return await api.deployToMicroDomain({
        domains: domains.map((domain) => domain.name),
        chainIds,
        snapshotAction,
        mode: DeployMode.APPEND,
      });
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
    onRow: dragDropRowProps,
  } = useTableDragDrop({
    tableItems,
    onMoveChain: moveChain,
    onMoveFolder: moveFolder,
    disabled: isLoading,
  });

  const handleRow = useCallback(
    (record: ChainTableItem) => {
      const base = dragDropRowProps(record);
      return {
        ...base,
        onClick: (event: React.MouseEvent<HTMLTableRowElement>) => {
          if (record.itemType !== CatalogItemType.CHAIN) {
            return;
          }
          if (shouldIgnoreRowClick(event.target)) {
            return;
          }
          setDetailsChain(record as ChainItem);
          setIsDetailsOpen(true);
        },
      };
    },
    [dragDropRowProps],
  );

  const breadcrumbItems: BreadcrumbProps["items"] = useMemo(() => {
    const dropClass = (dropId: string) =>
      dropBreadcrumbId === dropId ? styles.breadcrumbDropTarget : undefined;

    const homeItem = {
      title: (
        <span
          {...(isDragging ? getBreadcrumbDropProps(undefined) : {})}
          className={[
            styles.breadcrumbItem,
            styles.breadcrumbHome,
            isDragging ? dropClass("root") : undefined,
          ]
            .filter(Boolean)
            .join(" ")}
        >
          <a
            href="/chains"
            onClick={(e) => {
              e.preventDefault();
              if (!isDragging) {
                void navigate("/chains");
              }
            }}
          >
            <OverridableIcon name="home" />
          </a>
        </span>
      ),
    };
    const pathItems = folderPath.map((folder, index) => {
      const isClickable = !isDragging && index < folderPath.length - 1;
      const folderUrl = `/chains?folder=${folder.id}`;
      return {
        title: (
          <span
            {...(isDragging ? getBreadcrumbDropProps(folder.id) : {})}
            className={[
              styles.breadcrumbItem,
              isDragging ? dropClass(folder.id) : undefined,
            ]
              .filter(Boolean)
              .join(" ")}
          >
            {isClickable ? (
              <a
                href={folderUrl}
                onClick={(e) => {
                  e.preventDefault();
                  void navigate(folderUrl);
                }}
              >
                {folder.name}
              </a>
            ) : (
              folder.name
            )}
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
            onSubmit={(request: DeployRequest) => deploySelectedChains(request)}
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

  const deploySelectedChains = async (request: DeployRequest) => {
    const chainIds = folderItems
      .filter(
        (i) =>
          selectedRowKeys.includes(i.id) &&
          i.itemType === CatalogItemType.CHAIN,
      )
      .map((i) => i.id);
    return deployChains(chainIds, request.domains, request.snapshotAction);
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
        return showModal({
          component: (
            <ChainCreate
              variant="editChainMetaData"
              chainId={item.id}
              onUpdateMetadata={(id, update, open, tab) =>
                updateChainMetadata(id, update, open, tab)
              }
            />
          ),
        });
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
            href={
              item.itemType === CatalogItemType.FOLDER
                ? `/chains?folder=${item.id}`
                : `/chains/${item.id}`
            }
            onClick={(event) => {
              event.preventDefault();
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
      ...createActionsColumnBase<ChainTableItem>(),
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

  const chainsColumnResize = useTableColumnResize({
    name: 220,
    id: 200,
    description: 240,
    status: 200,
    labels: 200,
    createdBy: 120,
    createdWhen: 168,
    modifiedBy: 120,
    modifiedWhen: 168,
  });

  const columnsWithResize = useMemo(() => {
    const columns = attachResizeToColumns(
      orderedColumns,
      chainsColumnResize.columnWidths,
      chainsColumnResize.createResizeHandlers,
      { minWidth: 80 },
    );
    return disableResizeBeforeActions(columns);
  }, [
    orderedColumns,
    chainsColumnResize.columnWidths,
    chainsColumnResize.createResizeHandlers,
  ]);

  const scrollX = useMemo(
    () =>
      sumScrollXForColumns(columnsWithResize, chainsColumnResize.columnWidths, {
        expandColumnWidth: CHAINS_EXPAND_COLUMN_WIDTH,
        selectionColumnWidth: CHAINS_SELECTION_COLUMN_WIDTH,
      }),
    [columnsWithResize, chainsColumnResize.columnWidths],
  );

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
        <TableToolbar
          leading={
            <Flex
              align="center"
              gap={8}
              style={{ minWidth: 0, flex: 1 }}
              wrap="wrap"
            >
              {breadcrumbItems && breadcrumbItems.length > 0 ? (
                <Breadcrumb
                  items={breadcrumbItems}
                  className={styles.breadcrumb}
                />
              ) : null}
              <CompactSearch
                value={searchString}
                onChange={setSearchString}
                placeholder="Full text search"
                allowClear
                className={commonStyles["searchField"] as string}
              />
            </Flex>
          }
          trailing={
            <>
              {filterButton}
              <span style={{ flexShrink: 0 }}>{columnSettingsButton}</span>
              <ProtectedButton
                require={{ chain: ["read"] }}
                tooltipProps={{
                  title: "Compare selected chains",
                  placement: "bottom",
                }}
                buttonProps={{ iconName: "compare", disabled: true }}
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
            </>
          }
        />
        <Table<ChainTableItem>
          className="flex-table"
          size="small"
          dataSource={tableItems}
          columns={columnsWithResize}
          rowSelection={rowSelection}
          pagination={false}
          scroll={{ x: scrollX, y: "" }}
          components={chainsColumnResize.resizableHeaderComponents}
          rowKey="id"
          rowClassName={(record) =>
            [
              "clickable-row",
              dropTargetId === record.id ? styles.dropTarget : "",
            ]
              .filter(Boolean)
              .join(" ")
          }
          onRow={handleRow}
          loading={isLoading}
          expandable={{
            expandIcon: ({ record, ...rest }) => {
              const hideArrow =
                record.itemType !== CatalogItemType.FOLDER ||
                (loadedFolders.has(record.id) &&
                  (record.children?.length ?? 0) === 0);
              const props = { ...rest, record };
              return chainExpandIcon(
                hideArrow ? { ...props, expandable: false } : props,
              );
            },
            expandedRowKeys,
            onExpand: (expanded, item) => {
              if (!expanded) {
                setExpandedRowKeys((keys) => keys.filter((k) => k !== item.id));
                return;
              }
              if (loadedFolders.has(item.id)) {
                setExpandedRowKeys((keys) => [...keys, item.id]);
              } else {
                void openFolder(item.id);
              }
            },
          }}
        />
      </Flex>
      <ChainDetailsDrawer
        chain={detailsChain}
        open={isDetailsOpen}
        onClose={() => setIsDetailsOpen(false)}
      />
    </>
  );
};

export default Chains;
