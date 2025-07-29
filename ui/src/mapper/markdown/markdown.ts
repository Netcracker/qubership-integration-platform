import { MarkdownEntryOrPrimitive, tsMarkdown } from "ts-markdown";
import { DataTypes } from "../util/types.ts";
import { marked } from "marked";
import { MappingActions } from "../util/actions.ts";
import { Attributes } from "../util/attributes.ts";
import { DESCRIPTION_KEY, EXAMPLES_KEY } from "../model/metadata.ts";
import { MetadataUtil } from "../util/metadata.ts";
import { MessageSchemaUtil } from "../util/schema.ts";
import {
    bindParameterValues,
    TransformationInfo,
    TRANSFORMATIONS,
} from "../model/transformations.ts";
import { GENERATORS } from "../model/generators.ts";

import {
  Attribute,
  AttributeKind,
  AttributeReference,
  Constant,
  ConstantReference,
  MappingDescription,
  MessageSchema,
  Transformation,
  TypeDefinition,
  ValueGenerator,
  ValueSupplier,
} from "../model/model";
import { MappingUtil } from "../util/mapping.ts";

export interface MarkdownMappingExportOptions {
  titleSize: "h1" | "h2" | "h3";
}

export function exportAsMarkdown(
  mapping: MappingDescription,
  options: MarkdownMappingExportOptions,
): string {
  const entries: MarkdownEntryOrPrimitive[] = [
    ...buildMarkdownEntriesForConstants(mapping?.constants, options),
    ...buildMarkdownEntriesForMessageSchema(mapping?.target, mapping, options),
  ];
  return tsMarkdown(entries);
}

function buildMarkdownEntriesForMessageSchema(
  messageSchema: MessageSchema,
  mapping: MappingDescription,
  options: MarkdownMappingExportOptions,
): MarkdownEntryOrPrimitive[] {
  const bodyTypeResolveResult = messageSchema?.body
    ? DataTypes.resolveType(messageSchema.body, [])
    : {
        type: undefined,
        definitions: [],
      };
  const typeResolveResult =
    bodyTypeResolveResult.type?.name === "array"
      ? DataTypes.resolveArrayItemType(
          bodyTypeResolveResult.type,
          bodyTypeResolveResult.definitions,
        )
      : bodyTypeResolveResult;
  const bodyAttributes = [];
  const typeDefinitions = typeResolveResult.definitions;
  if (typeResolveResult.type?.name === "object") {
    bodyAttributes.push(...(typeResolveResult.type.schema?.attributes ?? []));
    typeDefinitions.push(...(typeResolveResult.type.definitions ?? []));
  }
  return [
    ...buildMarkdownEntriesForAttributes(
      messageSchema?.headers,
      "header",
      mapping,
      [],
      options,
    ),
    ...buildMarkdownEntriesForAttributes(
      messageSchema?.properties,
      "property",
      mapping,
      [],
      options,
    ),
    ...buildMarkdownEntriesForAttributes(
      bodyAttributes,
      "body",
      mapping,
      typeDefinitions,
      options,
    ),
  ];
}

function buildMarkdownEntriesForConstants(
  constants: Constant[],
  options: MarkdownMappingExportOptions,
): MarkdownEntryOrPrimitive[] {
  if (!constants || constants.length === 0) {
    return [];
  }
  return [
    { [options.titleSize]: "Constants" },
    {
      table: {
        columns: [
          { name: "Name" },
          { name: "Type" },
          { name: "Value" },
          { name: "Description" },
        ],
        rows: constants.map((constant) => buildConstantRow(constant)),
      },
    },
  ];
}

function buildConstantRow(constant: Constant): string[] {
  return [
    [constant?.name],
    [DataTypes.buildTypeName(constant?.type, [])],
    buildConstantValueEntries(constant?.valueSupplier),
    [String(MetadataUtil.getValue(constant, DESCRIPTION_KEY))],
  ].map((cellContext) => renderTableCellContext(cellContext));
}

function buildConstantValueEntries(
  valueSupplier: ValueSupplier,
): MarkdownEntryOrPrimitive[] {
  switch (valueSupplier?.kind) {
    case "given":
      return [{ codeblock: valueSupplier.value, fenced: true }];
    case "generated":
      return buildMarkdownEntriesForGenerator(valueSupplier.generator);
    default:
      return [""];
  }
}

function buildMarkdownEntriesForAttributes(
  attributes: Attribute[],
  kind: AttributeKind,
  mapping: MappingDescription,
  typeDefinitions: TypeDefinition[],
  options: MarkdownMappingExportOptions,
): MarkdownEntryOrPrimitive[] {
  if (!attributes || attributes.length === 0) {
    return [];
  }
  return [
    { [options.titleSize]: getAttributeTableTitle(kind) },
    {
      table: {
        columns: [
          { name: "Name" },
          { name: "Type" },
          { name: "Optionality" },
          { name: "Description" },
          { name: "Examples" },
          { name: "Sources" },
          { name: "Transformation" },
          { name: "Action description" },
        ],
        rows: attributes
          .map((attribute) =>
            buildMarkdownTableRowsForAttribute(
              attribute,
              [],
              kind,
              mapping,
              typeDefinitions,
            ),
          )
          .reduce((l1, l2) => [...l1, ...l2], []),
      },
    },
  ];
}

function getAttributeTableTitle(kind: AttributeKind): string {
  switch (kind) {
    case "header":
      return "Headers";
    case "property":
      return "Properties";
    case "body":
      return "Body";
    default:
      return "Attributes";
  }
}

function buildMarkdownTableRowsForAttribute(
  attribute: Attribute,
  path: Attribute[],
  kind: AttributeKind,
  mapping: MappingDescription,
  scopeTypeDefinitions: TypeDefinition[],
): MarkdownEntryOrPrimitive[] {
  const typeDefinitions = [
    ...Attributes.extractTypeDefinitions(path),
    ...scopeTypeDefinitions,
  ];
  const typeResolveResult = DataTypes.resolveType(
    attribute.type,
    typeDefinitions,
  );
  const typeName = typeResolveResult.type
    ? DataTypes.buildTypeName(
        typeResolveResult.type,
        typeResolveResult.definitions,
      )
    : "";

  const attributePath = [...path, attribute]; // .filter(a => !a.metadata?.isPartOfCompoundType);
  const reference: AttributeReference = {
    type: "attribute",
    kind,
    path: attributePath.map((a) => a.id),
  };

  const name = buildElementPathString(
    reference,
    mapping.target,
    mapping.constants,
  );
  const optionality = attribute.required ? "required" : "optional";
  const description = MetadataUtil.getValue(
    attribute,
    DESCRIPTION_KEY,
  ) as string;

  const examples = buildExamples(
    MetadataUtil.getValue(attribute, EXAMPLES_KEY),
  );

  const actions = MappingActions.findActionsByElementReference(
    mapping.actions,
    reference,
  );
  const sources = actions
    ?.map((a) => a.sources)
    .reduce((s0, s1) => [...s0, ...s1], []);
  const sourcesDetails = {
    ul: sources.map((source) => {
      const prefix = source.type === "constant" ? "constant" : source.kind;
      return [
        prefix,
        buildElementPathString(source, mapping.source, mapping.constants),
      ].join(".");
    }),
  };

  const transformationDetails = actions
    .map((a) => a.transformation)
    .filter((t) => !!t)
    .map((t) => buildTransformationDetails(t))
    .reduce((l1, l2) => [...l1, ...l2], []);
  const actionDescriptions = actions
    .map((a) => MetadataUtil.getValue(a, DESCRIPTION_KEY) as string)
    .filter((d) => !!d);

  const childAttributes = Attributes.getChildAttributes(
    attribute,
    typeDefinitions,
  );

  const row = [
    [name],
    [typeName],
    [optionality],
    [description],
    examples,
    [sourcesDetails],
    transformationDetails,
    actionDescriptions,
  ].map((cellContext) => renderTableCellContext(cellContext));
  return [
    row,
    ...childAttributes
      .map((a) =>
        buildMarkdownTableRowsForAttribute(
          a,
          attributePath,
          kind,
          mapping,
          scopeTypeDefinitions,
        ),
      )
      .reduce((l1, l2) => [...l1, ...l2], []),
  ];
}

function buildExamples(examples: unknown): MarkdownEntryOrPrimitive[] {
  return Array.isArray(examples)
    ? examples.map((example) => ({
        codeblock: JSON.stringify(example),
        fenced: true,
      }))
    : [""];
}

function buildElementPathString(
  reference: AttributeReference | ConstantReference,
  messageSchema: MessageSchema,
  constants: Constant[],
): string {
  return MappingUtil.isConstantReference(reference)
    ? (constants.find(
        (c) => c.id === reference.constantId,
      )?.name ?? "")
    : MessageSchemaUtil.restorePath(messageSchema, reference)
        ?.map((a) => a.name ?? "")
        .join(".") ?? "";
}

function buildTransformationDetails(
  transformation: Transformation,
): MarkdownEntryOrPrimitive[] {
  const transformationInfo = TRANSFORMATIONS.find(
    (i) => i.name === transformation?.name,
  );
  const title = transformationInfo?.title ?? transformation.name;
  const parametersElements = buildParametersElements(
    transformationInfo,
    transformation?.parameters,
  );
  return [{ p: title }, ...parametersElements];
}

function buildMarkdownEntriesForGenerator(
  generator: ValueGenerator,
): MarkdownEntryOrPrimitive[] {
  const generatorInfo = GENERATORS.find((i) => i.name === generator?.name);
  const title = generatorInfo?.title ?? generator?.name;
  const parametersElements = buildParametersElements(
    generatorInfo,
    generator?.parameters,
  );
  return [{ p: title }, ...parametersElements];
}

function buildParametersElements(
  transformationInfo: TransformationInfo | undefined,
  parameters: string[],
): MarkdownEntryOrPrimitive[] {
  if (transformationInfo && parameters?.length > 1) {
    const parametersInfo = bindParameterValues(
      transformationInfo.parameters,
      parameters,
    );
    const parametersTable = {
      table: {
        columns: [{ name: "Parameter" }, { name: "Value" }],
        rows: parametersInfo
          .filter(([, values]) => values.some((v) => !!v))
          .map(([info, values]) =>
            [
              [info.name],
              values.map((v) => ({ codeblock: v, fenced: true })),
            ].map((cellContext) => renderTableCellContext(cellContext)),
          ),
      },
    };
    return [tsMarkdown([parametersTable])];
  } else {
    return parameters?.map((p) => ({ codeblock: p }));
  }
}

function renderTableCellContext(context: MarkdownEntryOrPrimitive[]): string {
  const markdown = tsMarkdown(context);
  return (marked.parse(markdown) as string).trim().replace(/\n/g, "");
}
