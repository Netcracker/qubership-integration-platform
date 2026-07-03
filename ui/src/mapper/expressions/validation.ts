import { AttributeDetail } from "../util/schema.ts";
import { Constant, MessageSchema } from "../model/model.ts";
import { parse } from "./parser.ts";
import { LocationRange } from "pegjs";
import {
  AttributeReferenceNode,
  ConstantReferenceNode,
  ReferenceNode,
} from "./model.ts";
import { MappingActions } from "../actions-text/util.ts";
import { getExpressionPathNames, processReferences } from "./references.ts";
import { isParseError } from "../actions-text/parser.ts";

export type TransformationValidationCallback = (
  location: LocationRange,
  message: string,
) => void;

function pathsMatch(
  attribute: AttributeDetail,
  expressionPath: string[],
  sourceSchema?: MessageSchema,
): boolean {
  const expected = getExpressionPathNames(
    attribute.kind,
    attribute.path,
    sourceSchema,
  );
  const normalized = expressionPath.map((s) => (s === "_" ? "" : s));
  return (
    expected.length === normalized.length &&
    expected.every((name, i) => name === normalized[i])
  );
}

export function referenceIsValid(
  node: ReferenceNode,
  attributes: AttributeDetail[],
  constants: Constant[],
  sourceSchema?: MessageSchema,
): boolean {
  return node.kind === "constant"
    ? constants.some(
        (constant) => constant.name === (node as ConstantReferenceNode).name,
      )
    : attributes.some(
        (attribute) =>
          attribute.kind === node.kind &&
          pathsMatch(
            attribute,
            (node as AttributeReferenceNode).path,
            sourceSchema,
          ),
      );
}

export function validateReference(
  node: ReferenceNode,
  attributes: AttributeDetail[],
  constants: Constant[],
  callback: TransformationValidationCallback,
  sourceSchema?: MessageSchema,
) {
  if (!referenceIsValid(node, attributes, constants, sourceSchema)) {
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
  sourceSchema?: MessageSchema,
): void {
  try {
    const expression = parse(text);
    processReferences(expression, (node) =>
      validateReference(node, attributes, constants, callback, sourceSchema),
    );
  } catch (exception) {
    if (isParseError(exception)) {
      callback(exception.location, exception.message);
    }
  }
}
