import React, { useCallback, useContext, useEffect, useState } from "react";
import {
  Button,
  Col,
  Flex,
  Form,
  Modal,
  Row,
  Select,
  SelectProps,
  Tabs,
  Tag,
} from "antd";
import { useModalContext } from "../../ModalContextProvider";
import Dragger from "antd/lib/upload/Dragger";
import { Editor } from "@monaco-editor/react";
import {
  GraphQLOperationInfo,
  GraphQLUtil,
} from "../../mapper/util/graphql.ts";
import { buildSchema } from "graphql/utilities";
import { UploadChangeParam, UploadFile } from "antd/lib/upload/interface";
import {
  DataType,
  MappingDescription,
  SchemaKind,
} from "../../mapper/model/model.ts";
import { ChainContext } from "../../pages/ChainPage.tsx";
import { Chain, Dependency, Element } from "../../api/apiTypes.ts";
import { editor, MarkerSeverity } from "monaco-editor";
import {
  AttributeImporter,
  SourceFormat,
  SourceType,
} from "../../mapper/util/attribute-import.ts";
import { useNotificationService } from "../../hooks/useNotificationService.tsx";
import { capitalize } from "../../misc/format-utils.ts";
import { exportAsJsonSchema } from "../../mapper/json-schema/json-schema.ts";
import { api } from "../../api/api.ts";
import { Icon } from "../../IconProvider.tsx";

function buildGraphQLOperations(schemaText: string, queryText: string) {
  const operations: GraphQLOperationInfo[] = [];
  if (schemaText.trim()) {
    try {
      const schema = buildSchema(schemaText);
      operations.push(...GraphQLUtil.getOperationInfo(schema));
      if (queryText.trim()) {
        operations.push(...GraphQLUtil.getOperationInfoFromQuery(queryText));
      }
    } catch {
      // TODO
      return operations;
    }
  }
  return operations;
}

function buildOperationSelectOptions(
  operations: GraphQLOperationInfo[],
): NonNullable<SelectProps["options"]> {
  let unnamedOperationNumber = 0;
  return operations.map((operation, index) => ({
    value: operation.name ?? index,
    label: (
      <>
        <Tag
          color={operation.type === "query" ? "geekblue" : "magenta"}
          closable={false}
        >
          {operation.type}
        </Tag>
        <span>
          {operation.name ??
            `unnamed operation #${(unnamedOperationNumber += 1)}`}
        </span>
      </>
    ),
  }));
}

export type LoadSchemaDialogProps = {
  elementId: string;
  onSubmit?: (type: DataType) => void;
};

type GraphQLSample = {
  operation: string;
  schema: string;
  query: string;
};

type CodeSample = {
  language?: string;
  text?: string;
  graphql?: GraphQLSample;
};

type FormData = {
  schema: CodeSample & { element: string; operation: string };
  file: File;
  sample: CodeSample;
};

function importFile(
  file: File,
  onSuccess: (type: DataType) => void,
  onError: (error: unknown) => void,
) {
  const reader = new FileReader();
  reader.addEventListener("load", () => {
    try {
      const type = AttributeImporter.importDataType(
        reader.result as string,
        SourceFormat.UNSPECIFIED,
        SourceType.UNSPECIFIED,
      );
      onSuccess(type);
    } catch (error) {
      onError(error);
    }
  });
  reader.addEventListener("error", () => {
    onError(reader.error);
  });
  reader.readAsText(file);
}

function importCode(
  sample: CodeSample,
  onSuccess: (type: DataType) => void,
  onError: (error: unknown) => void,
) {
  const isGraphQL = sample.language === "graphql";
  const text = (isGraphQL ? sample.graphql?.schema : sample.text) ?? "";
  const format: SourceFormat = isGraphQL
    ? SourceFormat.GRAPHQL
    : sample.language === "json"
      ? SourceFormat.JSON
      : SourceFormat.XML;
  const options: Record<string, unknown> = isGraphQL
    ? {
        operation: sample.graphql?.operation,
        query: sample.graphql?.query,
      }
    : {};
  try {
    const type = AttributeImporter.importDataType(
      text,
      format,
      SourceType.UNSPECIFIED,
      options,
    );
    onSuccess(type);
  } catch (error) {
    onError(error);
  }
}

function importDataType(
  activeTab: string,
  formData: FormData,
  onSuccess: (type: DataType) => void,
  onError: (error: unknown) => void,
): void {
  switch (activeTab) {
    case "schema": {
      importCode(formData.schema, onSuccess, onError);
      break;
    }
    case "file": {
      importFile(formData.file, onSuccess, onError);
      break;
    }
    default: /* sample */ {
      importCode(formData.sample, onSuccess, onError);
    }
  }
}

function isMapperElement(element: Element): boolean {
  return element.type === "mapper-2";
}

function isOperationSet(element: Element): boolean {
  return !!element.properties?.["integrationOperationId"];
}

function isConfiguredGraphQLServiceCallElement(element: Element): boolean {
  return (
    element.properties?.["integrationOperationProtocolType"] === "graphql" &&
    !!element.properties?.["integrationSystemId"] &&
    !!element.properties?.["integrationSpecificationId"] &&
    !!element.properties?.["integrationGqlQuery"]
  );
}

function getHopsBetweenElements(
  from: string,
  to: string,
  connections: Dependency[],
): number {
  const nodeMap = new Map<string, number>([[from, 0]]);
  const result = new Map<string, number>([]);
  while (nodeMap.size > 0 && !result.has(to)) {
    const [node, cost] = Array.from(nodeMap.entries()).sort(
      (e0, e1) => e0[1] - e1[1],
    )[0];
    result.set(node, cost);
    nodeMap.delete(node);
    connections
      .filter(
        (connection) =>
          connection.from === node &&
          !result.has(connection.to) &&
          (nodeMap.get(connection.to) ?? Infinity > cost + 1),
      )
      .forEach((connection) => {
        nodeMap.set(connection.to, cost + 1);
      });
  }
  return result.get(to) ?? Infinity;
}

type ElementOption = NonNullable<SelectProps["options"]>["0"] & {
  element: Element;
};

type OperationOption = NonNullable<SelectProps["options"]>["0"] &
  Partial<GraphQLSample>;

function buildElementOptions(elementId: string, chain: Chain): ElementOption[] {
  return chain.elements
    .filter(
      (element) =>
        element.id !== elementId &&
        (isMapperElement(element) ||
          isOperationSet(element) ||
          isConfiguredGraphQLServiceCallElement(element)),
    )
    .map(
      (element) =>
        [
          element,
          getHopsBetweenElements(element.id, elementId, chain.dependencies),
        ] as [Element, number],
    )
    .sort((e0, e1) => e0[1] - e1[1])
    .map(([element, hops]) => ({
      value: element.id,
      label: hops === 1 ? <b>{element.name}</b> : element.name,
      element,
    }));
}

async function buildGraphQLOperationOptions(
  element: Element,
): Promise<OperationOption[]> {
  const specificationId = element.properties?.[
    "integrationSpecificationId"
  ] as string;
  const query = element.properties?.["integrationGqlQuery"] as string;
  const operationName = (
    element.properties?.["integrationGqlOperationName"] as string
  )?.trim();
  const schema = await api.getSpecificationModelSource(specificationId);
  const operation = GraphQLUtil.getOperationInfoFromQuery(query).find(
    (op) => !operationName || op.name === operationName,
  );
  if (!operation) {
    if (operationName) {
      throw new Error(`operation "${operationName}" not found`);
    }
  }
  return operation
    ? buildOperationSelectOptions([operation]).map((item) => ({
        ...item,
        schema,
        operation: operation.name,
        query,
      }))
    : [];
}

function buildMapperOperationOptions(element: Element): OperationOption[] {
  const mappingDescription = element.properties?.[
    "mappingDescription"
  ] as MappingDescription;
  if (!mappingDescription) {
    return [];
  }
  return [SchemaKind.SOURCE, SchemaKind.TARGET]
    .map(
      (schemaKind) =>
        [schemaKind, mappingDescription[schemaKind].body] as [
          SchemaKind,
          DataType | null,
        ],
    )
    .filter(([, type]) => !!type)
    .map(([schemaKind, type]) => ({
      value: schemaKind.toString(),
      label: capitalize(schemaKind.toLowerCase()),
      schema: JSON.stringify(exportAsJsonSchema(type!, []), undefined, 2),
    }));
}

async function buildServiceOperationOptions(
  element: Element,
): Promise<OperationOption[]> {
  const operationId = element.properties?.["integrationOperationId"] as string;
  const operationInfo = await api.getOperationInfo(operationId);
  return [
    ...Object.entries(operationInfo.requestSchema ?? {})
      .slice(0, 1)
      .map(([mediaType, schema]) => ({
        value: `request-${mediaType}`,
        label: "Request",
        schema: JSON.stringify(schema, undefined, 2),
      })),
    ...Object.entries(operationInfo.responseSchemas ?? {}).map(
      ([name, schema]) => ({
        value: name,
        label: name,
        schema: JSON.stringify(schema, undefined, 2),
      }),
    ),
  ];
}

async function buildOperationOptions(
  element: Element | undefined,
): Promise<OperationOption[]> {
  if (!element) {
    return [];
  }
  if (isConfiguredGraphQLServiceCallElement(element)) {
    return buildGraphQLOperationOptions(element);
  } else if (isMapperElement(element)) {
    return buildMapperOperationOptions(element);
  } else if (isOperationSet(element)) {
    return buildServiceOperationOptions(element);
  }
  return [];
}

export const LoadSchemaDialog: React.FC<LoadSchemaDialogProps> = ({
  elementId,
  onSubmit,
}) => {
  const chainContext = useContext(ChainContext);
  const { closeContainingModal } = useModalContext();
  const notificationService = useNotificationService();
  const [form] = Form.useForm<FormData>();
  const [isOperationsLoading, setIsOperationsLoading] = useState(false);
  const [selectedLanguage, setSelectedLanguage] = useState<string>("json");
  const [graphqlOperationOptions, setGraphqlOperationOptions] = useState<
    SelectProps["options"]
  >([]);
  const [activeTab, setActiveTab] = useState<string>("schema");
  const [elementOptions, setElementOptions] = useState<ElementOption[]>([]);
  const [selectedElement, setSelectedElement] = useState<Element | undefined>(
    undefined,
  );
  const [operationOptions, setOperationOptions] = useState<OperationOption[]>(
    [],
  );
  const [sampleErrors, setSampleErrors] = useState<editor.IMarker[]>([]);
  const [graphqlSchemaErrors, setGraphqlSchemaErrors] = useState<
    editor.IMarker[]
  >([]);
  const [graphqlQueryErrors, setGraphqlQueryErrors] = useState<
    editor.IMarker[]
  >([]);

  useEffect(() => {
    setIsOperationsLoading(true);
    void buildOperationOptions(selectedElement)
      .then((options) => setOperationOptions(options))
      .catch((error) => {
        notificationService.requestFailed("Failed to get schemas", error);
        setOperationOptions([]);
      })
      .finally(() => setIsOperationsLoading(false));
  }, [notificationService, selectedElement]);

  useEffect(() => {
    const options = chainContext?.chain
      ? buildElementOptions(elementId, chainContext.chain)
      : [];
    setElementOptions(options);
  }, [elementId, chainContext]);

  useEffect(() => {
    if (!!form && !!elementOptions && elementOptions.length > 0) {
      form.setFieldValue(["schema", "element"], elementOptions[0].value);
      setSelectedElement(elementOptions[0].element);
    }
  }, [form, elementOptions]);

  const onOperationChange = useCallback(
    (option: OperationOption | undefined) => {
      if (
        selectedElement &&
        isConfiguredGraphQLServiceCallElement(selectedElement)
      ) {
        form.setFieldValue(["schema", "graphql", "schema"], option?.schema);
        form.setFieldValue(["schema", "graphql", "query"], option?.query);
      } else {
        form.setFieldValue(["schema", "text"], option?.schema);
      }
    },
    [form, selectedElement],
  );

  useEffect(() => {
    if (form) {
      if (!!operationOptions && operationOptions.length > 0) {
        form.setFieldValue(["schema", "operation"], operationOptions[0].value);
      } else {
        form.setFieldValue(["schema", "operation"], undefined);
      }
      onOperationChange(operationOptions[0]);
    }
    form.setFieldValue(
      ["schema", "language"],
      selectedElement && isConfiguredGraphQLServiceCallElement(selectedElement)
        ? "graphql"
        : "json",
    );
  }, [form, onOperationChange, operationOptions, selectedElement]);

  const languageOptions: SelectProps["options"] = [
    { value: "json", label: "JSON" },
    { value: "xml", label: "XML" },
    { value: "graphql", label: "GraphQL" },
  ];

  return (
    <Modal
      title="Load source"
      open={true}
      onCancel={closeContainingModal}
      footer={[
        <Button
          key="submit"
          type="primary"
          htmlType={"submit"}
          form={"loadForm"}
        >
          Submit
        </Button>,
      ]}
    >
      <Form<FormData>
        id={"loadForm"}
        form={form}
        initialValues={{
          sample: {
            language: selectedLanguage,
          },
        }}
        style={{ height: "60vh", display: "flex", flexDirection: "column" }}
        labelWrap
        onValuesChange={(changes: Partial<FormData>, values) => {
          if (changes.sample?.language) {
            setSelectedLanguage(changes.sample?.language);
          }
          if (
            ((changes.sample?.graphql &&
              (changes.sample?.graphql?.schema ||
                changes.sample?.graphql?.query)) ||
              changes.sample?.language) &&
            values.sample?.language === "graphql"
          ) {
            const operations = buildGraphQLOperations(
              values.sample?.graphql?.schema ?? "",
              values.sample?.graphql?.query ?? "",
            );
            const options = buildOperationSelectOptions(operations);
            setGraphqlOperationOptions(options);
          }
        }}
        onFinish={(data) => {
          importDataType(
            activeTab,
            data,
            (type) => {
              onSubmit?.(type);
              closeContainingModal();
            },
            (error) => {
              notificationService.requestFailed(
                "Failed to import schema",
                error,
              );
            },
          );
        }}
      >
        <Tabs
          style={{ height: "100%" }}
          className={"flex-tabs"}
          defaultActiveKey="schema"
          onChange={(tabName) => {
            setActiveTab(tabName);
          }}
          items={[
            {
              key: "schema",
              label: "Schema",
              children: (
                <Flex vertical style={{ height: "100%" }} gap={16}>
                  <Row gutter={[16, 16]}>
                    <Col span={12}>
                      <Form.Item
                        name={["schema", "element"]}
                        label={"Element"}
                        rules={[
                          {
                            required: activeTab === "schema",
                            message: "Element is required",
                          },
                        ]}
                      >
                        <Select<string, ElementOption>
                          options={elementOptions}
                          onChange={(_value, option) => {
                            const element = Array.isArray(option)
                              ? undefined
                              : option?.element;
                            setSelectedElement(element);
                          }}
                        />
                      </Form.Item>
                    </Col>
                    <Col span={12}>
                      <Form.Item
                        name={["schema", "operation"]}
                        label={
                          selectedElement &&
                          isConfiguredGraphQLServiceCallElement(selectedElement)
                            ? "Operation"
                            : "Schema"
                        }
                      >
                        <Select<string, OperationOption>
                          loading={isOperationsLoading}
                          options={operationOptions}
                          onChange={(_value, option) => {
                            const opt = Array.isArray(option)
                              ? undefined
                              : option;
                            onOperationChange(opt);
                          }}
                        />
                      </Form.Item>
                    </Col>
                  </Row>
                  <Form.Item name={["schema", "language"]} hidden={true}>
                    <Select<string> options={languageOptions} />
                  </Form.Item>
                  {selectedElement &&
                  isConfiguredGraphQLServiceCallElement(selectedElement) ? (
                    <>
                      <Form.Item
                        className={"flex-form-item"}
                        name={["schema", "graphql", "schema"]}
                        label={"Schema"}
                        layout={"vertical"}
                      >
                        <Editor
                          className="qip-editor"
                          language={"graphql"}
                          options={{
                            readOnly: true,
                            fixedOverflowWidgets: true,
                          }}
                        />
                      </Form.Item>
                      <Form.Item
                        className={"flex-form-item"}
                        name={["schema", "graphql", "query"]}
                        label={"Query"}
                        layout={"vertical"}
                      >
                        <Editor
                          className="qip-editor"
                          language={"graphql"}
                          options={{
                            readOnly: true,
                            fixedOverflowWidgets: true,
                          }}
                        />
                      </Form.Item>
                    </>
                  ) : (
                    <Form.Item
                      className={"flex-form-item"}
                      name={["schema", "text"]}
                      label={"Schema"}
                      layout={"vertical"}
                    >
                      <Editor
                        className="qip-editor"
                        options={{ readOnly: true, fixedOverflowWidgets: true }}
                        language={"json"}
                      />
                    </Form.Item>
                  )}
                </Flex>
              ),
            },
            {
              key: "file",
              label: "File",
              children: (
                <Form.Item
                  style={{ height: "100%" }}
                  className={"flex-form-item"}
                  name={"file"}
                  valuePropName={"fileList"}
                  getValueProps={(value: UploadFile) => {
                    return {
                      fileList:
                        value && value.status !== "removed" ? [value] : [],
                    };
                  }}
                  normalize={(value: UploadChangeParam) => {
                    return value.file;
                  }}
                  rules={[
                    {
                      required: activeTab === "file",
                      message: "File is required",
                    },
                  ]}
                >
                  <Dragger
                    rootClassName="flex-dragger width100"
                    multiple={false}
                    beforeUpload={() => false}
                  >
                    <p className="ant-upload-drag-icon">
                      <Icon name="inbox" />
                    </p>
                    <p className="ant-upload-text">
                      Click or drag file to this area to upload
                    </p>
                  </Dragger>
                </Form.Item>
              ),
            },
            {
              key: "sample",
              label: "Code",
              children: (
                <Flex vertical style={{ height: "100%" }} gap={16}>
                  <Row gutter={[16, 16]}>
                    <Col span={12}>
                      <Form.Item
                        name={["sample", "language"]}
                        label={"Language"}
                        rules={[
                          {
                            required: activeTab === "sample",
                            message: "Language is required",
                          },
                        ]}
                      >
                        <Select<string> options={languageOptions} />
                      </Form.Item>
                    </Col>
                    {selectedLanguage === "graphql" ? (
                      <Col span={12}>
                        <Form.Item
                          name={["sample", "graphql", "operation"]}
                          label={"Operation"}
                          rules={[
                            {
                              required: activeTab === "sample",
                              message: "Operation is required",
                            },
                          ]}
                        >
                          <Select<string> options={graphqlOperationOptions} />
                        </Form.Item>
                      </Col>
                    ) : (
                      <></>
                    )}
                  </Row>
                  {selectedLanguage === "graphql" ? (
                    <>
                      <Form.Item
                        className={"flex-form-item"}
                        name={["sample", "graphql", "schema"]}
                        label={"Schema"}
                        layout={"vertical"}
                        rules={[
                          {
                            required: activeTab === "sample",
                            message: "GraphQL schema is required",
                          },
                          () => ({
                            validator() {
                              return graphqlSchemaErrors.length === 0
                                ? Promise.resolve()
                                : Promise.reject(
                                    new Error(
                                      graphqlSchemaErrors
                                        .map((marker) => marker.message)
                                        .join(" "),
                                    ),
                                  );
                            },
                          }),
                        ]}
                      >
                        <Editor
                          className="qip-editor"
                          language={"graphql"}
                          onValidate={(markers) => {
                            setGraphqlSchemaErrors(
                              markers.filter(
                                (marker) =>
                                  marker.severity === MarkerSeverity.Error,
                              ),
                            );
                            void form.validateFields([
                              ["sample", "graphql", "schema"],
                            ]);
                          }}
                          options={{ fixedOverflowWidgets: true }}
                        />
                      </Form.Item>
                      <Form.Item
                        className={"flex-form-item"}
                        name={["sample", "graphql", "query"]}
                        label={"Query"}
                        layout={"vertical"}
                        rules={[
                          () => ({
                            validator() {
                              return graphqlQueryErrors.length === 0
                                ? Promise.resolve()
                                : Promise.reject(
                                    new Error(
                                      graphqlQueryErrors
                                        .map((marker) => marker.message)
                                        .join(" "),
                                    ),
                                  );
                            },
                          }),
                        ]}
                      >
                        <Editor
                          className="qip-editor"
                          language={"graphql"}
                          onValidate={(markers) => {
                            setGraphqlQueryErrors(
                              markers.filter(
                                (marker) =>
                                  marker.severity === MarkerSeverity.Error,
                              ),
                            );
                            void form.validateFields([
                              ["sample", "graphql", "query"],
                            ]);
                          }}
                          options={{ fixedOverflowWidgets: true }}
                        />
                      </Form.Item>
                    </>
                  ) : (
                    <Form.Item
                      className={"flex-form-item"}
                      name={["sample", "text"]}
                      rules={[
                        {
                          required: activeTab === "sample",
                          message: "Code sample is required",
                        },
                        () => ({
                          validator() {
                            return sampleErrors.length === 0
                              ? Promise.resolve()
                              : Promise.reject(
                                  new Error(
                                    sampleErrors
                                      .map((marker) => marker.message)
                                      .join(" "),
                                  ),
                                );
                          },
                        }),
                      ]}
                    >
                      <Editor
                        className="qip-editor"
                        language={selectedLanguage}
                        onValidate={(markers) => {
                          setSampleErrors(
                            markers.filter(
                              (marker) =>
                                marker.severity === MarkerSeverity.Error,
                            ),
                          );
                          void form.validateFields([["sample", "text"]]);
                        }}
                        options={{ fixedOverflowWidgets: true }}
                      />
                    </Form.Item>
                  )}
                </Flex>
              ),
            },
          ]}
        />
      </Form>
    </Modal>
  );
};
