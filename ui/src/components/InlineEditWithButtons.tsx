import React, {
  useCallback,
  useContext,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
} from "react";
import { Button, Form } from "antd";
import { useForm } from "antd/lib/form/Form";
import { OverridableIcon } from "../icons/IconProvider.tsx";
import { InlineEditContext, InlineEditProps } from "./InlineEdit.tsx";
import styles from "./InlineEdit.module.css";

export type InlineEditWithButtonsRef<Values> = {
  getValuesAndClose: () => Values | null;
};

export type InlineEditWithButtonsProps<Values> = InlineEditProps<Values> & {
  cancelOnBlur?: boolean;
  submitOnBlur?: boolean;
  disabled?: boolean;
  escapeToCancel?: boolean;
  getApplyEnabled?: (values: Values) => boolean;
  hideApplyButton?: boolean;
  className?: string;
  editorClassName?: string;
  viewerAriaLabel?: string;
  innerRef?: React.Ref<InlineEditWithButtonsRef<Values>>;
};

function InlineEditActionButtons<Values>({
  showApply,
  hideApplyButton,
  getApplyEnabled,
}: {
  showApply: boolean;
  hideApplyButton?: boolean;
  getApplyEnabled?: (values: Values) => boolean;
}) {
  const form = Form.useFormInstance<Values>();
  const ctx = useContext(InlineEditContext);
  const values = Form.useWatch(undefined, form) as Values | undefined;

  const applyEnabled =
    showApply &&
    (!getApplyEnabled || getApplyEnabled?.(values ?? ({} as Values)));

  return (
    <div className={styles.inlineEditButtons}>
      {showApply && !hideApplyButton && (
        <Button
          icon={<OverridableIcon name="check" />}
          type="text"
          size="small"
          onClick={() => form.submit()}
          disabled={!applyEnabled}
          aria-label="Apply"
          data-testid="element-name-apply"
        />
      )}
      <Button
        icon={<OverridableIcon name="close" />}
        type="text"
        size="small"
        onClick={() => ctx?.toggle()}
        aria-label="Cancel"
        data-testid="element-name-cancel"
      />
    </div>
  );
}

export function InlineEditWithButtons<Values>(
  props: Readonly<InlineEditWithButtonsProps<Values>>,
): React.ReactNode {
  const {
    cancelOnBlur,
    submitOnBlur,
    disabled,
    escapeToCancel,
    getApplyEnabled,
    hideApplyButton,
    className,
    editorClassName,
    viewerAriaLabel,
    innerRef,
  } = props;

  const [form] = useForm<Values>();
  const [processing, setProcessing] = useState(false);
  const [active, setActive] = useState(props.initialActive ?? false);
  const [hasChanges, setHasChanges] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (active) {
      // @ts-expect-error False positive as object of type Values is RecursivePartial<Values>
      form.setFieldsValue(props.values);
      setHasChanges(false);
    }
  }, [props.values, active, form]);

  const toggle = useCallback(() => {
    setActive((prev) => {
      if (prev) {
        props.onCancel?.();
        return false;
      }
      // @ts-expect-error False positive
      form.setFieldsValue(props.values);
      return true;
    });
  }, [form, props.values, props.onCancel]);

  const getValuesAndClose = useCallback((): Values | null => {
    if (!active) return null;
    const values = form.getFieldsValue();
    toggle();
    return values;
  }, [active, form, toggle]);

  useImperativeHandle(innerRef, () => ({ getValuesAndClose }), [
    getValuesAndClose,
  ]);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      toggle();
    }
  };

  const handleBlur = useCallback(
    (e: React.FocusEvent<HTMLDivElement>) => {
      if (!e.currentTarget.contains(e.relatedTarget as Node | null)) {
        if (submitOnBlur) {
          form.submit();
        } else if (cancelOnBlur) {
          toggle();
        }
      }
    },
    [cancelOnBlur, submitOnBlur, toggle, form],
  );

  const handleKeyDownCapture = useCallback(
    (e: React.KeyboardEvent) => {
      if (escapeToCancel && e.key === "Escape") {
        e.preventDefault();
        e.stopPropagation();
        toggle();
      }
    },
    [escapeToCancel, toggle],
  );

  if (disabled) {
    return (
      <div className={className} role="group">
        {props.viewer}
      </div>
    );
  }

  if (!active) {
    return (
      <InlineEditContext.Provider value={{ toggle }}>
        <div
          role="button"
          tabIndex={0}
          aria-label={viewerAriaLabel}
          className={className ?? styles.inlineEditValueWrap}
          style={!className ? { paddingInlineEnd: 24 } : undefined}
          onClick={toggle}
          onKeyDown={handleKeyDown}
        >
          {props.viewer}
        </div>
      </InlineEditContext.Provider>
    );
  }

  return (
    <InlineEditContext.Provider value={{ toggle }}>
      <div
        ref={containerRef}
        className={editorClassName ?? styles.inlineEditFormWrap}
        onBlur={cancelOnBlur || submitOnBlur ? handleBlur : undefined}
        onKeyDownCapture={escapeToCancel ? handleKeyDownCapture : undefined}
      >
        <Form<Values>
          form={form}
          disabled={processing}
          component={false}
          onValuesChange={() => setHasChanges(true)}
          onFinish={() => {
            setProcessing(true);
            try {
              const fields = form.getFieldsValue();
              const result = props.onSubmit?.(fields);
              if (result instanceof Promise) {
                result
                  .then(() => {
                    setProcessing(false);
                    toggle();
                  })
                  .catch(() => setProcessing(false));
              } else {
                setProcessing(false);
                toggle();
              }
            } catch {
              setProcessing(false);
            }
          }}
        >
          {props.editor}
          <InlineEditActionButtons<Values>
            showApply={hasChanges}
            hideApplyButton={hideApplyButton}
            getApplyEnabled={getApplyEnabled}
          />
        </Form>
      </div>
    </InlineEditContext.Provider>
  );
}
