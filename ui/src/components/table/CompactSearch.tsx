import React from "react";
import { Button, Input, Space } from "antd";
import { SearchOutlined } from "@ant-design/icons";

export type CompactSearchProps = {
  value: string;
  onChange: (value: string) => void;
  onClear?: () => void;
  /** Called on Enter or Search button click. Use for immediate submit (e.g. bypass debounce). */
  onSearchConfirm?: (value: string) => void;
  placeholder?: string;
  allowClear?: boolean;
  style?: React.CSSProperties;
  className?: string;
};

export const CompactSearch: React.FC<CompactSearchProps> = ({
  value,
  onChange,
  onClear,
  onSearchConfirm,
  placeholder,
  allowClear = true,
  style,
  className,
}) => {
  const handleConfirm = () =>
    onSearchConfirm ? onSearchConfirm(value) : onChange(value);

  return (
    <Space.Compact style={style} className={className}>
      <Input
        placeholder={placeholder}
        allowClear={allowClear}
        value={value}
        onChange={(e) => {
          const v = e.target.value;
          onChange(v);
          if (!v) onClear?.();
        }}
        onPressEnter={handleConfirm}
      />
      <Button
        type="primary"
        icon={<SearchOutlined />}
        aria-label="Search"
        onClick={handleConfirm}
      />
    </Space.Compact>
  );
};
