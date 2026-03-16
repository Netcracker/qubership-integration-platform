import React, { useContext, useEffect, useState } from "react";
import { Button, Form } from "antd";
import { useForm } from "antd/lib/form/Form";
import { OverridableIcon } from "../icons/IconProvider.tsx";
import { InlineEditContext, InlineEditProps } from "./InlineEdit.tsx";
import styles from "./InlineEdit.module.css";

/**
 * Standalone inline edit with Apply/Cancel buttons.
 * Does not modify InlineEdit; implements its own Form + buttons logic.
 */
function InlineEditActionButtons({ showApply }: { showApply: boolean }) {
  const form = Form.useFormInstance();
  const ctx = useContext(InlineEditContext);

  return (
    <div className={styles.inlineEditButtons}>
      {showApply && (
        <Button
          icon={<OverridableIcon name="check" />}
          type="text"
          size="small"
          onClick={() => form.submit()}
          aria-label="Apply"
        />
      )}
      <Button
        icon={<OverridableIcon name="close" />}
        type="text"
        size="small"
        onClick={() => ctx?.toggle()}
        aria-label="Cancel"
      />
    </div>
  );
}

export function InlineEditWithButtons<Values>(
  props: InlineEditProps<Values>,
): React.ReactNode {
  const [form] = useForm<Values>();
  const [processing, setProcessing] = useState(false);
  const [active, setActive] = useState(props.initialActive ?? false);
  const [hasChanges, setHasChanges] = useState(false);

  useEffect(() => {
    if (active) {
      // @ts-expect-error False positive as object of type Values is RecursivePartial<Values>
      form.setFieldsValue(props.values);
      setHasChanges(false);
    }
  }, [props.values, active, form]);

  const toggle = () => {
    setActive(!active);
    if (!active) {
      // @ts-expect-error False positive
      form.setFieldsValue(props.values);
    } else {
      props.onCancel?.();
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      toggle();
    }
  };

  if (!active) {
    return (
      <InlineEditContext.Provider value={{ toggle }}>
        <div
          role="button"
          tabIndex={0}
          className={styles.inlineEditValueWrap}
          style={{ paddingInlineEnd: 24 }}
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
      <div className={styles.inlineEditFormWrap}>
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
              toggle();
            } catch {
              setProcessing(false);
            }
          }}
        >
          {props.editor}
          <InlineEditActionButtons showApply={hasChanges} />
        </Form>
      </div>
    </InlineEditContext.Provider>
  );
}
