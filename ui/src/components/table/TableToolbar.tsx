import React, {
  type ComponentProps,
  type CSSProperties,
  type ReactNode,
  useState,
} from "react";
import { Flex } from "antd";
import clsx from "clsx";
import { CompactSearch, type CompactSearchProps } from "./CompactSearch.tsx";
import { useNotificationService } from "../../hooks/useNotificationService";
import { ProtectedButton } from "../../permissions/ProtectedButton";
import styles from "./TableToolbar.module.css";

export type TableToolbarVariant = "admin" | "chain-tab" | "default";

export type TableToolbarSearch = Pick<
  CompactSearchProps,
  | "value"
  | "onChange"
  | "onClear"
  | "onSearchConfirm"
  | "placeholder"
  | "allowClear"
> & {
  className?: string;
  style?: CSSProperties;
};

export type TableToolbarRefresh = {
  onRefresh: () => unknown;
  loading?: boolean;
  disabled?: boolean;
  require?: ComponentProps<typeof ProtectedButton>["require"];
  "data-testid"?: string;
};

export type TableToolbarProps = {
  refresh?: TableToolbarRefresh;
  variant?: TableToolbarVariant;
  search?: TableToolbarSearch;
  filterButton?: ReactNode;
  columnSettingsButton?: ReactNode;
  actions?: ReactNode;
  leading?: ReactNode;
  middle?: ReactNode;
  trailing?: ReactNode;
  className?: string;
  actionsClassName?: string;
  "data-testid"?: string;
};

const variantClassNameByVariant: Record<TableToolbarVariant, string> = {
  admin: styles.admin,
  "chain-tab": styles.chainTab,
  default: styles.default,
};

const searchClassNameByVariant: Partial<Record<TableToolbarVariant, string>> = {
  admin: styles.adminSearch,
  "chain-tab": styles.chainTabSearch,
};

const TableToolbarRefreshButton: React.FC<{
  refresh: TableToolbarRefresh;
}> = ({ refresh }) => {
  const [refreshing, setRefreshing] = useState(false);
  const notificationService = useNotificationService();
  const handleRefresh = async () => {
    setRefreshing(true);
    try {
      await refresh.onRefresh();
    } catch (error) {
      notificationService.requestFailed("Failed to refresh table", error);
    } finally {
      setRefreshing(false);
    }
  };

  return (
    <ProtectedButton
      require={refresh.require ?? {}}
      tooltipProps={{ title: "Refresh", placement: "bottom" }}
      buttonProps={{
        "aria-label": "Refresh",
        ...(refresh["data-testid"]
          ? { "data-testid": refresh["data-testid"] }
          : {}),
        iconName: "refresh",
        loading: refresh.loading || refreshing,
        disabled: refresh.disabled,
        onClick: () => void handleRefresh(),
      }}
    />
  );
};

export const TableToolbar: React.FC<TableToolbarProps> = ({
  variant = "default",
  search,
  refresh,
  columnSettingsButton,
  filterButton,
  actions,
  leading,
  middle,
  trailing,
  className,
  actionsClassName,
  "data-testid": dataTestId,
}) => {
  const hasLeading = Boolean(leading || middle);
  const hasActions = Boolean(
    refresh || filterButton || columnSettingsButton || actions || trailing,
  );

  return (
    <Flex
      className={clsx(
        styles.toolbar,
        variantClassNameByVariant[variant],
        className,
      )}
      align="center"
      gap={8}
      wrap="wrap"
      data-testid={dataTestId}
    >
      {hasLeading ? (
        <Flex align="center" gap={8} wrap="wrap" className={styles.leading}>
          {leading}
          {middle}
        </Flex>
      ) : null}
      {search ? (
        <CompactSearch
          value={search.value}
          onChange={search.onChange}
          onClear={search.onClear}
          onSearchConfirm={search.onSearchConfirm}
          placeholder={search.placeholder}
          allowClear={search.allowClear}
          className={clsx(
            styles.search,
            searchClassNameByVariant[variant],
            search.className,
          )}
          style={search.style}
        />
      ) : null}
      {hasActions ? (
        <Flex
          align="center"
          gap={8}
          wrap="wrap"
          className={clsx(
            styles.actions,
            variant === "default" && styles.defaultActions,
            variant === "admin" && styles.adminActions,
            actionsClassName,
          )}
        >
          {refresh ? <TableToolbarRefreshButton refresh={refresh} /> : null}
          {filterButton}
          {columnSettingsButton}
          {actions}
          {trailing}
        </Flex>
      ) : null}
    </Flex>
  );
};
