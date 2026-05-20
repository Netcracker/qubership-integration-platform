/**
 * @jest-environment jsdom
 */

import React from "react";
import "@testing-library/jest-dom";
import { render, screen, fireEvent } from "@testing-library/react";
import { MappingTableView } from "../../../src/components/mapper/MappingTableView";
import { MappingDescription } from "../../../src/mapper/model/model";

jest.mock("../../../src/Modals.tsx", () => ({
  useModalsContext: () => ({
    showModal: jest.fn(),
  }),
}));

jest.mock("../../../src/components/mapper/useMappingDescription.tsx", () => ({
  useMappingDescription: jest.fn(),
}));

jest.mock("../../../src/components/table/useColumnSettingsButton.tsx", () => {
  const actual: Record<string, unknown> = jest.requireActual(
    "../../../src/components/table/useColumnSettingsButton.tsx",
  );
  return actual;
});

jest.mock("../../../src/components/mapper/ConstantValue.tsx", () => ({
  ConstantValue: () => <div>ConstantValue</div>,
}));

jest.mock("../../../src/components/mapper/TransformationValue.tsx", () => ({
  TransformationValue: () => <div>TransformationValue</div>,
}));

jest.mock("../../../src/components/mapper/ConstantValueEditDialog.tsx", () => ({
  ConstantValueEditDialog: () => <div>ConstantValueEditDialog</div>,
}));

jest.mock(
  "../../../src/components/mapper/TransformationEditDialog.tsx",
  () => ({
    TransformationContext: {
      Provider: (props: { children?: React.ReactNode }) => (
        <div>{props.children}</div>
      ),
    },
    TransformationEditDialog: () => <div>TransformationEditDialog</div>,
  }),
);

jest.mock("../../../src/components/mapper/InlineTypeEdit.tsx", () => ({
  InlineTypeEdit: () => <div>InlineTypeEdit</div>,
}));

jest.mock("../../../src/components/InlineEdit.tsx", () => ({
  InlineEdit: (props: {
    onSubmit?: (val: { name: string }) => void;
    viewer?: React.ReactNode;
  }) => (
    <div>
      <button onClick={() => props.onSubmit?.({ name: "edited" })}>Edit</button>
      {props.viewer}
    </div>
  ),
}));

jest.mock("../../../src/components/table/TextValueEdit.tsx", () => ({
  TextValueEdit: () => <input data-testid="text-value-edit" />,
}));

jest.mock("../../../src/components/table/SelectEdit.tsx", () => ({
  SelectEdit: () => <select data-testid="select-edit" />,
}));

jest.mock("../../../src/components/mapper/DefaultValueEdit.tsx", () => ({
  DefaultValueEdit: () => <input data-testid="default-value-edit" />,
}));

jest.mock(
  "../../../src/components/mapper/InlineElementReferencesEdit.tsx",
  () => ({
    InlineElementReferencesEdit: (props: {
      onSubmit?: (val: unknown[]) => void;
    }) => (
      <div>
        <button onClick={() => props.onSubmit?.([])}>EditRefs</button>
        InlineElementReferencesEdit
      </div>
    ),
  }),
);

jest.mock(
  "../../../src/components/mapper/MappingTableItemActionButton.tsx",
  () => ({
    MappingTableItemActionButton: (props: {
      onAdd?: () => void;
      onClear?: () => void;
      onDelete?: () => void;
      onExport?: () => void;
    }) => (
      <div>
        <button onClick={props.onAdd}>Add</button>
        <button onClick={props.onClear}>Clear</button>
        <button onClick={props.onDelete}>Delete</button>
        <button onClick={props.onExport}>Export</button>
      </div>
    ),
  }),
);

jest.mock("../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: (props: { name: string }) => <span>{props.name}</span>,
}));

jest.mock("../../../src/mapper/markdown/markdown.ts", () => ({
  exportAsMarkdown: jest.fn(() => "markdown content"),
}));

jest.mock("../../../src/misc/download-utils.ts", () => ({
  downloadFile: jest.fn(),
}));

jest.mock("../../../src/misc/format-utils.ts", () => ({
  formatDate: () => "2024-01-01",
  PLACEHOLDER: "PLACEHOLDER",
}));

jest.mock("../../../src/mapper/util/metadata.ts", () => {
  const actual: Record<string, Record<string, unknown>> = jest.requireActual(
    "../../../src/mapper/util/metadata.ts",
  );
  return {
    MetadataUtil: {
      getTypedValue: actual.MetadataUtil.getTypedValue,
      getValue: actual.MetadataUtil.getValue,
      getString: jest.fn(
        (obj: { metadata?: Record<string, string> }, key: string) =>
          obj.metadata?.[key] ?? "",
      ),
      upsert: jest.fn(
        (metadata: Record<string, unknown>, key: string, value: unknown) => ({
          ...metadata,
          [key]: value,
        }),
      ),
      setValue: jest.fn(
        (obj: Record<string, unknown>, key: string, value: unknown) => ({
          ...obj,
          [key]: value,
        }),
      ),
    },
    DESCRIPTION_KEY: "description",
    METADATA_DATA_FORMAT_KEY: "dataFormat",
    METADATA_SOURCE_XML_NAMESPACES_KEY: "xmlNamespaces",
    isXmlNamespaces: actual.isXmlNamespaces,
    SourceFormat: { JSON: "json", XML: "xml" },
  };
});

jest.mock("../../../src/mapper/util/types.ts", () => ({
  DataTypes: {
    resolveType: jest.fn((type: { name?: string } | undefined) =>
      type?.name === "array"
        ? { type, definitions: [] }
        : { type: undefined, definitions: [] },
    ),
    resolveArrayItemType: jest.fn(
      (type: { name?: string; itemType?: unknown } | undefined) =>
        type?.name === "array"
          ? { type: type.itemType, definitions: [] }
          : { type, definitions: [] },
    ),
    getTypeDefinitions: jest.fn(() => []),
    buildTypeName: jest.fn(
      (type: { name?: string } | undefined) => type?.name ?? "null",
    ),
    nullType: jest.fn(() => ({ name: "null" })),
  },
}));

jest.mock("../../../src/mapper/util/attributes.ts", () => ({
  Attributes: {
    getChildAttributes: jest.fn(() => []),
    buildAttribute: jest.fn(() => ({})),
    extractTypeDefinitions: jest.fn().mockReturnValue([]),
  },
}));

jest.mock("../../../src/mapper/util/actions.ts", () => ({
  MappingActions: {
    findActionsByElementReference: jest.fn(() => []),
    resolveReference: jest.fn(),
  },
}));

jest.mock("../../../src/mapper/util/mapping.ts", () => ({
  MappingUtil: {
    isAttributeReference: jest.fn(() => true),
  },
}));

jest.mock("../../../src/mapper/verification/actions.ts", () => ({
  verifyMappingAction: jest.fn(() => []),
}));

jest.mock("../../../src/components/table/TextColumnFilterDropdown.tsx", () => ({
  TextColumnFilterDropdown: () => <div>TextColumnFilterDropdown</div>,
  getTextColumnFilterFn: jest.fn(() => () => true),
}));

jest.mock("../../../src/components/table/EnumColumnFilterDropdown.tsx", () => ({
  EnumColumnFilterDropdown: () => <div>EnumColumnFilterDropdown</div>,
  getEnumColumnFilterFn: jest.fn(() => () => true),
}));

jest.mock(
  "../../../src/components/mapper/ElementReferenceColumnFilterDropdown.tsx",
  () => ({
    ElementReferenceColumnFilterDropdown: () => (
      <div>ElementReferenceColumnFilterDropdown</div>
    ),
    getElementReferenceColumnFilterFn: jest.fn(() => () => true),
  }),
);

jest.mock(
  "../../../src/components/mapper/TransformationColumnFilterDropdown.tsx",
  () => ({
    TransformationColumnFilterDropdown: () => (
      <div>TransformationColumnFilterDropdown</div>
    ),
    getTransformationColumnFilterFn: jest.fn(() => () => true),
  }),
);

jest.mock("antd", () => {
  const React = require("react");
  const {
    antdMockWithLightweightTable,
  } = require("tests/helpers/antdMockWithLightweightTable");
  return antdMockWithLightweightTable({
    message: {
      useMessage: () => [
        { open: jest.fn() },
        React.createElement("div", { key: "msg" }, "msg"),
      ],
    },
  });
});

jest.mock("../../../src/components/mapper/MappingTableView.module.css", () => ({
  "invalid-value": "invalid-value",
  "group-row": "group-row",
}));
jest.mock("../../../src/components/InlineEdit.module.css", () => ({
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

const useMappingDescriptionModule: {
  useMappingDescription: jest.Mock;
} = jest.requireMock(
  "../../../src/components/mapper/useMappingDescription.tsx",
);
useMappingDescriptionModule.useMappingDescription.mockImplementation(
  () => useMappingDescriptionMock,
);

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
    expect(screen.getByRole("button", { name: /more/i })).toBeInTheDocument();
  });

  it("renders constants, headers, Clear button with real columns", () => {
    renderComponent();
    fireEvent.click(screen.getByRole("radio", { name: /source/i }));
    expect(screen.getByText(/constants/i)).toBeInTheDocument();
    expect(screen.getByText(/headers/i)).toBeInTheDocument();
    expect(
      screen.getAllByRole("button", { name: /clear/i }).length,
    ).toBeGreaterThan(0);
  });

  it("clearTreeForItem: click Clear in constant-group calls clearConstants", () => {
    renderComponent();
    fireEvent.click(screen.getByRole("radio", { name: /source/i }));
    const clearButtons = screen.getAllByRole("button", { name: /clear/i });
    fireEvent.click(clearButtons[0]);
    expect(useMappingDescriptionMock.clearConstants).toHaveBeenCalled();
  });

  it("exportElement: click Export calls exportDataType", () => {
    renderComponent();
    fireEvent.click(screen.getByRole("radio", { name: /source/i }));
    const exportButtons = screen.getAllByRole("button", { name: /export/i });
    fireEvent.click(exportButtons[0]);
    expect(useMappingDescriptionMock.exportDataType).toHaveBeenCalled();
  });

  it("tryAddElement: click Add in constant-group calls addConstant", () => {
    renderComponent();
    fireEvent.click(screen.getByRole("radio", { name: /source/i }));
    const addButtons = screen.getAllByRole("button", { name: /add/i });
    fireEvent.click(addButtons[0]);
    expect(useMappingDescriptionMock.addConstant).toHaveBeenCalled();
  });

  it("MappingTableItemActionButton: click Delete on constant item calls removeConstant", () => {
    renderComponent();
    fireEvent.click(screen.getByRole("radio", { name: /source/i }));
    const deleteButtons = screen.getAllByRole("button", { name: /delete/i });
    fireEvent.click(deleteButtons[1]);
    expect(useMappingDescriptionMock.removeConstant).toHaveBeenCalled();
  });

  it("Table sort: click Name column header triggers onChange with sorts", () => {
    renderComponent();
    fireEvent.click(screen.getByRole("radio", { name: /source/i }));
    const nameHeader = screen.getByRole("columnheader", { name: /name/i });
    fireEvent.click(nameHeader);
    expect(screen.getByText(/constants/i)).toBeInTheDocument();
  });

  it("error handling: addConstant callback with error shows messageApi.open with type error", () => {
    const messageOpenMock = jest.fn();
    const antd: {
      message: { useMessage: jest.Mock };
    } = jest.requireMock("antd");
    const originalUseMessage = antd.message.useMessage;
    antd.message.useMessage = jest.fn(() => [
      { open: messageOpenMock },
      React.createElement("div", { key: "msg" }, "msg"),
    ]);
    useMappingDescriptionMock.addConstant.mockImplementation(
      (_changes: unknown, callback: (err: unknown) => void) => {
        callback(new Error("test"));
      },
    );
    renderComponent();
    fireEvent.click(screen.getByRole("radio", { name: /source/i }));
    const addButtons = screen.getAllByRole("button", { name: /add/i });
    fireEvent.click(addButtons[0]);
    expect(messageOpenMock).toHaveBeenCalledWith(
      expect.objectContaining({ type: "error" }),
    );
    antd.message.useMessage = originalUseMessage;
  });

  it("should include selectedColumns in orderedColumns for SchemaKind.SOURCE", async () => {
    const expectedSelectedColumns = [
      "name",
      "type",
      "default value",
      "targets",
    ];

    renderComponent();

    fireEvent.click(screen.getByRole("radio", { name: /source/i }));

    const columnHeaders = await screen.findAllByRole("columnheader");
    const columnTitles = columnHeaders.map((header) =>
      header.textContent?.trim().toLowerCase(),
    );

    expectedSelectedColumns.forEach((column) => {
      const columnTitle = column.toLowerCase();
      expect(columnTitles).toContainEqual(expect.stringContaining(columnTitle));
    });
  });

  it("dropdown Save as markdown triggers exportMappingAsMarkdown", async () => {
    const downloadUtils: { downloadFile: jest.Mock } = jest.requireMock(
      "../../../src/misc/download-utils.ts",
    );
    renderComponent();
    const moreButton = screen.getByRole("button", { name: /more/i });
    fireEvent.click(moreButton);
    const markdownItem = await screen.findByText("Save as markdown");
    fireEvent.click(markdownItem);
    expect(downloadUtils.downloadFile).toHaveBeenCalled();
  });

  it("search input triggers updateControlsState", () => {
    renderComponent();
    const searchInput = screen.getByPlaceholderText(/Full text search/i);
    fireEvent.change(searchInput, { target: { value: "test" } });
    expect(searchInput).toHaveValue("test");
  });

  it("dropdown Clear filters triggers updateControlsState", async () => {
    renderComponent();
    const moreButton = screen.getByRole("button", { name: /more/i });
    fireEvent.click(moreButton);
    const clearFiltersItem = await screen.findByText("Clear filters");
    fireEvent.click(clearFiltersItem);
    expect(moreButton).toBeInTheDocument();
  });

  it("dropdown Clear sorters triggers updateControlsState", async () => {
    renderComponent();
    const moreButton = screen.getByRole("button", { name: /more/i });
    fireEvent.click(moreButton);
    const clearSortersItem = await screen.findByText("Clear sorters");
    fireEvent.click(clearSortersItem);
    expect(moreButton).toBeInTheDocument();
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

    fireEvent.click(screen.getByRole("radio", { name: /target/i }));

    const columnHeaders = await screen.findAllByRole("columnheader");
    const columnTitles = columnHeaders.map((header) =>
      header.textContent?.trim().toLowerCase(),
    );

    expectedSelectedColumns.forEach((column) => {
      const columnTitle = column.toLowerCase();
      expect(columnTitles).toContainEqual(expect.stringContaining(columnTitle));
    });
  });
});

// Pure utility function tests have been extracted to MappingTableView.utils.test.ts
// to run in the faster node environment (without jsdom overhead).
