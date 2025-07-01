import { Select, Button, Flex } from "antd";
import type { FilterDropdownProps } from "antd/es/table/interface";
import React from "react";

export function makeEnumColumnFilterDropdown<
  K extends string,
  V extends string | number,
>(options: { label: React.ReactNode; value: V }[], field: K, multiple = false) {
  const filterDropdown = ({
    setSelectedKeys,
    selectedKeys,
    confirm,
    clearFilters,
  }: FilterDropdownProps) => (
    <Flex vertical gap={8} style={{ padding: 8, width: 350 }}>
      {multiple ? (
        <Select<V[]>
          mode="multiple"
          allowClear
          style={{ width: "100%" }}
          placeholder="Select value"
          options={options}
          value={selectedKeys as V[]}
          onChange={(vals) => setSelectedKeys(vals as React.Key[])}
        />
      ) : (
        <Select<V>
          allowClear
          style={{ width: "100%" }}
          placeholder="Select value"
          options={options}
          value={selectedKeys[0] as V | undefined}
          onChange={(val) => setSelectedKeys(val ? [val] : [])}
        />
      )}

      <Flex justify="end" gap={8}>
        <Button
          size="small"
          onClick={() => {
            clearFilters?.();
            confirm();
          }}
        >
          Reset
        </Button>
        <Button type="primary" size="small" onClick={() => confirm()}>
          Filter
        </Button>
      </Flex>
    </Flex>
  );

  const onFilter = (value: V | React.Key | number | boolean, record: Record<K, V>) =>
    record[field] === value;

  return { filterDropdown, onFilter };
}
