import { AttributeDetail } from "../util/schema.ts";
import { Constant } from "../model/model.ts";
import { parse } from "./parser.ts";
import { LocationRange } from "pegjs";
import {
  AttributeReferenceNode,
  ConstantReferenceNode,
  ReferenceNode,
} from "./model.ts";
import { MappingActions } from "../actions-text/util.ts";
import { processReferences } from "./references.ts";
import { isParseError } from "../actions-text/parser.ts";

export type TransformationValidationCallback = (
  location: LocationRange,
  message: string,
) => void;

export function referenceIsValid(
  node: ReferenceNode,
  attributes: AttributeDetail[],
  constants: Constant[],
): boolean {
  return node.kind === "constant"
    ? constants.some(
        (constant) => constant.name === (node as ConstantReferenceNode).name,
      )
    : attributes.some(
        (attribute) =>
          attribute.kind === node.kind &&
          attribute.path.length ===
            (node as AttributeReferenceNode).path.length &&
          attribute.path.every(
            (a, i) => a.name === (node as AttributeReferenceNode).path[i],
          ),
      );
}

export function validateReference(
  node: ReferenceNode,
  attributes: AttributeDetail[],
  constants: Constant[],
  callback: TransformationValidationCallback,
) {
  if (!referenceIsValid(node, attributes, constants)) {
    const message = `Unknown ${node.kind === "body" ? "attribute" : node.kind}: ${MappingActions.escapePath(
      node.kind === "constant"
        ? [(node as ConstantReferenceNode).name]
        : (node as AttributeReferenceNode).path,
    )}`;
    callback(node.location, message);
  }
}

export function validateExpression(
  text: string,
  attributes: AttributeDetail[],
  constants: Constant[],
  callback: TransformationValidationCallback,
): void {
  try {
    const expression = parse(text);
    processReferences(expression, (node) =>
      validateReference(node, attributes, constants, callback),
    );
  } catch (exception) {
    if (isParseError(exception)) {
      callback(exception.location, exception.message);
    }
  }
}
