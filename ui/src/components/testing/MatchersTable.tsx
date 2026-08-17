import React, { useMemo, useState } from "react";
import { Button, Switch, Table, Tooltip, Typography } from "antd";
import type { TableProps } from "antd/lib/table";
import type { TableRowSelection } from "antd/lib/table/interface";
import {
  MatcherEntityType,
  MatcherType,
  TestingMatcher,
  TestingNamedParameter,
} from "../../api/apiTypes.ts";
import { InlineEdit } from "../InlineEdit.tsx";
import { OverridableIcon } from "../../icons/IconProvider.tsx";
import { formatOptional } from "../../misc/format-utils.ts";
import { SelectEdit } from "../table/SelectEdit.tsx";
import { TableToolbar } from "../table/TableToolbar.tsx";
import { TextValueEdit } from "../table/TextValueEdit.tsx";
import { inlineTableEmpty } from "../table/tableEmpty.tsx";
import { tableScroll } from "../table/tableScroll.ts";
import { MatcherParametersCell } from "./matcherEditors/MatcherParametersCell.tsx";
import {
  createMatcher,
  getEntityTypesForOwnerKind,
  MATCHER_ENTITY_TYPE_LABELS,
  MATCHER_TYPE_LABELS,
  matcherMatchesSearch,
  matcherRequiresEntityName,
  MatcherOwnerKind,
  withEntityType,
  withMatcherType,
} from "./matchers.ts";

const MATCHERS_SELECTION_COLUMN_WIDTH = 48;

/** Sum of the column widths below; keeps the grid scrollable instead of squeezed. */
const TABLE_SCROLL_X = 1280;

const MATCHER_TYPE_OPTIONS = Object.values(MatcherType).map((type) => ({
  value: type,
  label: MATCHER_TYPE_LABELS[type],
}));

export type MatchersTableProps = {
  /** Request matchers belong to an endpoint mock, response ones to a test case. */
  kind: MatcherOwnerKind;
  matchers: TestingMatcher[] | null;
  onChange: (matchers: TestingMatcher[]) => void;
  readonly?: boolean;
};

export const MatchersTable: React.FC<MatchersTableProps> = ({
  kind,
  matchers,
  onChange,
  readonly = false,
}) => {
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const [expandedDescriptions, setExpandedDescriptions] = useState<string[]>(
    [],
  );
  const [searchTerm, setSearchTerm] = useState("");

  const rows = useMemo(() => matchers ?? [], [matchers]);

  const visibleRows = useMemo(
    () => rows.filter((matcher) => matcherMatchesSearch(matcher, searchTerm)),
    [rows, searchTerm],
  );

  const entityTypeOptions = useMemo(
    () =>
      getEntityTypesForOwnerKind(kind).map((entityType) => ({
        value: entityType,
        label: MATCHER_ENTITY_TYPE_LABELS[entityType],
      })),
    [kind],
  );

  const rowKeyOf = (matcher: TestingMatcher) => matcher.id ?? matcher.name;

  const updateMatcher = (
    matcher: TestingMatcher,
    change: (current: TestingMatcher) => TestingMatcher,
  ) => {
    const key = rowKeyOf(matcher);
    onChange(rows.map((row) => (rowKeyOf(row) === key ? change(row) : row)));
  };

  const addMatcher = () => onChange([...rows, createMatcher(kind)]);

  const deleteSelected = () => {
    const selected = new Set(selectedRowKeys.map(String));
    onChange(rows.filter((row) => !selected.has(String(rowKeyOf(row)))));
    setSelectedRowKeys([]);
  };

  const setEnabledOnSelected = (enabled: boolean) => {
    const selected = new Set(selectedRowKeys.map(String));
    onChange(
      rows.map((row) =>
        selected.has(String(rowKeyOf(row))) ? { ...row, enabled } : row,
      ),
    );
  };

  const toggleDescription = (key: string) =>
    setExpandedDescriptions((keys) =>
      keys.includes(key) ? keys.filter((k) => k !== key) : [...keys, key],
    );

  const columns: TableProps<TestingMatcher>["columns"] = [
    {
      title: "Name",
      key: "name",
      width: 180,
      render: (_, matcher) =>
        readonly ? (
          <>{formatOptional(matcher.name)}</>
        ) : (
          <InlineEdit<{ name: string }>
            values={{ name: matcher.name }}
            editor={
              <TextValueEdit
                name="name"
                inputProps={{ "aria-label": "Matcher name" }}
                rules={[{ required: true, message: "Name is required." }]}
              />
            }
            viewer={
              matcher.name ? (
                <span>{matcher.name}</span>
              ) : (
                <Typography.Text type="danger">
                  Name is required
                </Typography.Text>
              )
            }
            onSubmit={({ name }) =>
              updateMatcher(matcher, (current) => ({ ...current, name }))
            }
          />
        ),
    },
    {
      title: "Description",
      key: "description",
      width: 220,
      render: (_, matcher) => {
        const key = String(rowKeyOf(matcher));
        const expanded = expandedDescriptions.includes(key);
        const text = (
          <span
            style={
              expanded
                ? undefined
                : {
                    display: "-webkit-box",
                    WebkitLineClamp: 1,
                    WebkitBoxOrient: "vertical",
                    overflow: "hidden",
                  }
            }
          >
            {formatOptional(matcher.description)}
          </span>
        );
        return (
          <div style={{ display: "flex", alignItems: "flex-start", gap: 4 }}>
            {matcher.description ? (
              <Button
                type="text"
                size="small"
                aria-label={
                  expanded ? "Collapse description" : "Expand description"
                }
                icon={<OverridableIcon name={expanded ? "up" : "down"} />}
                onClick={() => toggleDescription(key)}
              />
            ) : null}
            <div style={{ flex: 1, minWidth: 0 }}>
              {readonly ? (
                text
              ) : (
                <InlineEdit<{ description: string }>
                  values={{ description: matcher.description }}
                  editor={
                    <TextValueEdit
                      name="description"
                      rules={[]}
                      inputProps={{ "aria-label": "Matcher description" }}
                    />
                  }
                  viewer={text}
                  onSubmit={({ description }) =>
                    updateMatcher(matcher, (current) => ({
                      ...current,
                      description,
                    }))
                  }
                />
              )}
            </div>
          </div>
        );
      },
    },
    {
      title: "Condition",
      key: "type",
      width: 180,
      render: (_, matcher) =>
        readonly ? (
          <>{MATCHER_TYPE_LABELS[matcher.type]}</>
        ) : (
          <InlineEdit<{ type: MatcherType }>
            values={{ type: matcher.type }}
            editor={
              <SelectEdit<MatcherType>
                name="type"
                options={MATCHER_TYPE_OPTIONS}
                selectProps={{ "aria-label": "Condition" }}
                shouldSubmitOnChange={() => true}
              />
            }
            viewer={<span>{MATCHER_TYPE_LABELS[matcher.type]}</span>}
            onSubmit={({ type }) =>
              updateMatcher(matcher, (current) =>
                withMatcherType(current, type),
              )
            }
          />
        ),
    },
    {
      title: "Entity Type",
      key: "entityType",
      width: 200,
      render: (_, matcher) =>
        readonly ? (
          <>{MATCHER_ENTITY_TYPE_LABELS[matcher.entityType]}</>
        ) : (
          <InlineEdit<{ entityType: MatcherEntityType }>
            values={{ entityType: matcher.entityType }}
            editor={
              <SelectEdit<MatcherEntityType>
                name="entityType"
                options={entityTypeOptions}
                selectProps={{ "aria-label": "Entity type" }}
                shouldSubmitOnChange={() => true}
              />
            }
            viewer={
              <span>{MATCHER_ENTITY_TYPE_LABELS[matcher.entityType]}</span>
            }
            onSubmit={({ entityType }) =>
              updateMatcher(matcher, (current) =>
                withEntityType(current, entityType),
              )
            }
          />
        ),
    },
    {
      title: "Entity Name",
      key: "entityName",
      width: 160,
      render: (_, matcher) => {
        if (!matcherRequiresEntityName(matcher.entityType)) {
          return (
            <Typography.Text type="secondary">Not applicable</Typography.Text>
          );
        }
        if (readonly) {
          return <>{formatOptional(matcher.entityName)}</>;
        }
        return (
          <InlineEdit<{ entityName: string }>
            values={{ entityName: matcher.entityName ?? "" }}
            editor={
              <TextValueEdit
                name="entityName"
                inputProps={{ "aria-label": "Entity name" }}
                rules={[
                  { required: true, message: "Entity name is required." },
                ]}
              />
            }
            viewer={
              matcher.entityName ? (
                <span>{matcher.entityName}</span>
              ) : (
                <Typography.Text type="danger">
                  Entity name is required
                </Typography.Text>
              )
            }
            onSubmit={({ entityName }) =>
              updateMatcher(matcher, (current) => ({ ...current, entityName }))
            }
          />
        );
      },
    },
    {
      title: "Parameters",
      key: "parameters",
      width: 240,
      render: (_, matcher) => (
        <MatcherParametersCell
          matcher={matcher}
          readonly={readonly}
          onChange={(parameters: TestingNamedParameter[]) =>
            updateMatcher(matcher, (current) => ({ ...current, parameters }))
          }
        />
      ),
    },
    {
      title: "Enabled",
      key: "enabled",
      width: 100,
      render: (_, matcher) => (
        <Switch
          size="small"
          checked={matcher.enabled}
          disabled={readonly}
          aria-label={`Enable ${matcher.name || "matcher"}`}
          onChange={(enabled) =>
            updateMatcher(matcher, (current) => ({ ...current, enabled }))
          }
        />
      ),
    },
  ];

  const rowSelection: TableRowSelection<TestingMatcher> | undefined = readonly
    ? undefined
    : {
        type: "checkbox",
        selectedRowKeys,
        columnWidth: MATCHERS_SELECTION_COLUMN_WIDTH,
        onChange: setSelectedRowKeys,
      };

  const nothingSelected = selectedRowKeys.length === 0;

  return (
    <>
      <TableToolbar
        data-testid="matchers-toolbar"
        search={{
          value: searchTerm,
          // The bulk actions act on the selection, which the search would
          // otherwise carry over rows the table no longer shows.
          onChange: (term: string) => {
            setSearchTerm(term);
            setSelectedRowKeys([]);
          },
          placeholder: "Search matchers...",
          allowClear: true,
          style: { minWidth: 160, maxWidth: 320, flex: "0 1 auto" },
        }}
        actions={
          readonly ? null : (
            <>
              <Tooltip title="Enable selected matchers">
                <Button
                  aria-label="Enable selected matchers"
                  icon={<OverridableIcon name="check" />}
                  disabled={nothingSelected}
                  onClick={() => setEnabledOnSelected(true)}
                />
              </Tooltip>
              <Tooltip title="Disable selected matchers">
                <Button
                  aria-label="Disable selected matchers"
                  icon={<OverridableIcon name="stop" />}
                  disabled={nothingSelected}
                  onClick={() => setEnabledOnSelected(false)}
                />
              </Tooltip>
              <Tooltip title="Delete selected matchers">
                <Button
                  aria-label="Delete selected matchers"
                  icon={<OverridableIcon name="delete" />}
                  disabled={nothingSelected}
                  onClick={deleteSelected}
                />
              </Tooltip>
              <Tooltip title="Add matcher">
                <Button
                  type="primary"
                  aria-label="Add matcher"
                  icon={<OverridableIcon name="plus" />}
                  onClick={addMatcher}
                />
              </Tooltip>
            </>
          )
        }
      />
      <Table<TestingMatcher>
        size="small"
        columns={columns}
        rowSelection={rowSelection}
        dataSource={visibleRows}
        pagination={false}
        rowKey={rowKeyOf}
        className="flex-table"
        style={{ flex: 1, minHeight: 0 }}
        locale={{ emptyText: inlineTableEmpty("No matchers") }}
        scroll={tableScroll(TABLE_SCROLL_X, visibleRows.length)}
      />
    </>
  );
};
