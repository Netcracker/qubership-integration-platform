import {
  Attribute,
  Constant,
  ConstantReference,
  AttributeReference,
  MappingAction,
  MappingDescription,
  MessageSchema,
  Transformation,
  MetadataAware,
} from "../model/model.ts";
import { isParseError, parse } from "./parser.ts";
import { MessageSchemaUtil } from "../util/schema.ts";
import {
  ConstantReference as ConstantReferenceText,
  AttributeReference as AttributeReferenceText,
  Transformation as TransformationText,
  MappingTextProcessError,
  MappingUpdateException,
} from "./model.ts";
import { DataTypes } from "../util/types.ts";
import { MappingUtil } from "../util/mapping.ts";

export interface MappingUpdateResult {
  mapping: MappingDescription;
  errors: MappingTextProcessError[];
}

export class MappingActions {
  public static toString(mapping: MappingDescription): string {
    return (mapping?.actions ?? [])
      .map((action) => this.buildActionText(action, mapping))
      .join("");
  }

  public static updateFromString(
    text: string,
    mapping: MappingDescription,
  ): MappingUpdateResult {
    const errors: MappingTextProcessError[] = [];
    const parseResult = parse(text);
    errors.push(...parseResult.errors);
    const mappingDescription = parseResult.actions.reduce(
      (result, actionDescription) => {
        try {
          const [res, sources] = actionDescription.sources.reduce(
            ([r, ids], source) => {
              const [r1, id] = this.resolveSource(source, r);
              return [r1, [...ids, id]];
            },
            [result, [] as (AttributeReference | ConstantReference)[]],
          );
          const [res1, target] = this.resolveTarget(
            actionDescription.target,
            res,
          );
          const action = {
            id: MappingUtil.generateUUID(),
            sources,
            target,
            transformation: this.buildTransformation(
              actionDescription.transformation,
            ),
          };
          return { ...res1, actions: [...res1.actions, action] };
        } catch (exception) {
          if (isParseError(exception)) {
            errors.push({
              location: exception.location,
              message: exception.message,
            });
            return result;
          } else {
            throw exception;
          }
        }
      },
      {
        ...this.removeGeneratedAttributesFromMapping(
          mapping ?? MappingUtil.emptyMapping(),
        ),
        actions: [] as MappingAction[],
      },
    );
    return { mapping: mappingDescription, errors };
  }

  private static removeGeneratedAttributesFromMapping(
    mapping: MappingDescription,
  ): MappingDescription {
    return {
      ...mapping,
      source: this.removeGeneratedAttributesFromMessageSchema(mapping.source),
      target: this.removeGeneratedAttributesFromMessageSchema(mapping.target),
      constants: this.removeGeneratedAttributes(mapping.constants),
    };
  }

  private static removeGeneratedAttributesFromMessageSchema(
    schema: MessageSchema,
  ): MessageSchema {
    return {
      ...schema,
      properties: this.removeGeneratedAttributes(schema.properties),
      headers: this.removeGeneratedAttributes(schema.headers),
    };
  }

  private static removeGeneratedAttributes<T extends MetadataAware>(
    attributes: T[],
  ): T[] {
    return attributes.filter(
      (attribute) => !this.isGeneratedAttribute(attribute),
    );
  }

  private static isGeneratedAttribute<T extends MetadataAware>(
    obj: T,
  ): boolean {
    return !!obj.metadata?.location;
  }

  private static buildTransformation(
    transformation: TransformationText,
  ): Transformation {
    return (
      transformation && {
        name: transformation.name,
        parameters: transformation.parameters,
        metadata: { location: transformation.location },
      }
    );
  }

  private static resolveSource(
    source: ConstantReferenceText | AttributeReferenceText,
    mapping: MappingDescription,
  ): [MappingDescription, ConstantReference | AttributeReference] {
    if (source.type === "constant") {
      return this.resolveConstant(source, mapping);
    } else {
      const [schema, reference] = this.resolveAttributeReference(
        source,
        mapping.source,
      );
      return [{ ...mapping, source: schema }, reference];
    }
  }

  private static resolveTarget(
    target: AttributeReferenceText,
    mapping: MappingDescription,
  ): [MappingDescription, AttributeReference] {
    const [schema, reference] = this.resolveAttributeReference(
      target,
      mapping.target,
    );
    return [{ ...mapping, target: schema }, reference];
  }

  private static resolveConstant(
    constantReference: ConstantReferenceText,
    mapping: MappingDescription,
  ): [MappingDescription, ConstantReference] {
    const constant = MappingUtil.findConstantByName(
      mapping,
      constantReference.name,
    );
    if (constant) {
      const ref: ConstantReference = {
        type: "constant",
        constantId: constant.id,
        metadata: { location: constantReference.location },
      };
      return [mapping, ref];
    }
    const newConstant: Constant = {
      id: MappingUtil.generateUUID(),
      name: constantReference.name,
      valueSupplier: { kind: "given", value: constantReference.name },
      type: DataTypes.stringType(),
      metadata: { location: constantReference.location },
    };
    const reference: ConstantReference = {
      type: "constant",
      constantId: newConstant.id,
      metadata: { location: constantReference.location },
    };
    return [
      { ...mapping, constants: [...mapping.constants, newConstant] },
      reference,
    ];
  }

  private static resolveAttributeReference(
    reference: AttributeReferenceText,
    schema: MessageSchema,
  ): [MessageSchema, AttributeReference] {
    const searchResult = MessageSchemaUtil.findAttributeByPath(
      schema,
      reference.kind,
      reference.path,
    );
    if (searchResult) {
      const ref: AttributeReference = {
        type: "attribute",
        kind: searchResult.kind,
        path: searchResult.path.map((attribute) => attribute.id),
        metadata: { location: reference.location },
      };
      return [schema, ref];
    }
    if (reference.kind === "body") {
      throw new MappingUpdateException(
        `Failed to resolve body attribute by path: ${this.escapePath(reference.path)}`,
        reference.location,
      );
    }
    if (reference.path.length > 1) {
      throw new MappingUpdateException(
        `Failed to resolve ${reference.kind} by path: ${this.escapePath(reference.path)}`,
        reference.location,
      );
    }
    const newAttribute: Attribute = {
      id: MappingUtil.generateUUID(),
      name: reference.path[0],
      type: DataTypes.stringType(),
      metadata: { location: reference.location },
    };
    const key = MessageSchemaUtil.getMessageSchemaKey(reference.kind)!;
    const attributes = MessageSchemaUtil.getMessageSchemaAttributes(
      schema,
      reference.kind,
    );
    const attributeReference: AttributeReference = {
      type: "attribute",
      kind: reference.kind,
      path: [newAttribute.id],
      metadata: { location: reference.location },
    };
    return [
      { ...schema, [key]: [...attributes, newAttribute] },
      attributeReference,
    ];
  }

  private static buildActionText(
    action: MappingAction,
    mapping: MappingDescription,
  ): string {
    const sourcesText = action.sources
      .map((source) => this.buildSourceText(source, mapping.source, mapping))
      .join(" ");
    const targetText = this.buildAttributeText(action.target, mapping.target);
    let result = `${sourcesText} -> ${targetText}`;
    if (action.transformation) {
      result = result.concat(
        " : ",
        this.buildTransformationText(action.transformation),
      );
    }
    return result.concat("\n");
  }

  private static buildSourceText(
    source: ConstantReference | AttributeReference,
    messageSchema: MessageSchema,
    mapping: MappingDescription,
  ) {
    return source.type === "constant"
      ? `constant.${this.escapeValue(
          MappingUtil.findConstantById(
            mapping,
            (source as ConstantReference).constantId,
          )?.name ?? "",
          " \t\n\r\\",
        )}`
      : this.buildAttributeText(source, messageSchema);
  }

  private static buildAttributeText(
    reference: AttributeReference,
    schema: MessageSchema,
  ): string {
    const result = MessageSchemaUtil.restorePath(schema, reference);
    if (!result) {
      throw new Error(`Element not found: ${JSON.stringify(reference)}`);
    }
    return this.escapePath([
      reference.kind,
      ...result.map((i) => i.name ?? ""),
    ]);
  }

  public static escapePath(path: string[]): string {
    return path.map((i) => this.escapeValue(i, " .\t\n\r\\")).join(".");
  }

  public static buildTransformationText(
    transformation: Transformation,
  ): string {
    return [transformation.name, ...transformation.parameters]
      .map((i) => this.escapeValue(i, " \t\n\r\\"))
      .join(" ");
  }

  public static escapeValue(text: string, charactersToEscape: string): string {
    return text?.length ? this.escape(text, charactersToEscape) : "\\_";
  }

  public static escape(text: string, charactersToEscape: string): string {
    return [...text]
      .map((i) => (charactersToEscape.indexOf(i) >= 0 ? `\\${i}` : i))
      .join("");
  }
}
