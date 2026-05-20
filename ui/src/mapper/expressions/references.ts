import { ExpressionNode, OperationNode, ReferenceNode } from "./model.ts";
import { Constant } from "../model/model.ts";
import { AttributeDetail } from "../util/schema.ts";

export type ReferenceProcessCallback = (node: ReferenceNode) => void;

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
  const charactersToEscape = " .\t\r\n\\+-*!><,=%()|&/";
  return [...value]
    .map((i) => (charactersToEscape.indexOf(i) >= 0 ? `\\${i}` : i))
    .join("");
}

export function buildConstantReferenceText(constant: Constant): string {
  return `constant.${escape(constant.name)}`;
}

export function buildAttributeReferenceText(detail: AttributeDetail): string {
  return [detail.kind, ...detail.path.map((a) => a.name)]
    .map((i) => escape(i))
    .join(".");
}
