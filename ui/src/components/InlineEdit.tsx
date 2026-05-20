import {
  createContext,
  ReactNode,
  useCallback,
  useEffect,
  useMemo,
  useState,
} from "react";
import { useForm } from "antd/lib/form/Form";
import { Flex, Form } from "antd";
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
}: Readonly<InlineEditProps<Values>>): ReactNode {
  const [form] = useForm<Values>();
  const [processing, setProcessing] = useState<boolean>(false);
  const [active, setActive] = useState<boolean>(initialActive ?? false);

  useEffect(() => {
    if (active) {
      // @ts-expect-error False positive as object of type Values is RecursivePartial<Values>
      form.setFieldsValue(values);
    }
  }, [values, active, form]);

  const toggle = useCallback(() => {
    setActive((prev) => {
      if (prev) {
        onCancel?.();
        return false;
      }
      // @ts-expect-error False positive as object of type Values is RecursivePartial<Values>
      form.setFieldsValue(values);
      return true;
    });
  }, [form, values, onCancel]);

  const contextValue = useMemo(() => ({ toggle }), [toggle]);

  return (
    <InlineEditContext.Provider value={contextValue}>
      {active ? (
        <Flex style={{ width: "100%", minWidth: 0 }} vertical={false}>
          <div style={{ flex: 1, minWidth: 0 }}>
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
                } catch (e) {
                  console.error(e);
                  setProcessing(false);
                }
                toggle();
              }}
              onBlur={toggle}
            >
              {editor}
            </Form>
          </div>
        </Flex>
      ) : (
        <button
          type="button"
          className={styles.inlineEditValueWrap}
          style={{ paddingInlineEnd: 24 }}
          onClick={toggle}
        >
          {viewer}
        </button>
      )}
    </InlineEditContext.Provider>
  );
}
