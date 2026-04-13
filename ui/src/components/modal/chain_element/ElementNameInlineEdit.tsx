import React, { forwardRef, useImperativeHandle, useRef } from "react";
import { OverridableIcon } from "../../../icons/IconProvider.tsx";
import type { InlineEditWithButtonsRef } from "../../InlineEditWithButtons.tsx";
import { InlineEditWithButtons } from "../../InlineEditWithButtons.tsx";
import { TextValueEdit } from "../../table/TextValueEdit.tsx";
import styles from "./ElementNameInlineEdit.module.css";

export type ElementNameInlineEditRef = {
  syncIfEditing: () => string | null;
};

export type ElementNameInlineEditProps = {
  value: string;
  typeLabel?: string;
  onSave: (newValue: string) => void | Promise<void>;
  disabled?: boolean;
};

export const ElementNameInlineEdit = forwardRef<
  ElementNameInlineEditRef,
  Readonly<ElementNameInlineEditProps>
>(function ElementNameInlineEdit(
  { value, typeLabel, onSave, disabled = false },
  ref,
): React.ReactElement {
  const innerRef = useRef<InlineEditWithButtonsRef<{ name: string }>>(null);

  useImperativeHandle(
    ref,
    () => ({
      syncIfEditing: (): string | null => {
        const values = innerRef.current?.getValuesAndClose();
        return values?.name ?? null;
      },
    }),
    [],
  );

  return (
    <InlineEditWithButtons<{ name: string }>
      innerRef={innerRef}
      values={{ name: value }}
      disabled={disabled}
      submitOnBlur
      escapeToCancel
      hideApplyButton
      viewerAriaLabel="Edit name"
      className={styles.wrapper}
      editorClassName={styles.editor}
      viewer={
        <span className={styles.viewer}>
          <span className={styles.name} title={value}>
            {value}
          </span>
          {typeLabel && (
            <span className={styles["type-badge"]}>{typeLabel}</span>
          )}
          {!disabled && (
            <OverridableIcon name="edit" className={styles["edit-icon"]} />
          )}
        </span>
      }
      editor={
        <TextValueEdit
          name="name"
          rules={[]}
          inputProps={{
            size: "small",
            className: styles.input,
            "data-testid": "element-name-input",
          }}
        />
      }
      onSubmit={async ({ name }) => {
        const trimmed = name?.trim() ?? "";
        if (trimmed.length > 0) {
          await onSave(trimmed);
        }
      }}
    />
  );
});
