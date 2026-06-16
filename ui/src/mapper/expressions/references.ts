import { ExpressionNode, OperationNode, ReferenceNode } from "./model.ts";
import {
  Attribute,
  AttributeKind,
  Constant,
  MessageSchema,
} from "../model/model.ts";
import { AttributeDetail, isBodyRootArray } from "../util/schema.ts";
import { MappingActions } from "../actions-text/util.ts";

export type ReferenceProcessCallback = (node: ReferenceNode) => void;

const EXPRESSION_PATH_ESCAPE_CHARS = " .\t\r\n\\+-*!><,=%()|&/";

export function processReferences(
  expression: ExpressionNode,
  callback: ReferenceProcessCallback,
): void {
  switch (expression.type) {
    case "operation":
    case "call":
      return (expression as OperationNode).arguments.forEach((arg) =>
        processReferences(arg, callback),
      );
    case "reference":
      callback(expression as ReferenceNode);
  }
}

export function escape(value: string): string {
  return [...value]
    .map((i) => (EXPRESSION_PATH_ESCAPE_CHARS.indexOf(i) >= 0 ? `\\${i}` : i))
    .join("");
}

export function buildConstantReferenceText(constant: Constant): string {
  return `constant.${escape(constant.name)}`;
}

export function getExpressionPathNames(
  kind: AttributeKind,
  path: Attribute[],
  sourceSchema?: MessageSchema,
): string[] {
  const segments = path.map((attribute) => attribute.name ?? "");
  if (kind === "body" && isBodyRootArray(sourceSchema)) {
    return ["", ...segments];
  }
  return segments;
}

export function buildAttributeReferenceText(
  detail: AttributeDetail,
  sourceSchema?: MessageSchema,
): string {
  return [detail.kind, ...getExpressionPathNames(detail.kind, detail.path, sourceSchema)]
    .map((segment) =>
      MappingActions.escapeValue(segment, EXPRESSION_PATH_ESCAPE_CHARS),
    )
    .join(".");
}
