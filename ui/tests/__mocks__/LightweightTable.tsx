/**
 * Lightweight Ant Design Table mock for fast JSDOM tests.
 *
 * Renders a plain <table> with column headers, row data (via column.render),
 * row selection checkboxes, and expandable children — just enough DOM for
 * typical test queries (getByText, getByRole, querySelector) while avoiding
 * the heavy rendering cost of the real antd Table.
 *
 * Supports expandable.expandedRowRender (flat tables) in addition to tree
 * childrenColumnName rows.
 */
import React from "react";

interface LightColumn {
  key?: string;
  dataIndex?: string;
  title?: React.ReactNode | ((props: Record<string, unknown>) => React.ReactNode);
  render?: (value: unknown, record: unknown, index: number) => React.ReactNode;
  onHeaderCell?: (col: unknown) => Record<string, unknown>;
}

interface RowSelection {
  type?: string;
  selectedRowKeys?: React.Key[];
  onChange?: (keys: React.Key[], rows: unknown[]) => void;
  getCheckboxProps?: (record: unknown) => Record<string, unknown>;
  checkStrictly?: boolean;
}

interface ExpandableConfig {
  expandedRowKeys?: React.Key[];
  defaultExpandAllRows?: boolean;
  defaultExpandedRowKeys?: React.Key[];
  onExpand?: (expanded: boolean, record: unknown) => void;
  onExpandedRowsChange?: (keys: readonly React.Key[]) => void;
  expandIcon?: (props: {
    expanded: boolean;
    onExpand: (record: unknown, e: React.MouseEvent) => void;
    record: unknown;
    expandable: boolean;
  }) => React.ReactNode;
  childrenColumnName?: string;
  expandedRowRender?: (
    record: unknown,
    index: number,
    indent: number,
    expanded: boolean,
  ) => React.ReactNode;
  rowExpandable?: (record: unknown) => boolean;
}

interface LightTableProps {
  dataSource?: unknown[];
  columns?: LightColumn[];
  rowKey?: string | ((record: unknown) => string);
  rowSelection?: RowSelection;
  expandable?: ExpandableConfig;
  rowClassName?: string | ((record: unknown, index: number) => string);
  className?: string;
  loading?: boolean;
  size?: string;
  pagination?: false | object;
  scroll?: object;
  sticky?: boolean;
  components?: unknown;
  onChange?: (...args: unknown[]) => void;
  onRow?: (
    record: unknown,
    index: number,
  ) => React.HTMLAttributes<HTMLTableRowElement>;
}

function getRowKey(
  record: Record<string, unknown>,
  index: number,
  rowKeyProp?: string | ((record: unknown) => string),
): React.Key {
  if (typeof rowKeyProp === "function") return rowKeyProp(record);
  if (typeof rowKeyProp === "string") return (record[rowKeyProp] as React.Key) ?? index;
  return (record["key"] as React.Key) ?? index;
}

function RenderRows({
  rows,
  columns,
  rowSelection,
  expandable,
  rowKeyProp,
  rowClassName,
  onRow,
}: {
  rows: unknown[];
  columns: LightColumn[];
  rowSelection?: RowSelection;
  expandable?: ExpandableConfig;
  rowKeyProp?: string | ((record: unknown) => string);
  rowClassName?: string | ((record: unknown, index: number) => string);
  onRow?: (record: unknown, index: number) => React.HTMLAttributes<HTMLTableRowElement>;
}) {
  const bodyColSpan = columns.length + (rowSelection ? 1 : 0);
  const childrenCol = expandable?.childrenColumnName ?? "children";
  // When defaultExpandAllRows is set OR no expandable config is provided
  // (tree data mode), always render children without tracking expanded state.
  const alwaysExpand = expandable?.defaultExpandAllRows || !expandable;

  const [localExpanded, setLocalExpanded] = React.useState<Set<React.Key>>(
    () => {
      if (expandable?.defaultExpandedRowKeys) {
        return new Set(expandable.defaultExpandedRowKeys);
      }
      return new Set<React.Key>();
    },
  );

  const expandedKeys = expandable?.expandedRowKeys
    ? new Set(expandable.expandedRowKeys)
    : localExpanded;

  return (
    <>
      {rows.map((record, idx) => {
        const rec = record as Record<string, unknown>;
        const key = getRowKey(rec, idx, rowKeyProp);
        const children = rec[childrenCol] as unknown[] | undefined;
        const hasChildren = Array.isArray(children) && children.length > 0;
        // Ant Design: empty children array still means "row can expand" (lazy / onExpand).
        const lazyExpandable = Array.isArray(children);
        const isExpanded = expandedKeys.has(key);
        const rowExpandOk =
          !expandable?.rowExpandable || expandable.rowExpandable(record);

        const cls =
          typeof rowClassName === "function"
            ? rowClassName(record, idx)
            : rowClassName ?? "";

        const toggleExpand = (e: React.MouseEvent) => {
          e.stopPropagation();
          const newExpanded = !isExpanded;
          if (!expandable?.expandedRowKeys) {
            setLocalExpanded((prev) => {
              const next = new Set(prev);
              if (newExpanded) next.add(key);
              else next.delete(key);
              return next;
            });
          }
          expandable?.onExpand?.(newExpanded, record);
          if (expandable?.onExpandedRowsChange) {
            const nextKeys = new Set(expandedKeys);
            if (newExpanded) nextKeys.add(key);
            else nextKeys.delete(key);
            expandable.onExpandedRowsChange([...nextKeys]);
          }
        };

        return (
          <React.Fragment key={key}>
            <tr className={cls} data-row-key={key} {...(onRow?.(record, idx) ?? {})}>
              {rowSelection && (
                <td>
                  <input
                    type="checkbox"
                    checked={rowSelection.selectedRowKeys?.includes(key) ?? false}
                    onChange={() => {
                      const current = rowSelection.selectedRowKeys ?? [];
                      const next = current.includes(key)
                        ? current.filter((k) => k !== key)
                        : [...current, key];
                      rowSelection.onChange?.(next, []);
                    }}
                    {...(rowSelection.getCheckboxProps?.(record) ?? {})}
                  />
                </td>
              )}
              {columns.map((col, colIdx) => {
                const val = col.dataIndex ? rec[col.dataIndex] : undefined;
                return (
                  <td key={col.key ?? col.dataIndex ?? colIdx}>
                    {expandable && colIdx === 0 && (
                      <>
                        {expandable.expandIcon
                          ? expandable.expandIcon({
                              expanded: isExpanded,
                              onExpand: (_record, e) => toggleExpand(e),
                              record,
                              expandable:
                                lazyExpandable ||
                                (!!expandable.expandedRowRender &&
                                  rowExpandOk),
                            })
                          : (lazyExpandable ||
                              (!!expandable.expandedRowRender &&
                                rowExpandOk)) && (
                              <span
                                className="ant-table-row-expand-icon"
                                onClick={toggleExpand}
                              />
                            )}
                      </>
                    )}
                    {col.render ? col.render(val, record, idx) : (val as React.ReactNode)}
                  </td>
                );
              })}
            </tr>
            {expandable?.expandedRowRender &&
              isExpanded &&
              rowExpandOk && (
                <tr
                  className="ant-table-expanded-row"
                  data-testid="light-table-expanded-row"
                >
                  <td colSpan={bodyColSpan}>
                    {expandable.expandedRowRender(record, idx, 0, isExpanded)}
                  </td>
                </tr>
              )}
            {(alwaysExpand || isExpanded) && hasChildren && (
              <RenderRows
                rows={children}
                columns={columns}
                rowSelection={rowSelection}
                expandable={expandable}
                rowKeyProp={rowKeyProp}
                rowClassName={rowClassName}
                onRow={onRow}
              />
            )}
          </React.Fragment>
        );
      })}
    </>
  );
}

export function LightweightTable({
  dataSource,
  columns,
  rowKey: rowKeyProp,
  rowSelection,
  expandable,
  rowClassName,
  className,
  loading,
  components,
  onRow,
}: LightTableProps) {
  const HeaderCell =
    (components as { header?: { cell?: React.ElementType } })?.header?.cell ?? "th";
  const cols = columns ?? [];
  const rows = dataSource ?? [];

  return (
    <div className={className}>
      {loading && <div className="ant-spin" data-testid="table-loading" />}
      <table>
        <thead>
          <tr>
            {rowSelection && (
              <HeaderCell>
                <input
                  type="checkbox"
                  onChange={() => {
                    const allKeys = rows.map((r, i) =>
                      getRowKey(r as Record<string, unknown>, i, rowKeyProp),
                    );
                    const allSelected =
                      rowSelection.selectedRowKeys?.length === allKeys.length;
                    rowSelection.onChange?.(allSelected ? [] : allKeys, []);
                  }}
                />
              </HeaderCell>
            )}
            {cols.map((col, idx) => {
              const cellProps = col.onHeaderCell?.(col) ?? {};
              return (
                <HeaderCell key={col.key ?? col.dataIndex ?? idx} {...cellProps}>
                  {typeof col.title === "function" ? col.title({}) : col.title}
                </HeaderCell>
              );
            })}
          </tr>
        </thead>
        <tbody>
          <RenderRows
            rows={rows}
            columns={cols}
            rowSelection={rowSelection}
            expandable={expandable}
            rowKeyProp={rowKeyProp}
            rowClassName={rowClassName}
            onRow={onRow}
          />
        </tbody>
      </table>
    </div>
  );
}
