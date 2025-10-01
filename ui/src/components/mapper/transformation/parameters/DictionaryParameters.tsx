import {
  Button,
  Flex,
  Form,
  Input,
  message,
  Table,
  TableProps,
  Tabs,
} from "antd";
import React, { useCallback, useEffect, useRef, useState } from "react";
import { InlineEdit } from "../../../InlineEdit";
import { KeyValuePair, makeString, parse } from "./key-value-text-util";
import { TextValueEdit } from "../../../table/TextValueEdit";
import { Editor, Monaco } from "@monaco-editor/react";
import { editor, languages, MarkerSeverity } from "monaco-editor";
import { isParseError } from "../../../../mapper/actions-text/parser.ts";
import { LocationRange } from "pegjs";
import { Icon } from "../../../../IconProvider.tsx";

const MAPPER_DICTIONARY_LANGUAGE_ID = "qip-mapper-dictionary";

const MAPPER_DICTIONARY_LANGUAGE_CONFIGURATION: languages.LanguageConfiguration =
  {};

const MAPPER_DICTIONARY_LANGUAGE_TOKENIZER: languages.IMonarchLanguage = {
  tokenizer: {
    start: [
      { regex: /[ \t\r\n]+/, action: { token: "white" } },
      { regex: /([^=;\\]|\\[=;\\])+/, action: { token: "string" } },
      { regex: /[=;]/, action: { token: "delimiter" } },
    ],
  },
  defaultToken: "invalid",
  start: "start",
  includeLF: true,
};

function configureMapperDictionaryLanguage(monaco: Monaco) {
  const alreadyRegistered = monaco.languages
    .getLanguages()
    .some((language) => language.id === MAPPER_DICTIONARY_LANGUAGE_ID);
  if (alreadyRegistered) {
    console.log(
      `Language already registered: ${MAPPER_DICTIONARY_LANGUAGE_ID}`,
    );
    return;
  }
  monaco.languages.register({
    id: MAPPER_DICTIONARY_LANGUAGE_ID,
  });
  monaco.languages.setLanguageConfiguration(
    MAPPER_DICTIONARY_LANGUAGE_ID,
    MAPPER_DICTIONARY_LANGUAGE_CONFIGURATION,
  );
  monaco.languages.setMonarchTokensProvider(
    MAPPER_DICTIONARY_LANGUAGE_ID,
    MAPPER_DICTIONARY_LANGUAGE_TOKENIZER,
  );
}

export type DictionaryEditorProps = {
  value?: string;
  onChange?: (value: string) => void;
};

function removeDuplicateKeys(data: KeyValuePair[]): KeyValuePair[] {
  const seenKeys = new Set<string>();
  return data.filter((item) => {
    if (seenKeys.has(item.key)) {
      return false;
    } else {
      seenKeys.add(item.key);
      return true;
    }
  });
}

const DictionaryTableEditor: React.FC<DictionaryEditorProps> = ({
  onChange,
  value,
}) => {
  const [messageApi, contextHolder] = message.useMessage();
  const [tableData, setTableData] = useState<
    TableProps<KeyValuePair>["dataSource"]
  >([]);

  useEffect(() => {
    try {
      setTableData(removeDuplicateKeys(parse(value ?? "")));
    } catch (error) {
      void messageApi.error(`Invalid dictionary: ${String(error)}`);
      setTableData([]);
    }
  }, [messageApi, value]);

  const updateRecord = useCallback(
    (index: number, changes: Partial<KeyValuePair>) => {
      setTableData((data) => {
        const result =
          data?.map((r, idx) => (idx === index ? { ...r, ...changes } : r)) ??
          [];
        onChange?.(makeString(result));
        return result;
      });
    },
    [onChange],
  );

  const addRecord = useCallback(() => {
    setTableData((data) => {
      if (data?.some((r) => r.key === "")) {
        return data;
      }
      const result = [...(data ?? []), { key: "", value: "" }];
      onChange?.(makeString(result));
      return result;
    });
  }, [onChange]);

  const deleteRecord = useCallback(
    (index: number) => {
      setTableData((data) => {
        const result =
          data?.slice(0, index)?.concat(data?.slice(index + 1)) ?? [];
        onChange?.(makeString(result));
        return result;
      });
    },
    [onChange],
  );

  const clearRecords = useCallback(() => {
    setTableData([]);
    onChange?.(makeString([]));
  }, [onChange]);

  return (
    <>
      {contextHolder}
      <Flex style={{ height: "100%" }} vertical gap={8}>
        <Flex wrap="wrap" vertical={false} gap={8}>
          <Button
            size="small"
            icon={<Icon name="plusCircle" />}
            onClick={() => addRecord()}
          >
            Add rule
          </Button>
          <Button
            size="small"
            icon={<Icon name="delete" />}
            onClick={() => clearRecords()}
          >
            Clear rules
          </Button>
        </Flex>
        <Table
          className="flex-table"
          size="small"
          scroll={{ y: "" }}
          columns={[
            {
              key: "key",
              title: "Input",
              dataIndex: "key",
              sorter: (a, b, sortOrder) => {
                if (sortOrder === "ascend") {
                  return a.key.localeCompare(b.key);
                } else if (sortOrder === "descend") {
                  return b.key.localeCompare(a.key);
                }
                return 0;
              },
              render: (value: string, _record: KeyValuePair, index: number) => {
                return (
                  <InlineEdit<{ value: string }>
                    values={{ value }}
                    editor={<TextValueEdit name="value" />}
                    viewer={value}
                    initialActive={value === ""}
                    onSubmit={({ value }) => {
                      if (tableData?.some((r) => r.key === value)) {
                        messageApi.error(`Already exists: ${value}`);
                      } else {
                        updateRecord(index, { key: value });
                      }
                    }}
                  />
                );
              },
            },
            {
              key: "value",
              title: "Result",
              dataIndex: "value",
              sorter: (a, b, sortOrder) => {
                if (sortOrder === "ascend") {
                  return a.value.localeCompare(b.value);
                } else if (sortOrder === "descend") {
                  return b.value.localeCompare(a.value);
                }
                return 0;
              },
              render: (value: string, _record: KeyValuePair, index: number) => {
                return (
                  <InlineEdit<{ value: string }>
                    values={{ value }}
                    editor={<TextValueEdit name="value" rules={[]} />}
                    viewer={value}
                    onSubmit={({ value }) => {
                      updateRecord(index, { value });
                    }}
                  />
                );
              },
            },
            {
              key: "actions",
              className: "actions-column",
              width: 40,
              align: "right",
              render: (_, _record, index: number) => {
                return (
                  <Button
                    type="text"
                    icon={<Icon name="delete" />}
                    onClick={() => deleteRecord(index)}
                  />
                );
              },
            },
          ]}
          dataSource={tableData}
          pagination={false}
          rowKey={(record) => record.key}
        />
      </Flex>
    </>
  );
};

const DictionaryTextEditor: React.FC<DictionaryEditorProps> = ({
  onChange,
  value,
}) => {
  const [text, setText] = useState<string>("");

  useEffect(() => {
    setText(value ?? "");
  }, [value]);

  const editorRef = useRef<editor.IStandaloneCodeEditor>();
  const monacoRef = useRef<Monaco>();

  const setMarkers = useCallback((markers: editor.IMarkerData[]) => {
    const model = editorRef.current?.getModel();
    if (!model) {
      return;
    }
    monacoRef.current?.editor?.setModelMarkers(
      model,
      MAPPER_DICTIONARY_LANGUAGE_ID,
      markers,
    );
  }, []);

  const setMarker = useCallback(
    (locationRange: LocationRange, message: string) => {
      const markers: editor.IMarkerData[] = [
        {
          severity: MarkerSeverity.Error,
          message,
          startLineNumber: locationRange.start.line,
          startColumn: locationRange.start.column,
          endLineNumber: locationRange.end.line,
          endColumn: locationRange.end.column,
        },
      ];
      setMarkers(markers);
    },
    [setMarkers],
  );

  const clearMarkers = useCallback(() => {
    setMarkers([]);
  }, [setMarkers]);

  return (
    <Editor
      className="qip-editor"
      value={text}
      language={MAPPER_DICTIONARY_LANGUAGE_ID}
      onMount={(editor, monaco) => {
        editorRef.current = editor;
        monacoRef.current = monaco;
        configureMapperDictionaryLanguage(monaco);
      }}
      onChange={(value) => {
        const v = value ?? "";
        setText(v);
        try {
          parse(v);
          onChange?.(v);
          clearMarkers();
        } catch (error) {
          if (isParseError(error)) {
            setMarker(error.location, error.message);
          }
        }
      }}
      options={{
        fixedOverflowWidgets: true,
      }}
    />
  );
};

const DictionaryEditor: React.FC<DictionaryEditorProps> = ({
  onChange,
  value,
}) => {
  return (
    <Tabs
      style={{ height: "100%", width: "100%" }}
      className={"flex-tabs"}
      tabPosition="bottom"
      size="small"
      defaultActiveKey="table"
      items={[
        {
          key: "table",
          label: "Table",
          children: <DictionaryTableEditor value={value} onChange={onChange} />,
        },
        {
          key: "text",
          label: "Text",
          children: <DictionaryTextEditor value={value} onChange={onChange} />,
        },
      ]}
    />
  );
};

export const DictionaryParameters: React.FC = () => {
  return (
    <>
      <Form.Item name={["parameters", 0]} label="Default">
        <Input />
      </Form.Item>
      <Form.Item className={"flex-form-item"} name={["parameters", 1]}>
        <DictionaryEditor />
      </Form.Item>
    </>
  );
};
