import { DependencyList, ReactNode, useMemo } from "react";
import { TableToolbar, TableToolbarVariant } from "../table/TableToolbar.tsx";
import { useRegisterChainHeaderActions } from "../../pages/ChainHeaderActionsContext.tsx";

type TestingListToolbarOptions = {
  variant: TableToolbarVariant;
  searchValue: string;
  onSearchChange: (value: string) => void;
  /** Left out by a page that filters as you type rather than on Enter. */
  onSearchConfirm?: () => void;
  searchPlaceholder: string;
  leading?: ReactNode;
  filterButton?: ReactNode;
  columnSettingsButton?: ReactNode;
  actions: ReactNode;
  /** A chain tab hands its toolbar to the chain header; an admin page renders it itself. */
  registerInChainHeader: boolean;
  /**
   * The state the toolbar reads, not the toolbar node: the filter and column-settings
   * buttons are fresh elements on every render, and depending on them would loop
   * through the header's own re-render.
   */
  registerDependencies: DependencyList;
};

/** The toolbar every testing list page builds, and the chain-header registration that follows it. */
export function useTestingListToolbar({
  variant,
  searchValue,
  onSearchChange,
  onSearchConfirm,
  searchPlaceholder,
  leading,
  filterButton,
  columnSettingsButton,
  actions,
  registerInChainHeader,
  registerDependencies,
}: TestingListToolbarOptions): ReactNode {
  const toolbar = useMemo(
    () => (
      <TableToolbar
        variant={variant}
        leading={leading}
        search={{
          value: searchValue,
          onChange: onSearchChange,
          onSearchConfirm,
          placeholder: searchPlaceholder,
          allowClear: true,
        }}
        filterButton={filterButton}
        columnSettingsButton={columnSettingsButton}
        actions={actions}
      />
    ),
    [
      variant,
      leading,
      searchValue,
      onSearchChange,
      onSearchConfirm,
      searchPlaceholder,
      filterButton,
      columnSettingsButton,
      actions,
    ],
  );

  useRegisterChainHeaderActions(
    registerInChainHeader ? toolbar : undefined,
    registerDependencies,
  );

  return toolbar;
}
