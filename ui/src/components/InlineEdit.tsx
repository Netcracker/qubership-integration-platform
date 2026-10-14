import {
  createContext,
  FocusEvent,
  ReactNode,
  useCallback,
  useEffect,
  useMemo,
  useState,
} from "react";
import { Form } from "antd";
import styles from "./InlineEdit.module.css";

const { useForm } = Form;

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
      form.setFieldsValue(values);
    }
  }, [values, active, form]);

  const toggle = useCallback(() => {
    setActive((prev) => {
      if (prev) {
        onCancel?.();
        return false;
      }
      form.setFieldsValue(values);
      return true;
    });
  }, [form, values, onCancel]);

  const handleBlur = useCallback(
    (e: FocusEvent<HTMLDivElement>) => {
      if (!e.currentTarget.contains(e.relatedTarget)) {
        form.submit();
      }
    },
    [form],
  );

  const contextValue = useMemo(() => ({ toggle }), [toggle]);

  return (
    <InlineEditContext.Provider value={contextValue}>
      {active ? (
        <div className={styles.inlineEditEditorWrap}>
          <div onBlur={handleBlur}>
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
              }}
            >
              {editor}
            </Form>
          </div>
        </div>
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
