import { createContext, ReactNode, useEffect, useState } from "react";
import { useForm } from "antd/lib/form/Form";
import { Form } from "antd";
import styles from "./InlineEdit.module.css";

export type InlineEditContextProps = {
  toggle: () => void;
};

export type InlineEditProps<Values> = {
  values: Values;
  editor: ReactNode;
  viewer: ReactNode;
  onSubmit?: (values: Values) => void | Promise<void>;
  onCancel?: () => void;
  initialActive?: boolean;
};

export const InlineEditContext = createContext<InlineEditContextProps | null>(
  null,
);

export function InlineEdit<Values>({
  values,
  editor,
  viewer,
  onSubmit,
  onCancel,
  initialActive,
}: InlineEditProps<Values>): ReactNode {
  const [form] = useForm<Values>();
  const [processing, setProcessing] = useState<boolean>(false);
  const [active, setActive] = useState<boolean>(initialActive ?? false);

  useEffect(() => {
    if (active) {
      // @ts-expect-error False positive as object of type Values is RecursivePartial<Values>
      form.setFieldsValue(values);
    }
  }, [values, active, form]);

  const toggle = () => {
    setActive(!active);
    if (!active) {
      // @ts-expect-error False positive as object of type Values is RecursivePartial<Values>
      form.setFieldsValue(values);
    } else {
      onCancel?.();
    }
  };

  return (
    <InlineEditContext.Provider value={{ toggle }}>
      {active ? (
        <Form<Values>
          form={form}
          disabled={processing}
          component={false}
          onFinish={() => {
            setProcessing(true);
            try {
              const result = onSubmit?.(form.getFieldsValue());
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
              // eslint-disable-next-line @typescript-eslint/no-unused-vars
            } catch (error) {
              setProcessing(false);
            }
            toggle();
          }}
          onBlur={toggle}
        >
          {editor}
        </Form>
      ) : (
        <div
          className={styles.inlineEditValueWrap}
          style={{ paddingInlineEnd: 24 }}
          onClick={toggle}
        >
          {viewer}
        </div>
      )}
    </InlineEditContext.Provider>
  );
}
