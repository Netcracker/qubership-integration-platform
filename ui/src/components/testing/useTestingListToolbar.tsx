import { ReactNode, useMemo } from "react";
import { TableToolbar, TableToolbarVariant } from "../table/TableToolbar.tsx";

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
};

/** The toolbar every testing list page builds. */
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

  return toolbar;
}
