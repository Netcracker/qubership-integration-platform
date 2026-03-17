/**
 * @jest-environment jsdom
 */

Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: jest.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: jest.fn(),
    removeListener: jest.fn(),
    addEventListener: jest.fn(),
    removeEventListener: jest.fn(),
    dispatchEvent: jest.fn(),
  })),
});

import "@testing-library/jest-dom";
import { render, screen, waitFor } from "@testing-library/react";
import { MappingTableView } from "../../../components/mapper/MappingTableView";
import { MappingDescription } from "../../../mapper/model/model";

jest.mock("../../../Modals.tsx", () => ({
  useModalsContext: () => ({
    showModal: jest.fn(),
  }),
}));

jest.mock("../../../components/mapper/useMappingDescription.tsx", () => ({
  useMappingDescription: jest.fn(),
}));

jest.mock("../../../components/table/useColumnSettingsButton.tsx", () => ({
  useColumnSettingsBasedOnColumnsType: jest
    .fn()
    .mockImplementation((storageKey, tableColumnDefinitions) => {
      return {
        orderedColumns:
          storageKey === "targetMappingTable"
            ? [
                {
                  key: "name",
                  title: "Name",
                  render: jest.fn(),
                  hidden: false,
                },
                {
                  key: "type",
                  title: "Type",
                  render: jest.fn(),
                  hidden: false,
                },
                {
                  key: "optionality",
                  title: "Optionality",
                  render: jest.fn(),
                  hidden: false,
                },
                {
                  key: "sources",
                  title: "Sources",
                  render: jest.fn(),
                  hidden: false,
                },
                {
                  key: "transformation",
                  title: "Transformation",
                  render: jest.fn(),
                  hidden: false,
                },
                { key: "actions", title: "", render: jest.fn(), hidden: false },
              ]
            : [
                {
                  key: "name",
                  title: "Name",
                  render: jest.fn(),
                  hidden: false,
                },
                {
                  key: "type",
                  title: "Type",
                  render: jest.fn(),
                  hidden: false,
                },
                {
                  key: "optionality",
                  title: "Optionality",
                  render: jest.fn(),
                  hidden: false,
                },
                {
                  key: "defaultValue",
                  title: "Default Value",
                  render: jest.fn(),
                  hidden: false,
                },
                {
                  key: "targets",
                  title: "Targets",
                  render: jest.fn(),
                  hidden: false,
                },
                { key: "actions", title: "", render: jest.fn(), hidden: false },
              ],
        columnSettingsButton: <button>Column Settings</button>,
      };
    }),
}));

jest.mock("../../../components/mapper/ConstantValue.tsx", () => ({
  ConstantValue: () => <div>ConstantValue</div>,
}));

jest.mock("../../../components/mapper/TransformationValue.tsx", () => ({
  TransformationValue: () => <div>TransformationValue</div>,
}));

jest.mock("../../../components/mapper/ConstantValueEditDialog.tsx", () => ({
  ConstantValueEditDialog: () => <div>ConstantValueEditDialog</div>,
}));

jest.mock("../../../components/mapper/TransformationEditDialog.tsx", () => ({
  TransformationContext: {
    Provider: (props: any) => <div>{props.children}</div>,
  },
  TransformationEditDialog: () => <div>TransformationEditDialog</div>,
}));

jest.mock("../../../components/mapper/InlineTypeEdit.tsx", () => ({
  InlineTypeEdit: () => <div>InlineTypeEdit</div>,
}));

jest.mock("../../../components/InlineEdit.tsx", () => ({
  InlineEdit: (props: any) => (
    <div>
      <button
        onClick={() => props.onSubmit && props.onSubmit({ name: "edited" })}
      >
        Edit
      </button>
      {props.viewer}
    </div>
  ),
}));

jest.mock("../../../components/table/TextValueEdit.tsx", () => ({
  TextValueEdit: () => <input data-testid="text-value-edit" />,
}));

jest.mock("../../../components/table/SelectEdit.tsx", () => ({
  SelectEdit: () => <select data-testid="select-edit" />,
}));

jest.mock("../../../components/mapper/DefaultValueEdit.tsx", () => ({
  DefaultValueEdit: () => <input data-testid="default-value-edit" />,
}));

jest.mock("../../../components/mapper/InlineElementReferencesEdit.tsx", () => ({
  InlineElementReferencesEdit: (props: any) => (
    <div>
      <button onClick={() => props.onSubmit && props.onSubmit([])}>
        EditRefs
      </button>
      InlineElementReferencesEdit
    </div>
  ),
}));

jest.mock(
  "../../../components/mapper/MappingTableItemActionButton.tsx",
  () => ({
    MappingTableItemActionButton: (props: any) => (
      <div>
        <button onClick={props.onAdd}>Add</button>
        <button onClick={props.onClear}>Clear</button>
        <button onClick={props.onDelete}>Delete</button>
        <button onClick={props.onExport}>Export</button>
      </div>
    ),
  }),
);

jest.mock("../../../icons/IconProvider.tsx", () => ({
  OverridableIcon: (props: any) => <span>{props.name}</span>,
}));

jest.mock("../../../mapper/markdown/markdown.ts", () => ({
  exportAsMarkdown: jest.fn(() => "markdown content"),
}));

jest.mock("../../../misc/download-utils.ts", () => ({
  downloadFile: jest.fn(),
}));

jest.mock("../../../misc/format-utils.ts", () => ({
  formatDate: () => "2024-01-01",
  PLACEHOLDER: "PLACEHOLDER",
}));

jest.mock("../../../mapper/util/metadata.ts", () => ({
  MetadataUtil: {
    getString: jest.fn((obj, key) => (obj.metadata && obj.metadata[key]) || ""),
    upsert: jest.fn((metadata, key, value) => ({ ...metadata, [key]: value })),
    setValue: jest.fn((obj, key, value) => ({ ...obj, [key]: value })),
  },
  DESCRIPTION_KEY: "description",
  METADATA_DATA_FORMAT_KEY: "dataFormat",
  METADATA_SOURCE_XML_NAMESPACES_KEY: "xmlNamespaces",
  isXmlNamespaces: jest.fn(),
  SourceFormat: { JSON: "json", XML: "xml" },
}));

jest.mock("../../../mapper/util/types.ts", () => ({
  DataTypes: {
    resolveType: jest.fn((type) => ({ ...type, name: type.name })),
    getTypeDefinitions: jest.fn(() => []),
    buildTypeName: jest.fn((type) => type.name),
    nullType: jest.fn(() => ({ name: "null" })),
  },
}));

jest.mock("../../../mapper/util/attributes.ts", () => ({
  Attributes: {
    getChildAttributes: jest.fn(() => []),
    buildAttribute: jest.fn(() => ({})),
    extractTypeDefinitions: jest.fn().mockReturnValue([]),
  },
}));

jest.mock("../../../mapper/util/actions.ts", () => ({
  MappingActions: {
    findActionsByElementReference: jest.fn(() => []),
  },
}));

jest.mock("../../../mapper/util/mapping.ts", () => ({
  MappingUtil: {
    isAttributeReference: jest.fn(() => true),
  },
}));

jest.mock("../../../mapper/verification/actions.ts", () => ({
  verifyMappingAction: jest.fn(() => []),
}));

jest.mock("../../../components/table/TextColumnFilterDropdown.tsx", () => ({
  TextColumnFilterDropdown: () => <div>TextColumnFilterDropdown</div>,
  getTextColumnFilterFn: jest.fn(() => () => true),
}));

jest.mock("../../../components/table/EnumColumnFilterDropdown.tsx", () => ({
  EnumColumnFilterDropdown: () => <div>EnumColumnFilterDropdown</div>,
  getEnumColumnFilterFn: jest.fn(() => () => true),
}));

jest.mock(
  "../../../components/mapper/ElementReferenceColumnFilterDropdown.tsx",
  () => ({
    ElementReferenceColumnFilterDropdown: () => (
      <div>ElementReferenceColumnFilterDropdown</div>
    ),
    getElementReferenceColumnFilterFn: jest.fn(() => () => true),
  }),
);

jest.mock(
  "../../../components/mapper/TransformationColumnFilterDropdown.tsx",
  () => ({
    TransformationColumnFilterDropdown: () => (
      <div>TransformationColumnFilterDropdown</div>
    ),
    getTransformationColumnFilterFn: jest.fn(() => () => true),
  }),
);

jest.mock("antd", () => {
  const antd = jest.requireActual("antd");
  return {
    ...antd,
    message: {
      useMessage: () => [{ open: jest.fn() }, <div key="msg">msg</div>],
    },
  };
});

jest.mock("../../../components/mapper/MappingTableView.module.css", () => ({
  "invalid-value": "invalid-value",
  "group-row": "group-row",
}));
jest.mock("../../../components/InlineEdit.module.css", () => ({
  inlineEditValueWrap: "inlineEditValueWrap",
}));

const minimalMappingDescription = {
  constants: [
    {
      id: "const1",
      name: "Const 1",
      type: { name: "string" },
      valueSupplier: { kind: "given", value: "abc" },
      metadata: { description: "A constant" },
    },
  ],
  actions: [],
  source: {
    headers: [],
    properties: [],
    body: null,
  },
  target: {
    headers: [
      {
        id: "attr1",
        name: "Header 1",
        type: { name: "string" },
        required: true,
        defaultValue: "default",
        metadata: { description: "Header description" },
      },
    ],
    properties: [],
    body: null,
  },
};

const useMappingDescriptionMock = {
  mappingDescription: minimalMappingDescription,
  clearConstants: jest.fn(),
  clearTree: jest.fn(),
  removeConstant: jest.fn(),
  removeAttribute: jest.fn(),
  updateBodyType: jest.fn(),
  exportDataType: jest.fn(),
  addConstant: jest.fn(),
  addAttribute: jest.fn(),
  updateAttribute: jest.fn(),
  updateConstant: jest.fn(),
  updateActions: jest.fn(),
  createOrUpdateMappingActionForTarget: jest.fn(),
  createOrUpdateMappingActionsForSource: jest.fn(),
  updateXmlNamespaces: jest.fn(),
};

(
  require("../../../components/mapper/useMappingDescription.tsx") as any
).useMappingDescription.mockImplementation(() => useMappingDescriptionMock);

describe("MappingTableView", () => {
  const renderComponent = () => {
    return render(
      <MappingTableView
        elementId="el1"
        mapping={minimalMappingDescription as MappingDescription}
      />,
    );
  };

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("renders the table with source and target radio buttons and search", () => {
    renderComponent();

    expect(screen.getByRole("radio", { name: /Source/i })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: /Target/i })).toBeInTheDocument();
    expect(
      screen.getByPlaceholderText(/Full text search/i),
    ).toBeInTheDocument();
    expect(screen.getByText("Column Settings")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /more/i })).toBeInTheDocument();
  });

  it("should include selectedColumns in orderedColumns for SchemaKind.SOURCE", async () => {
    const expectedSelectedColumns = [
      "name",
      "type",
      "optionality",
      "default value",
      "targets",
    ];

    renderComponent();

    const sourceRadio = screen.getByRole("radio", { name: /source/i });
    sourceRadio.click();

    await waitFor(() => {
      const columnHeaders = screen.getAllByRole("columnheader");
      const columnTitles = columnHeaders.map((header) =>
        header.textContent?.trim().toLowerCase(),
      );

      expectedSelectedColumns.forEach((column) => {
        const columnTitle = column.toLowerCase();
        expect(columnTitles).toContainEqual(
          expect.stringContaining(columnTitle),
        );
      });
    });
  });

  it("should include selectedColumns in orderedColumns for SchemaKind.TARGET", async () => {
    const expectedSelectedColumns = [
      "name",
      "type",
      "optionality",
      "sources",
      "transformation",
    ];

    renderComponent();

    const targetRadio = screen.getByRole("radio", { name: /target/i });
    targetRadio.click();

    await waitFor(() => {
      const columnHeaders = screen.getAllByRole("columnheader");
      const columnTitles = columnHeaders.map((header) =>
        header.textContent?.trim().toLowerCase(),
      );

      expectedSelectedColumns.forEach((column) => {
        const columnTitle = column.toLowerCase();
        expect(columnTitles).toContainEqual(
          expect.stringContaining(columnTitle),
        );
      });
    });
  });
});
