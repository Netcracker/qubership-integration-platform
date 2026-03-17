import React, { forwardRef, useImperativeHandle, useRef } from "react";
import { OverridableIcon } from "../../../icons/IconProvider.tsx";
import type { InlineEditWithButtonsRef } from "../../InlineEditWithButtons.tsx";
import { InlineEditWithButtons } from "../../InlineEditWithButtons.tsx";
import { TextValueEdit } from "../../table/TextValueEdit.tsx";
import styles from "./ChainElementModification.module.css";

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
      cancelOnBlur
      escapeToCancel
      viewerAriaLabel="Edit name"
      className={styles["element-name-edit-wrapper"] as string}
      editorClassName={styles["element-name-inline-editor"] as string}
      getApplyEnabled={(v: { name?: string }) =>
        (v.name?.trim().length ?? 0) > 0
      }
      viewer={
        <span className={styles["element-name-inline-viewer"] as string}>
          <span className={styles["modal-title-name"]} title={value}>
            {value}
          </span>
          {typeLabel && (
            <span className={styles["modal-title-type"]}>
              {"\u00A0"}
              {typeLabel}
            </span>
          )}
          {!disabled && (
            <OverridableIcon
              name="edit"
              className={styles["element-name-edit-icon"] as string}
            />
          )}
        </span>
      }
      editor={
        <TextValueEdit
          name="name"
          rules={[{ required: true, message: "Name is required" }]}
          inputProps={{
            size: "small",
            className: styles["element-name-input"] as string,
            "data-testid": "element-name-input",
          }}
        />
      }
      onSubmit={async ({ name }) => {
        const trimmed = name?.trim() ?? "";
        if (trimmed.length > 0) {
          const result = onSave(trimmed);
          if (result instanceof Promise) {
            await result;
          }
        }
      }}
      onCancel={() => {}}
    />
  );
});
