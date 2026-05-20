// Mock React component and browser-dependent modules to avoid JSX/DOM issues in node environment
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
  ConstantValue: () => null,
}));

jest.mock("../../../src/components/mapper/TransformationValue.tsx", () => ({
  TransformationValue: () => null,
}));

jest.mock("../../../src/components/mapper/ConstantValueEditDialog.tsx", () => ({
  ConstantValueEditDialog: () => null,
}));

jest.mock(
  "../../../src/components/mapper/TransformationEditDialog.tsx",
  () => ({
    TransformationContext: {
      Provider: () => null,
    },
    TransformationEditDialog: () => null,
  }),
);

jest.mock("../../../src/components/mapper/InlineTypeEdit.tsx", () => ({
  InlineTypeEdit: () => null,
}));

jest.mock("../../../src/components/InlineEdit.tsx", () => ({
  InlineEdit: () => null,
}));

jest.mock("../../../src/components/table/TextValueEdit.tsx", () => ({
  TextValueEdit: () => null,
}));

jest.mock("../../../src/components/table/SelectEdit.tsx", () => ({
  SelectEdit: () => null,
}));

jest.mock("../../../src/components/mapper/DefaultValueEdit.tsx", () => ({
  DefaultValueEdit: () => null,
}));

jest.mock(
  "../../../src/components/mapper/InlineElementReferencesEdit.tsx",
  () => ({
    InlineElementReferencesEdit: () => null,
  }),
);

jest.mock(
  "../../../src/components/mapper/MappingTableItemActionButton.tsx",
  () => ({
    MappingTableItemActionButton: () => null,
  }),
);

jest.mock("../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: () => null,
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

jest.mock("../../../src/components/table/TextColumnFilterDropdown.tsx", () => ({
  TextColumnFilterDropdown: () => null,
  getTextColumnFilterFn: jest.fn(() => () => true),
}));

jest.mock("../../../src/components/table/EnumColumnFilterDropdown.tsx", () => ({
  EnumColumnFilterDropdown: () => null,
  getEnumColumnFilterFn: jest.fn(() => () => true),
}));

jest.mock(
  "../../../src/components/mapper/ElementReferenceColumnFilterDropdown.tsx",
  () => ({
    ElementReferenceColumnFilterDropdown: () => null,
    getElementReferenceColumnFilterFn: jest.fn(() => () => true),
  }),
);

jest.mock(
  "../../../src/components/mapper/TransformationColumnFilterDropdown.tsx",
  () => ({
    TransformationColumnFilterDropdown: () => null,
    getTransformationColumnFilterFn: jest.fn(() => () => true),
  }),
);

jest.mock("antd", () => ({
  Button: () => null,
  Dropdown: () => null,
  Flex: () => null,
  message: { useMessage: () => [{ open: jest.fn() }, null] },
  Radio: Object.assign(() => null, { Group: () => null, Button: () => null }),
  Select: () => null,
  Space: () => null,
  Table: () => null,
}));

jest.mock("../../../src/components/mapper/MappingTableView.module.css", () => ({
  "invalid-value": "invalid-value",
  "group-row": "group-row",
}));
jest.mock("../../../src/components/InlineEdit.module.css", () => ({
  inlineEditValueWrap: "inlineEditValueWrap",
}));

jest.mock("../../../src/components/mapper/LoadConfirmationDialog.tsx", () => ({
  LoadConfirmationDialog: () => null,
}));

// Mock mapper utility modules needed by the pure functions
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

import {
  isConstantItem,
  isAttributeItem,
  isConstantGroup,
  isHeaderGroup,
  isPropertyGroup,
  isBodyGroup,
  buildAttributeItemId,
  buildConstantId,
  buildMappingTableItems,
  filterMappingTableItems,
  buildMappingTableItemPredicate,
  getSourceSearchContexts,
  getXmlNamespaces,
  buildElementReference,
  compareGroupItems,
  updateConstantValueToMatchType,
} from "../../../src/components/mapper/MappingTableView";
import type { MappingTableItem } from "../../../src/components/mapper/MappingTableView";
import type {
  MappingDescription,
  DataType,
  ValueSupplier,
  MappingAction,
} from "../../../src/mapper/model/model";
import { SchemaKind } from "../../../src/mapper/model/model";

type MockModule<K extends string> = Record<K, Record<string, jest.Mock>>;

function getAttributesMock(): Record<string, jest.Mock> {
  const mod: MockModule<"Attributes"> = jest.requireMock(
    "../../../src/mapper/util/attributes.ts",
  );
  return mod.Attributes;
}

function getMappingActionsMock(): Record<string, jest.Mock> {
  const mod: MockModule<"MappingActions"> = jest.requireMock(
    "../../../src/mapper/util/actions.ts",
  );
  return mod.MappingActions;
}

function getDataTypesMock(): Record<string, jest.Mock> {
  const mod: MockModule<"DataTypes"> = jest.requireMock(
    "../../../src/mapper/util/types.ts",
  );
  return mod.DataTypes;
}

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

describe("MappingTableView utils", () => {
  describe("type guards", () => {
    it("isConstantItem returns true for constant item", () => {
      expect(
        isConstantItem({
          itemType: "constant",
          id: "c1",
          constant: {} as never,
          actions: [],
        }),
      ).toBe(true);
    });
    it("isConstantItem returns false for non-constant", () => {
      expect(isConstantItem({ itemType: "attribute" })).toBe(false);
      expect(isConstantItem(null)).toBe(false);
    });
    it("isConstantItem returns false for undefined and empty object", () => {
      expect(isConstantItem(undefined)).toBe(false);
      expect(isConstantItem({})).toBe(false);
    });

    it("isAttributeItem returns true for attribute item", () => {
      expect(
        isAttributeItem({
          itemType: "attribute",
          id: "a1",
          kind: "header",
          path: [],
          resolvedType: { name: "string" },
          actions: [],
          typeDefinitions: [],
          attribute: {} as never,
        }),
      ).toBe(true);
    });
    it("isAttributeItem returns false for null", () => {
      expect(isAttributeItem(null)).toBe(false);
    });
    it("isHeaderGroup returns true for header-group", () => {
      expect(
        isHeaderGroup({
          itemType: "header-group",
          id: "h1",
          kind: "header",
          children: [],
        }),
      ).toBe(true);
    });
    it("isHeaderGroup returns false for wrong itemType", () => {
      expect(isHeaderGroup({ itemType: "constant" })).toBe(false);
    });
    it("isConstantGroup returns true for constant-group", () => {
      expect(
        isConstantGroup({ itemType: "constant-group", id: "g1", children: [] }),
      ).toBe(true);
    });
    it("isConstantGroup returns false for undefined", () => {
      expect(isConstantGroup(undefined)).toBe(false);
    });
    it("isPropertyGroup returns true for property-group", () => {
      expect(
        isPropertyGroup({
          itemType: "property-group",
          id: "p1",
          kind: "property",
          children: [],
        }),
      ).toBe(true);
    });
    it("isPropertyGroup returns false for empty object", () => {
      expect(isPropertyGroup({})).toBe(false);
    });
    it("isBodyGroup returns true for body-group", () => {
      expect(
        isBodyGroup({
          itemType: "body-group",
          id: "b1",
          kind: "body",
          type: null,
        }),
      ).toBe(true);
    });
    it("isBodyGroup returns false for undefined", () => {
      expect(isBodyGroup(undefined)).toBe(false);
    });
  });

  describe("buildAttributeItemId", () => {
    it("builds id from schemaKind, kind and path", () => {
      expect(
        buildAttributeItemId(SchemaKind.SOURCE, "header", ["a", "b"]),
      ).toBe("source-header-a-b");
    });
    it("builds id with empty path", () => {
      expect(buildAttributeItemId(SchemaKind.SOURCE, "header", [])).toBe(
        "source-header-",
      );
    });
  });

  describe("buildConstantId", () => {
    it("builds constant id with prefix", () => {
      expect(buildConstantId("const-123")).toBe("constant-const-123");
    });
    it("builds constant id with empty string", () => {
      expect(buildConstantId("")).toBe("constant-");
    });
  });

  describe("buildMappingTableItems", () => {
    const emptyMapping: MappingDescription = {
      source: { headers: [], properties: [], body: null },
      target: { headers: [], properties: [], body: null },
      constants: [],
      actions: [],
    };

    it("returns items for empty mapping", () => {
      const items = buildMappingTableItems(emptyMapping, SchemaKind.TARGET);
      expect(items).toHaveLength(3);
      expect(items[0].itemType).toBe("header-group");
      expect(items[1].itemType).toBe("property-group");
      expect(items[2].itemType).toBe("body-group");
    });

    it("includes constant-group for SOURCE schema", () => {
      const mapping: MappingDescription = {
        ...emptyMapping,
        constants: [
          {
            id: "c1",
            name: "c1",
            type: { name: "string" },
            valueSupplier: { kind: "given", value: "x" },
          },
        ],
      };
      const items = buildMappingTableItems(mapping, SchemaKind.SOURCE);
      expect(items[0].itemType).toBe("constant-group");
      expect(items[1].itemType).toBe("header-group");
    });

    it("SOURCE with no constants has constant-group with empty children", () => {
      const mapping: MappingDescription = {
        ...emptyMapping,
        constants: [],
      };
      const items = buildMappingTableItems(mapping, SchemaKind.SOURCE);
      expect(items[0].itemType).toBe("constant-group");
      const constantGroup = items[0] as { children: unknown[] };
      expect(constantGroup.children).toHaveLength(0);
    });

    it("with properties populates property-group", () => {
      const mapping: MappingDescription = {
        ...emptyMapping,
        source: {
          headers: [],
          properties: [
            {
              id: "p1",
              name: "Prop1",
              type: { name: "string" },
              required: false,
            },
          ],
          body: null,
        },
        target: emptyMapping.target,
      };
      const items = buildMappingTableItems(mapping, SchemaKind.SOURCE);
      const propertyGroup = items.find((i) => i.itemType === "property-group");
      expect(propertyGroup).toBeDefined();
      const groupWithChildren = propertyGroup as {
        children: { kind: string }[];
      };
      expect(groupWithChildren.children).toHaveLength(1);
      expect(groupWithChildren.children[0].kind).toBe("property");
    });

    it("with body populates body-group", () => {
      const bodyAttr = {
        id: "b1",
        name: "bodyAttr",
        type: { name: "string" },
      };
      const bodyType = {
        name: "object" as const,
        schema: { id: "obj1", attributes: [bodyAttr] },
      };
      const Attributes = getAttributesMock();
      Attributes.buildAttribute.mockImplementation(
        (
          _id: string,
          _name: string,
          type: { schema?: { attributes?: unknown[] } },
        ) => ({ id: "", name: "", type }),
      );
      Attributes.getChildAttributes.mockImplementation(
        (attr: {
          type?: { name?: string; schema?: { attributes?: unknown[] } };
        }) =>
          attr.type?.name === "object" && attr.type?.schema?.attributes
            ? attr.type.schema.attributes
            : [],
      );
      const mapping: MappingDescription = {
        ...emptyMapping,
        source: {
          headers: [],
          properties: [],
          body: bodyType as DataType,
        },
        target: emptyMapping.target,
      };
      const items = buildMappingTableItems(mapping, SchemaKind.SOURCE);
      const bodyGroup = items.find((i) => i.itemType === "body-group");
      expect(bodyGroup).toBeDefined();
      const groupWithChildren = bodyGroup as { children: { kind: string }[] };
      expect(groupWithChildren.children).toHaveLength(1);
      expect(groupWithChildren.children[0].kind).toBe("body");
      Attributes.buildAttribute.mockImplementation(() => ({}));
      Attributes.getChildAttributes.mockImplementation(() => []);
    });

    it("with header that has child attributes populates children", () => {
      const childAttr = {
        id: "child1",
        name: "child1",
        type: { name: "string" },
      };
      const Attributes = getAttributesMock();
      Attributes.getChildAttributes
        .mockImplementationOnce(() => [childAttr])
        .mockImplementation(() => []);
      const mapping: MappingDescription = {
        ...emptyMapping,
        source: {
          headers: [
            {
              id: "h1",
              name: "H1",
              type: { name: "object" } as DataType,
              required: false,
            },
          ],
          properties: [],
          body: null,
        },
        target: emptyMapping.target,
      };
      const items = buildMappingTableItems(mapping, SchemaKind.SOURCE);
      const headerGroup = items.find((i) => i.itemType === "header-group");
      expect(headerGroup).toBeDefined();
      const groupWithChildren = headerGroup as {
        children: { children?: unknown[] }[];
      };
      expect(groupWithChildren.children).toHaveLength(1);
      expect(groupWithChildren.children[0].children).toHaveLength(1);
      expect(
        (groupWithChildren.children[0].children as { id: string }[])[0].id,
      ).toContain("child1");
    });
  });

  describe("filterMappingTableItems", () => {
    it("filters items by predicate", () => {
      const items = [
        {
          id: "1",
          itemType: "constant",
          constant: { id: "c1" },
          actions: [],
        },
        {
          id: "2",
          itemType: "constant",
          constant: { id: "c2" },
          actions: [],
        },
      ] as unknown as MappingTableItem[];
      const pred = (i: { id: string }) => i.id === "1";
      const filtered = filterMappingTableItems(items, pred);
      expect(filtered).toHaveLength(1);
      expect(filtered[0].id).toBe("1");
    });

    it("recursively filters nested children", () => {
      const minimalAttrItem = {
        id: "keep",
        itemType: "attribute" as const,
        kind: "header" as const,
        path: [],
        resolvedType: { name: "string" },
        actions: [],
        typeDefinitions: [],
        attribute: { id: "a1", name: "A1", type: { name: "string" } },
      };
      const parent = {
        id: "header-group",
        itemType: "header-group" as const,
        kind: "header" as const,
        children: [
          minimalAttrItem,
          {
            ...minimalAttrItem,
            id: "remove",
            attribute: { id: "a2", name: "A2", type: { name: "string" } },
          },
        ],
      };
      const pred = (i: { id: string }) => i.id !== "remove";
      const filtered = filterMappingTableItems(
        [parent] as unknown as MappingTableItem[],
        pred,
      );
      expect(filtered).toHaveLength(1);
      const filteredParent = filtered[0] as { children: { id: string }[] };
      expect(filteredParent.children).toHaveLength(1);
      expect(filteredParent.children[0].id).toBe("keep");
    });
  });

  describe("buildMappingTableItemPredicate", () => {
    const emptyMapping: MappingDescription = {
      source: { headers: [], properties: [], body: null },
      target: { headers: [], properties: [], body: null },
      constants: [],
      actions: [],
    };

    it("returns true for empty search string", () => {
      const pred = buildMappingTableItemPredicate(
        emptyMapping,
        SchemaKind.TARGET,
        "",
      );
      expect(pred({ itemType: "constant" } as never)).toBe(true);
    });

    it("returns true for group items regardless of search", () => {
      const pred = buildMappingTableItemPredicate(
        emptyMapping,
        SchemaKind.TARGET,
        "foo",
      );
      expect(pred({ itemType: "header-group" } as never)).toBe(true);
      expect(pred({ itemType: "property-group" } as never)).toBe(true);
      expect(pred({ itemType: "body-group" } as never)).toBe(true);
      expect(pred({ itemType: "constant-group" } as never)).toBe(true);
    });

    it("returns false when search does not match constant name", () => {
      const items = buildMappingTableItems(
        minimalMappingDescription as MappingDescription,
        SchemaKind.SOURCE,
      );
      const constantGroup = items[0];
      const constantItem =
        "children" in constantGroup ? constantGroup.children![0] : null;
      expect(constantItem).toBeTruthy();
      const pred = buildMappingTableItemPredicate(
        minimalMappingDescription as MappingDescription,
        SchemaKind.SOURCE,
        "nonexistent",
      );
      expect(pred(constantItem!)).toBe(false);
    });

    it("returns true when search matches constant name", () => {
      const items = buildMappingTableItems(
        minimalMappingDescription as MappingDescription,
        SchemaKind.SOURCE,
      );
      const constantGroup = items[0];
      const constantItem =
        "children" in constantGroup ? constantGroup.children![0] : null;
      expect(constantItem).toBeTruthy();
      const pred = buildMappingTableItemPredicate(
        minimalMappingDescription as MappingDescription,
        SchemaKind.SOURCE,
        "Const",
      );
      expect(pred(constantItem!)).toBe(true);
    });

    it("returns true when search matches attribute name (case insensitive)", () => {
      const items = buildMappingTableItems(
        minimalMappingDescription as MappingDescription,
        SchemaKind.TARGET,
      );
      const headerGroup = items[0];
      const attrItem =
        "children" in headerGroup ? headerGroup.children![0] : null;
      expect(attrItem).toBeTruthy();
      const pred = buildMappingTableItemPredicate(
        minimalMappingDescription as MappingDescription,
        SchemaKind.TARGET,
        "header",
      );
      expect(pred(attrItem!)).toBe(true);
    });

    it("returns true when search matches AttributeItem mapping action target", () => {
      const mapping: MappingDescription = {
        ...emptyMapping,
        source: {
          headers: [
            {
              id: "sh1",
              name: "SourceHeader",
              type: { name: "string" },
              required: false,
            },
          ],
          properties: [],
          body: null,
        },
        target: emptyMapping.target,
        actions: [
          {
            target: { type: "attribute", kind: "header", path: ["sh1"] },
            sources: [],
          } as unknown as MappingAction,
        ],
      };
      const MappingActions = getMappingActionsMock();
      MappingActions.findActionsByElementReference.mockReturnValue([
        {
          target: { type: "attribute", kind: "header", path: ["sh1"] },
          sources: [],
        },
      ]);
      MappingActions.resolveReference.mockReturnValue({
        kind: "header",
        path: [{ id: "x", name: "ResolvedAttr" }],
        definitions: [],
      });
      const items = buildMappingTableItems(mapping, SchemaKind.SOURCE);
      const headerGroup = items.find((i) => i.itemType === "header-group");
      const attrItem =
        headerGroup && "children" in headerGroup
          ? headerGroup.children[0]
          : null;
      expect(attrItem).toBeTruthy();
      const pred = buildMappingTableItemPredicate(
        mapping,
        SchemaKind.SOURCE,
        "ResolvedAttr",
      );
      expect(pred(attrItem!)).toBe(true);
      MappingActions.findActionsByElementReference.mockReturnValue([]);
      MappingActions.resolveReference.mockReset();
    });

    it("returns true when search matches ConstantItem with generated value", () => {
      const mapping: MappingDescription = {
        ...emptyMapping,
        constants: [
          {
            id: "gen1",
            name: "GenConst",
            type: { name: "string" },
            valueSupplier: {
              kind: "generated",
              generator: { name: "generateUUID", parameters: [] },
            },
          },
        ],
      };
      const items = buildMappingTableItems(mapping, SchemaKind.SOURCE);
      const constantGroup = items[0];
      const constantItem =
        "children" in constantGroup ? constantGroup.children![0] : null;
      expect(constantItem).toBeTruthy();
      const pred = buildMappingTableItemPredicate(
        mapping,
        SchemaKind.SOURCE,
        "UUID",
      );
      expect(pred(constantItem!)).toBe(true);
    });

    it("returns true when search matches attribute via getTargetSearchContexts", () => {
      const mapping: MappingDescription = {
        ...emptyMapping,
        target: {
          headers: [
            {
              id: "th1",
              name: "TargetHeader",
              type: { name: "string" },
              required: true,
            },
          ],
          properties: [],
          body: null,
        },
      };
      const MappingActions = getMappingActionsMock();
      MappingActions.findActionsByElementReference.mockReturnValue([
        {
          target: { type: "attribute", kind: "header", path: ["th1"] },
          sources: [{ type: "attribute", kind: "header", path: ["sh1"] }],
          transformation: { name: "trim", parameters: ["both"] },
        },
      ]);
      MappingActions.resolveReference.mockReturnValue({
        kind: "header",
        path: [{ id: "x", name: "SourceAttr" }],
        definitions: [],
      });
      const items = buildMappingTableItems(mapping, SchemaKind.TARGET);
      const headerGroup = items.find((i) => i.itemType === "header-group");
      const attrItem =
        headerGroup && "children" in headerGroup
          ? headerGroup.children[0]
          : null;
      expect(attrItem).toBeTruthy();
      const pred = buildMappingTableItemPredicate(
        mapping,
        SchemaKind.TARGET,
        "trim",
      );
      expect(pred(attrItem!)).toBe(true);
      MappingActions.findActionsByElementReference.mockReturnValue([]);
      MappingActions.resolveReference.mockReset();
    });

    it("returns true when search matches mapping action source via getTargetSearchContexts", () => {
      const mapping: MappingDescription = {
        ...emptyMapping,
        target: {
          headers: [
            {
              id: "th1",
              name: "TargetHeader",
              type: { name: "string" },
              required: false,
            },
          ],
          properties: [],
          body: null,
        },
      };
      const MappingActions = getMappingActionsMock();
      MappingActions.findActionsByElementReference.mockReturnValue([
        {
          target: { type: "attribute", kind: "header", path: ["th1"] },
          sources: [{ type: "attribute", kind: "header", path: ["sh1"] }],
          transformation: { name: "trim", parameters: [] },
        },
      ]);
      MappingActions.resolveReference.mockReturnValue({
        kind: "header",
        path: [{ id: "x", name: "SourceAttr" }],
        definitions: [],
      });
      const items = buildMappingTableItems(mapping, SchemaKind.TARGET);
      const headerGroup = items.find((i) => i.itemType === "header-group");
      const attrItem =
        headerGroup && "children" in headerGroup
          ? headerGroup.children[0]
          : null;
      expect(attrItem).toBeTruthy();
      const pred = buildMappingTableItemPredicate(
        mapping,
        SchemaKind.TARGET,
        "SourceAttr",
      );
      expect(pred(attrItem!)).toBe(true);
      MappingActions.findActionsByElementReference.mockReturnValue([]);
      MappingActions.resolveReference.mockReset();
    });

    it("returns false for ConstantItem with TARGET schema (getTargetSearchContexts returns [])", () => {
      const mapping: MappingDescription = {
        ...emptyMapping,
        constants: [
          {
            id: "c1",
            name: "Const",
            type: { name: "string" },
            valueSupplier: { kind: "given", value: "x" },
          },
        ],
      };
      const constantItem = {
        id: buildConstantId("c1"),
        itemType: "constant" as const,
        constant: mapping.constants[0],
        actions: [],
      };
      const pred = buildMappingTableItemPredicate(
        mapping,
        SchemaKind.TARGET,
        "x",
      );
      expect(pred(constantItem)).toBe(false);
    });
  });

  describe("getSourceSearchContexts", () => {
    it("returns empty array for header-group item (default branch)", () => {
      const emptyMapping: MappingDescription = {
        source: { headers: [], properties: [], body: null },
        target: { headers: [], properties: [], body: null },
        constants: [],
        actions: [],
      };
      const mapping: MappingDescription = { ...emptyMapping };
      const item = { itemType: "header-group" as const } as MappingTableItem;
      const result = getSourceSearchContexts(mapping, item);
      expect(result).toEqual([]);
    });
  });

  describe("getXmlNamespaces", () => {
    it("returns empty array for non-array type", () => {
      const result = getXmlNamespaces({ name: "string" }, []);
      expect(result).toEqual([]);
    });

    it("returns empty array when resolveArrayItemType returns no type", () => {
      const DataTypes = getDataTypesMock();
      DataTypes.resolveType.mockImplementationOnce(() => ({
        type: { name: "array", itemType: { name: "string" } },
        definitions: [],
      }));
      DataTypes.resolveArrayItemType.mockImplementationOnce(() => ({
        type: undefined,
        definitions: [],
      }));
      const result = getXmlNamespaces(
        { name: "array", itemType: { name: "string" } },
        [],
      );
      expect(result).toEqual([]);
    });

    it("returns xmlNamespaces from array item type metadata", () => {
      const namespaces = [{ alias: "ns1", uri: "http://example.com/ns1" }];
      const arrayType = {
        name: "array" as const,
        itemType: {
          name: "string" as const,
          metadata: { xmlNamespaces: namespaces },
        },
      };
      const DataTypes = getDataTypesMock();
      DataTypes.resolveType.mockImplementationOnce(() => ({
        type: arrayType,
        definitions: [],
      }));
      DataTypes.resolveArrayItemType.mockImplementationOnce(() => ({
        type: arrayType.itemType,
        definitions: [],
      }));
      const result = getXmlNamespaces(arrayType, []);
      expect(result).toEqual(namespaces);
    });

    it("returns empty array when getTypedValue onFail callback is invoked", () => {
      const arrayType = {
        name: "array" as const,
        itemType: {
          name: "string" as const,
          metadata: { xmlNamespaces: "invalid" },
        },
      };
      const DataTypes = getDataTypesMock();
      DataTypes.resolveType.mockImplementationOnce(() => ({
        type: arrayType,
        definitions: [],
      }));
      DataTypes.resolveArrayItemType.mockImplementationOnce(() => ({
        type: arrayType.itemType,
        definitions: [],
      }));
      const result = getXmlNamespaces(arrayType, []);
      expect(result).toEqual([]);
    });
  });

  describe("updateConstantValueToMatchType", () => {
    it("returns given value for boolean type when value is true", () => {
      const result = updateConstantValueToMatchType(
        { kind: "given", value: "true" },
        { name: "boolean" },
      );
      expect(result).toEqual({ kind: "given", value: "true" });
    });

    it("returns false string for boolean type when value is not true", () => {
      const result = updateConstantValueToMatchType(
        { kind: "given", value: "false" },
        { name: "boolean" },
      );
      expect(result).toEqual({ kind: "given", value: "false" });
    });

    it("returns false string for boolean type when not given supplier", () => {
      const result = updateConstantValueToMatchType(
        { kind: "generated", generator: { name: "x", parameters: [] } },
        { name: "boolean" },
      );
      expect(result).toEqual({ kind: "given", value: "false" });
    });

    it("returns parsed number string for number type when given", () => {
      const result = updateConstantValueToMatchType(
        { kind: "given", value: "42.5" },
        { name: "number" },
      );
      expect(result).toEqual({ kind: "given", value: "42.5" });
    });

    it("returns zero for number type when not given supplier", () => {
      const result = updateConstantValueToMatchType(
        { kind: "generated", generator: { name: "x", parameters: [] } },
        { name: "number" },
      );
      expect(result).toEqual({ kind: "given", value: "0" });
    });

    it("returns valueSupplier unchanged for default type", () => {
      const supplier: ValueSupplier = { kind: "given", value: "hello" };
      const result = updateConstantValueToMatchType(supplier, {
        name: "string",
      });
      expect(result).toBe(supplier);
    });
  });

  describe("buildElementReference", () => {
    it("returns constant reference for ConstantItem", () => {
      const item = {
        itemType: "constant" as const,
        constant: { id: "c1" },
        actions: [],
      } as never;
      expect(buildElementReference(item)).toEqual({
        type: "constant",
        constantId: "c1",
      });
    });

    it("returns attribute reference for AttributeItem", () => {
      const item = {
        itemType: "attribute" as const,
        kind: "header" as const,
        path: [{ id: "a1" }],
        actions: [],
      } as never;
      expect(buildElementReference(item)).toEqual({
        type: "attribute",
        kind: "header",
        path: ["a1"],
      });
    });
  });

  describe("compareGroupItems", () => {
    it("returns positive when i0 after i1 in ascend", () => {
      const i0 = { itemType: "body-group" } as never;
      const i1 = { itemType: "constant-group" } as never;
      expect(compareGroupItems(i0, i1, "ascend")).toBeGreaterThan(0);
    });

    it("returns positive when i0 before i1 in descend", () => {
      const i0 = { itemType: "constant-group" } as never;
      const i1 = { itemType: "body-group" } as never;
      expect(compareGroupItems(i0, i1, "descend")).toBeGreaterThan(0);
    });

    it("orders constant-group, header-group, property-group, body-group", () => {
      const groups = [
        { itemType: "constant-group" },
        { itemType: "header-group" },
        { itemType: "property-group" },
        { itemType: "body-group" },
      ] as never[];
      for (let i = 0; i < groups.length; i++) {
        for (let j = 0; j < groups.length; j++) {
          const result = compareGroupItems(groups[i], groups[j], "ascend");
          expect(Math.sign(result)).toBe(Math.sign(i - j));
        }
      }
    });
  });
});
