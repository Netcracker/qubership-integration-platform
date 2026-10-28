import { Button, Flex, Form, Input, message } from "antd";
import React, { useCallback, useEffect, useRef, useState } from "react";
import {
  KeyValuePair,
  makeArray,
  parse,
  removeDuplicateKeys,
  sliceParameter,
} from "../../../../misc/key-value-text-util.ts";
import { OverridableIcon } from "../../../../icons/IconProvider.tsx";
import {
  KeyValuePairsEditor,
  KeyValuePairsEditorRef,
} from "../../../table/kv/KeyValuePairsEditor.tsx";

export type DictionaryAdapterProps = {
  value?: string[];
  onChange?: (value: string[]) => void;
};

const DictionaryEditor: React.FC<DictionaryAdapterProps> = ({
  onChange,
  value,
}) => {
  const [messageApi, contextHolder] = message.useMessage();
  const [keyValuePairs, setKeyValuePairs] = useState<KeyValuePair[]>([]);

  useEffect(() => {
    try {
      const parameters = sliceParameter(value ?? []);
      setKeyValuePairs(removeDuplicateKeys(parse(parameters ?? "")));
    } catch (error) {
      void messageApi.error(`Invalid dictionary: ${String(error)}`);
      setKeyValuePairs([]);
    }
  }, [messageApi, value]);

  const handleChange = useCallback(
    (updatedParams: KeyValuePair[]) => {
      const safeValue = value ?? [];
      const base = safeValue[0] ?? "";
      const merged = makeArray(updatedParams);
      onChange?.(merged.length ? [base, ...merged] : [base]);
    },
    [onChange, value],
  );

  const editorRef = useRef<KeyValuePairsEditorRef>(null);

  return (
    <>
      {contextHolder}
      <KeyValuePairsEditor
        value={keyValuePairs}
        onChange={handleChange}
        tableHeader={
          <Flex wrap="wrap" vertical={false} gap={8}>
            <Button
              size="small"
              icon={<OverridableIcon name="plusCircle" />}
              onClick={() => editorRef.current?.add()}
            >
              Add rule
            </Button>
            <Button
              size="small"
              icon={<OverridableIcon name="delete" />}
              onClick={() => editorRef.current?.clear()}
            >
              Clear rules
            </Button>
          </Flex>
        }
        actionRef={editorRef}
        tableColumnNames={{ key: "Input", value: "Result" }}
      />
    </>
  );
};

export const DictionaryParameters: React.FC = () => {
  return (
    <>
      <Form.Item name={["parameters", 0]} label="Default" initialValue={""}>
        <Input />
      </Form.Item>
      <Form.Item
        className={"flex-form-item flex-form-item-h100"}
        name={["parameters"]}
      >
        <DictionaryEditor />
      </Form.Item>
    </>
  );
};
