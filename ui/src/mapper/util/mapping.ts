import { v4 as uuidv4 } from "uuid";
import {
  AttributeReference,
  Constant,
  ConstantReference,
  ElementReference,
  MappingAction,
  MappingDescription,
  MessageSchema,
  ObjectSchema,
  Transformation,
} from "../model/model";
import { MessageSchemaUtil } from "./schema.ts";

export type Predicate<T> = (value: T) => boolean;

export class MappingUtil {
  public static generateUUID(): string {
    return (uuidv4 as () => string)().replace(/-/g, "");
  }

  public static emptyMapping(): MappingDescription {
    return {
      source: this.emptyMessageSchema(),
      target: this.emptyMessageSchema(),
      constants: [],
      actions: [],
    };
  }

  public static emptyMessageSchema(): MessageSchema {
    return { headers: [], properties: [], body: null };
  }

  public static emptyObjectSchema(): ObjectSchema {
    return { id: MappingUtil.generateUUID(), attributes: [] };
  }

  public static isEmpty(messageSchema: MessageSchema) {
    return (
      !messageSchema?.headers?.length &&
      !messageSchema?.properties?.length &&
      !messageSchema?.body
    );
  }

  public static objectIsEmpty(obj: object): boolean {
    for (const prop in obj) {
      if (Object.prototype.hasOwnProperty.call(obj, prop)) {
        return false;
      }
    }
    return true;
  }

  public static removeDanglingActions(
    mapping: MappingDescription,
  ): MappingDescription {
    return {
      ...mapping,
      actions: mapping.actions
        .filter((action) => this.targetExists(mapping, action.target))
        .map((action) => ({
          ...action,
          sources: action.sources.filter((source) =>
            this.sourceExists(mapping, source),
          ),
        }))
        .filter((action) => action.sources.length),
    };
  }

  public static sourceExists(
    mapping: MappingDescription,
    reference: ConstantReference | AttributeReference,
  ): boolean {
    return MappingUtil.isConstantReference(reference)
      ? this.constantExists(
          mapping,
          (constant) => constant.id === reference.constantId,
        )
      : MessageSchemaUtil.attributeExists(mapping.source, reference);
  }

  public static constantExists(
    mapping: MappingDescription,
    predicate: Predicate<Constant>,
  ): boolean {
    return mapping.constants.some((constant) => predicate(constant));
  }

  public static targetExists(
    mapping: MappingDescription,
    reference: AttributeReference,
  ): boolean {
    return MessageSchemaUtil.attributeExists(mapping.target, reference);
  }

  public static findConstant(
    mapping: MappingDescription,
    predicate: Predicate<Constant>,
  ): Constant | undefined {
    return mapping?.constants?.find((constant) => predicate(constant));
  }

  public static findConstantById(
    mapping: MappingDescription,
    id: string,
  ): Constant | undefined {
    return this.findConstant(mapping, (constant) => constant.id === id);
  }

  public static findConstantByName(
    mapping: MappingDescription,
    name: string,
  ): Constant | undefined {
    return this.findConstant(mapping, (constant) => constant.name === name);
  }

  public static updateConstant(
    mapping: MappingDescription,
    constantId: string,
    modifyFn: (constant: Constant) => Constant,
  ): MappingDescription {
    return {
      ...mapping,
      constants: mapping.constants.map((constant) =>
        constant.id === constantId ? modifyFn(constant) : constant,
      ),
    };
  }

  public static updateAction(
    mapping: MappingDescription,
    actionId: string,
    modifyFn: (action: MappingAction) => MappingAction,
  ): MappingDescription {
    return {
      ...mapping,
      actions: mapping.actions.map((action) =>
        action.id === actionId ? modifyFn(action) : action,
      ),
    };
  }

  public static updateTransformation(
    mapping: MappingDescription,
    actionId: string,
    transformation: Transformation,
  ): MappingDescription {
    return this.updateAction(mapping, actionId, (action) => ({
      ...action,
      transformation,
    }));
  }

  public static isConstantReference(
    reference: ElementReference,
  ): reference is ConstantReference {
    return reference.type === "constant";
  }

  public static isObjConstantReference(obj: unknown): obj is ConstantReference {
    return (
      typeof obj === "object" &&
      obj !== null &&
      "type" in obj &&
      "constantId" in obj &&
      obj.type === "constant" &&
      typeof obj.constantId === "string"
    );
  }

  public static isAttributeReference(obj: unknown): obj is AttributeReference {
    return (
      typeof obj === "object" &&
      obj !== null &&
      "type" in obj &&
      "kind" in obj &&
      "path" in obj &&
      obj.type === "attribute" &&
      (["header", "property", "body"] as unknown[]).includes(obj.kind) &&
      Array.isArray(obj.path)
    );
  }
}
