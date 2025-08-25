import { Constant } from "../model/model.ts";
import { isAttributeDetail } from "../util/schema.ts";
import { VerificationError, Verifier } from "./model.ts";
import { validateExpression } from "../expressions/validation.ts";
import { getSources, TransformationParameterDetail } from "./base.ts";
import { parse as parseKeyValuePairs } from "../expressions/key-value-text-parser.ts";

export class DictionaryEntryVerifier extends Verifier<TransformationParameterDetail> {
  constructor() {
    super();
  }

  verify(entity: TransformationParameterDetail): VerificationError[] {
    try {
      parseKeyValuePairs(entity.parameterValue + ";");
      return [];
    } catch (exception) {
      return [{ message: exception.message as string }];
    }
  }
}

export class TransformationExpressionVerifier extends Verifier<TransformationParameterDetail> {
  constructor() {
    super();
  }

  verify(entity: TransformationParameterDetail): VerificationError[] {
    const sources = getSources(entity);
    const attributes = sources.filter((source) => isAttributeDetail(source));
    const constants = sources.filter(
      (source) => !isAttributeDetail(source),
    ) as Constant[];
    const errors: VerificationError[] = [];
    validateExpression(
      entity.parameterValue,
      attributes,
      constants,
      (location, message) => errors.push({ message }),
    );
    return errors;
  }
}

export class RegexVerifier extends Verifier<string> {
  constructor() {
    super();
  }

  verify(entity: string): VerificationError[] {
    try {
      new RegExp(entity);
      return [];
    } catch (e) {
      return [{ message: e.message as string }];
    }
  }
}

export function isRegex(): Verifier<string> {
  return new RegexVerifier();
}

export class ReplacementStringVerifier extends Verifier<string> {
  constructor() {
    super();
  }

  verify(entity: string): VerificationError[] {
    return /^(\\.|\$\d+|[^\\$])*$/.test(entity)
      ? []
      : [{ message: "Malformed replacement string" }];
  }
}

export function isReplacementString(): Verifier<string> {
  return new ReplacementStringVerifier();
}
