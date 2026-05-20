import { LocationRange } from "pegjs";

export type NodeType = "operation" | "call" | "reference" | "literal";
export type OperationKind =
  | "or"
  | "and"
  | "equals"
  | "not equals"
  | "ge"
  | "le"
  | "greater"
  | "less"
  | "add"
  | "sub"
  | "multiply"
  | "divide"
  | "modulo"
  | "unary plus"
  | "unary minus"
  | "negate";
export type LiteralKind = "null" | "boolean" | "number" | "string";
export type ReferenceKind = "constant" | "header" | "body" | "property";

export interface ExpressionNode {
  readonly type: NodeType;
}

export interface OperationNode extends ExpressionNode {
  readonly type: "operation";
  kind: OperationKind;
  arguments: ExpressionNode[];
}

export interface FunctionCallNode extends ExpressionNode {
  readonly type: "call";
  name: string;
  arguments: ExpressionNode[];
}

export interface LiteralNode extends ExpressionNode {
  readonly type: "literal";
  kind: LiteralKind;
  value?: string;
}

export interface ReferenceNode extends ExpressionNode {
  readonly type: "reference";
  readonly kind: ReferenceKind;
  location: LocationRange;
}

export interface AttributeReferenceNode extends ReferenceNode {
  readonly kind: "header" | "body" | "property";
  path: string[];
}

export interface ConstantReferenceNode extends ReferenceNode {
  readonly kind: "constant";
  name: string;
}
