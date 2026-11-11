import React from "react";
import { Input, Space, Tooltip } from "antd";
import { OverridableIcon } from "../../icons/IconProvider.tsx";
import styles from "./CompactSearch.module.css";

export type CompactSearchProps = {
  value: string;
  onChange: (value: string) => void;
  onClear?: () => void;
  /** Called on Enter or Search button click. Use for immediate submit (e.g. bypass debounce). */
  onSearchConfirm?: (value: string) => void;
  placeholder?: string;
  style?: React.CSSProperties;
  className?: string;
};

export const CompactSearch: React.FC<CompactSearchProps> = ({
  value,
  onChange,
  onClear,
  onSearchConfirm,
  placeholder,
  style,
  className,
}) => {
  const showClearAction = value.length > 0;
  const actionLabel = showClearAction ? "Clear search" : "Search";
  const handleConfirm = () =>
    onSearchConfirm ? onSearchConfirm(value) : onChange(value);
  const handleClear = () => {
    onChange("");
    onClear?.();
  };
  const handleChange = (nextValue: string) => {
    if (nextValue) {
      onChange(nextValue);
    } else {
      handleClear();
    }
  };

  return (
    <Space.Compact style={style} className={className}>
      <Input
        placeholder={placeholder}
        value={value}
        onChange={(e) => handleChange(e.target.value)}
        onPressEnter={handleConfirm}
        suffix={
          <Tooltip title={actionLabel} placement="bottom">
            <button
              type="button"
              className={styles.action}
              aria-label={actionLabel}
              onClick={showClearAction ? handleClear : handleConfirm}
              onMouseDown={(event) => event.preventDefault()}
            >
              <OverridableIcon name={showClearAction ? "close" : "search"} />
            </button>
          </Tooltip>
        }
      />
    </Space.Compact>
  );
};
