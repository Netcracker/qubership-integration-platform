export type DataType =
  | NullType
  | StringType
  | IntegerType
  | BooleanType
  | ArrayType
  | ObjectType
  | ReferenceType
  | AllOfType
  | AnyOfType
  | OneOfType;

export interface Metadata {
  [key: string]: unknown;
}

export interface MetadataAware {
  metadata?: Metadata;
}

export interface TypeDefinition {
  id: string;
  name: string;
  type: DataType;
}

export interface TypeDefinitionsAware {
  definitions?: TypeDefinition[];
}

export interface TypeBase extends MetadataAware {
  readonly name: string;
}

export interface NullType extends TypeBase {
  readonly name: "null";
}

export interface StringType extends TypeBase {
  readonly name: "string";
}

export interface IntegerType extends TypeBase {
  readonly name: "number";
}

export interface BooleanType extends TypeBase {
  readonly name: "boolean";
}

export interface ArrayType extends TypeBase, TypeDefinitionsAware {
  readonly name: "array";
  itemType: DataType;
}

export interface ObjectType extends TypeBase, TypeDefinitionsAware {
  readonly name: "object";
  schema: ObjectSchema;
}

export interface ReferenceType extends TypeBase, TypeDefinitionsAware {
  readonly name: "reference";
  definitionId: string;
}

export interface CompoundType extends TypeBase, TypeDefinitionsAware {
  types: DataType[];
}

export interface AllOfType extends CompoundType {
  readonly name: "allOf";
}

export interface AnyOfType extends CompoundType {
  readonly name: "anyOf";
}

export interface OneOfType extends CompoundType {
  readonly name: "oneOf";
}

export interface Element extends MetadataAware {
  id: string;
  name: string;
  type: DataType;
}

export interface Attribute extends Element {
  defaultValue?: string;
  required?: boolean;
}

export interface ValueSupplierBase extends MetadataAware {
  readonly kind: string;
}

export interface GivenValue extends ValueSupplierBase {
  readonly kind: "given";
  value: string;
}

export interface ValueGenerator extends MetadataAware {
  name: string;
  parameters: string[];
}

export interface GeneratedValue extends ValueSupplierBase {
  readonly kind: "generated";
  generator: ValueGenerator;
}

export type ValueSupplier = GivenValue | GeneratedValue;

export interface Constant extends Element {
  valueSupplier: ValueSupplier;
}

export interface ObjectSchema extends MetadataAware {
  id: string;
  attributes: Attribute[];
}

export interface MessageSchema extends MetadataAware {
  headers: Attribute[];
  properties: Attribute[];
  body: DataType | null;
}

export interface Transformation extends MetadataAware {
  name: string;
  parameters: string[];
}

export type ElementType = "constant" | "attribute";

export interface ElementReference extends MetadataAware {
  readonly type: ElementType;
}

export interface ConstantReference extends ElementReference {
  readonly type: "constant";
  constantId: string;
}

export type AttributeKind = "header" | "property" | "body";

export interface AttributeReference extends ElementReference {
  kind: AttributeKind;
  path: string[];
}

export interface MappingAction extends MetadataAware {
  id: string;
  sources: (ConstantReference | AttributeReference)[];
  target: AttributeReference;
  transformation?: Transformation;
}

export interface MappingDescription extends MetadataAware {
  source: MessageSchema;
  target: MessageSchema;
  constants: Constant[];
  actions: MappingAction[];
}

export enum SchemaKind {
  SOURCE = "source",
  TARGET = "target",
}
