import React, { type CSSProperties, type ReactNode } from "react";
import { Flex } from "antd";
import clsx from "clsx";
import { CompactSearch, type CompactSearchProps } from "./CompactSearch.tsx";
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

export type TableToolbarProps = {
  variant?: TableToolbarVariant;
  search?: TableToolbarSearch;
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

export const TableToolbar: React.FC<TableToolbarProps> = ({
  variant = "default",
  search,
  columnSettingsButton,
  actions,
  leading,
  middle,
  trailing,
  className,
  actionsClassName,
  "data-testid": dataTestId,
}) => {
  const hasLeading = Boolean(leading || middle);
  const hasActions = Boolean(columnSettingsButton || actions || trailing);

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
          {columnSettingsButton}
          {actions}
          {trailing}
        </Flex>
      ) : null}
    </Flex>
  );
};
