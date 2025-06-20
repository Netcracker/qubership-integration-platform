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
  onSubmit?: (values: Values) => void;
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
  const [form] = useForm();
  const [active, setActive] = useState<boolean>(initialActive ?? false);

  useEffect(() => {
    if (active) {
      form.setFieldsValue(values);
    }
  }, [values, active]);

  const toggle = () => {
    setActive(!active);
    if (!active) {
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
          component={false}
          onFinish={async () => {
            onSubmit?.(form.getFieldsValue());
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
