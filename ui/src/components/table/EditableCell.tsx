import React from "react";
import { Button } from "antd";
import { OverridableIcon } from "../../icons/IconProvider.tsx";
import editableCellStyles from "./EditableCell.module.css";

export { editableCellStyles };

type EditableCellTriggerProps = {
  onClick: () => void;
  children: React.ReactNode;
  className?: string;
  style?: React.CSSProperties;
};

export const EditableCellTrigger: React.FC<EditableCellTriggerProps> = ({
  onClick,
  children,
  className,
  style,
}) => {
  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      onClick();
    }
  };

  return (
    <button
      type="button"
      tabIndex={0}
      className={`${editableCellStyles.editableCellWrapper} ${className ?? ""}`}
      style={{
        border: "none",
        background: "transparent",
        padding: 0,
        ...style,
      }}
      onClick={onClick}
      onKeyDown={handleKeyDown}
    >
      {children}
    </button>
  );
};

type InlineEditWithButtonsProps = {
  children: React.ReactNode;
  onApply: () => void;
  onCancel: () => void;
  onKeyDown?: (e: React.KeyboardEvent<HTMLElement>) => void;
  showButtons?: boolean;
};

export const InlineEditWithButtons: React.FC<InlineEditWithButtonsProps> = ({
  children,
  onApply,
  onCancel,
  onKeyDown,
  showButtons = true,
}) => {
  const childWithKeyDown =
    onKeyDown && React.isValidElement(children)
      ? React.cloneElement(
          children as React.ReactElement<{
            onKeyDown?: (e: React.KeyboardEvent) => void;
          }>,
          {
            onKeyDown: (e: React.KeyboardEvent<HTMLElement>) => {
              (
                children as React.ReactElement<{
                  onKeyDown?: (e: React.KeyboardEvent) => void;
                }>
              ).props.onKeyDown?.(e);
              onKeyDown(e);
            },
          },
        )
      : children;

  return (
    <fieldset
      className={editableCellStyles.editingWrapper}
      style={{ border: "none", padding: 0, margin: 0, minWidth: 0 }}
    >
      {childWithKeyDown}
      {showButtons && (
        <div className={editableCellStyles.editingButtons}>
          <Button
            icon={<OverridableIcon name="check" />}
            type="text"
            size="small"
            onClick={onApply}
            aria-label="Apply"
          />
          <Button
            icon={<OverridableIcon name="close" />}
            type="text"
            size="small"
            onClick={onCancel}
            aria-label="Cancel"
          />
        </div>
      )}
    </fieldset>
  );
};
