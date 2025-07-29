import {
  AttributeReference,
  Constant,
  ConstantReference,
  MappingAction,
  MappingDescription,
  MessageSchema,
} from "../model/model.ts";
import { AttributeDetail, MessageSchemaUtil } from "./schema.ts";
import { DataTypes } from "./types.ts";
import { MappingUtil } from "./mapping.ts";

export class MappingActions {
  private static KEY_TOKEN_SEPARATOR = "-";

  public static buildPathKey(path: string[]): string {
    return path?.join(this.KEY_TOKEN_SEPARATOR) ?? null;
  }

  public static findActionsByElementReference(
    actions: MappingAction[],
    reference: ConstantReference | AttributeReference,
  ): MappingAction[] {
    return actions.filter(
      (action) =>
        this.referencesAreEqual(action.target, reference) ||
        action.sources.some((ref) => this.referencesAreEqual(ref, reference)),
    );
  }

  public static referencesAreEqual(
    one: ConstantReference | AttributeReference,
    other: ConstantReference | AttributeReference,
  ) {
    return (
      one?.type === other?.type &&
      (!one ||
        (one.type === "constant" &&
          (one as ConstantReference).constantId ===
            (other as ConstantReference).constantId) ||
        (one.type === "attribute" &&
          one.path.length === (other as AttributeReference).path.length &&
          one.path.every(
            (value, index) =>
              value === (other as AttributeReference).path[index],
          )))
    );
  }

  public static getSourcesDetail(
    action: MappingAction,
    mapping: MappingDescription,
  ): (AttributeDetail | Constant | undefined)[] {
    return action.sources.map((ref) =>
      this.resolveReference(ref, false, mapping),
    );
  }

  public static getTargetDetail(
    action: MappingAction,
    mapping: MappingDescription,
  ): AttributeDetail {
    return this.resolveAttributeReference(action.target, mapping.target);
  }

  public static resolveReference(
    reference: AttributeReference | ConstantReference,
    isTarget: boolean,
    mapping: MappingDescription,
  ): AttributeDetail | Constant | undefined {
    return reference.type === "attribute"
      ? this.resolveAttributeReference(
          reference,
          isTarget ? mapping.target : mapping.source,
        )
      : MappingUtil.findConstantById(
          mapping,
          (reference as ConstantReference).constantId,
        );
  }

  public static resolveAttributeReference(
    reference: AttributeReference,
    messageSchema: MessageSchema,
  ): AttributeDetail {
    return {
      kind: reference.kind,
      path: MessageSchemaUtil.restorePath(messageSchema, reference) ?? [],
      definitions:
        reference.kind === "body" && messageSchema.body
          ? DataTypes.getTypeDefinitions(messageSchema.body)
          : [],
    };
  }

  public static getAttributesDetail(
    references: (ConstantReference | AttributeReference)[],
    schema: MessageSchema,
  ): AttributeDetail[] {
    return references
      .filter((ref) => ref.type === "attribute")
      .map((ref) =>
        this.resolveAttributeReference(ref as AttributeReference, schema),
      );
  }

  public static getConstants(
    references: (ConstantReference | AttributeReference)[],
    mapping: MappingDescription,
  ): Constant[] {
    return references
      .filter(ref => MappingUtil.isConstantReference(ref))
      .map((ref) =>
        MappingUtil.findConstantById(
          mapping,
          ref.constantId,
        ),
      )
      .filter(constant => !!constant);
  }
}
