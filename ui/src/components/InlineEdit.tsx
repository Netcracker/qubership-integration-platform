import { createContext, ReactNode, useEffect, useState } from "react";
import { useForm } from "antd/lib/form/Form";
import { Form } from "antd";
import styles from "./InlineEdit.module.css";

export type InlineEditContextProps = {
  toggle: () => void;
};

export type InlineEditProps<Values = any> = {
  values: Values;
  editor: ReactNode;
  viewer: ReactNode;
  onSubmit: (values: Values) => void;
};

export const InlineEditContext = createContext<InlineEditContextProps | null>(
  null,
);

export function InlineEdit<Values = any>({
  values,
  editor,
  viewer,
  onSubmit,
}: InlineEditProps<Values>): ReactNode {
  const [form] = useForm();
  const [active, setActive] = useState<boolean>(false);

  useEffect(() => {
    form.setFieldsValue(values);
  }, [values]);

  const toggle = () => {
    setActive(!active);
    if (!active) {
      form.setFieldsValue(values);
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
