import { MappingAction, MappingDescription } from "../model/model.ts";
import {
  among,
  anyMatch,
  count,
  equalTo,
  exists,
  fail,
  filter,
  forEach,
  greaterThan,
  map,
  not,
  switchOf,
  VerificationError,
} from "./model.ts";
import {
  dataType,
  hasName,
  finalType,
  MappingActionDetail,
  parameters,
  primitiveType,
  sources,
  target,
  transformation,
  transformationParameters,
  compoundType,
  itemType,
} from "./base.ts";
import {
  DictionaryEntryVerifier,
  isRegex,
  isReplacementString,
  TransformationExpressionVerifier,
} from "./transformations.ts";
import { TRANSFORMATIONS } from "../model/transformations.ts";

const UNSUPPORTED_CONNECTION_ERROR_MESSAGE =
  "Given connection pair is not supported or requires transformation";

const MAPPING_ACTION_VERIFIER = switchOf([
  {
    case: sources(
      anyMatch(
        dataType(
          hasName(equalTo("array")).and(itemType(hasName(equalTo("array")))),
        ),
      ),
    ).or(
      target(
        dataType(
          hasName(equalTo("array")).and(itemType(hasName(equalTo("array")))),
        ),
      ),
    ),
    verify: fail().withMessage(UNSUPPORTED_CONNECTION_ERROR_MESSAGE),
  },
  {
    case: transformation(not(exists())),
    verify: sources(count(equalTo(1)))
      .or(target(dataType(hasName(equalTo("array")))))
      .withMessage(
        "Transformation is mandatory for multiple fields aggregation.",
      )
      .and(
        not(
          sources(
            anyMatch(
              dataType(finalType(not(primitiveType()).and(compoundType()))),
            ),
          ),
        ).withMessage(UNSUPPORTED_CONNECTION_ERROR_MESSAGE),
      )
      .and(
        not(
          sources(anyMatch(dataType(finalType(primitiveType())))).and(
            target(dataType(finalType(not(primitiveType())))),
          ),
        ).withMessage(UNSUPPORTED_CONNECTION_ERROR_MESSAGE),
      )
      .and(
        not(
          sources(anyMatch(dataType(finalType(not(primitiveType()))))).and(
            target(dataType(finalType(primitiveType()))),
          ),
        ).withMessage(UNSUPPORTED_CONNECTION_ERROR_MESSAGE),
      ),
  },
  {
    case: transformation(hasName(equalTo("formatDateTime"))),
    verify: sources(
      count(equalTo(1)).withMessage(
        "Transformation requires exactly one input.",
      ),
    )
      .and(
        sources(forEach(dataType(hasName(equalTo("string"))))).withMessage(
          "Wrong source field type.",
        ),
      )
      .and(
        target(dataType(hasName(equalTo("string")))).withMessage(
          "Wrong target field type.",
        ),
      )
      .and(
        transformation(
          parameters(
            count(equalTo(8)).withMessage(
              "Wrong transformation parameters count. Should be 8 parameters.",
            ),
          ),
        ),
      ),
  },
  {
    case: transformation(hasName(equalTo("expression"))),
    verify: transformation(
      parameters(count(equalTo(1))).withMessage(
        "Transformation requires exactly one parameter.",
      ),
    ).and(
      transformationParameters(forEach(new TransformationExpressionVerifier())),
    ),
  },
  {
    case: transformation(hasName(equalTo("defaultValue"))),
    verify: sources(
      count(equalTo(1)).withMessage(
        "Transformation requires exactly one input",
      ),
    ).and(
      transformation(
        parameters(count(equalTo(1))).withMessage(
          "Transformation requires exactly one parameter.",
        ),
      ),
    ),
  },
  {
    case: transformation(hasName(equalTo("dictionary"))),
    verify: sources(
      count(equalTo(1)).withMessage(
        "Transformation requires exactly one input.",
      ),
    )
      .and(
        transformation(parameters(count(greaterThan(0)))).withMessage(
          "Wrong transformation parameters count. Should be >= 1.",
        ),
      )
      .and(
        transformationParameters(
          filter(
            (_value, index) => index > 0,
            forEach(
              new DictionaryEntryVerifier().withMessage(
                (context) =>
                  `Wrong dictionary entry value: ${context.entity.parameterValue}.`,
              ),
            ),
          ),
        ),
      ),
  },
  {
    case: transformation(hasName(equalTo("conditional"))),
    verify: transformation(
      parameters(
        count(equalTo(3)).withMessage("Wrong transformation parameters count."),
      ),
    ).and(
      transformationParameters(forEach(new TransformationExpressionVerifier())),
    ),
  },
  {
    case: transformation(hasName(equalTo("trim"))),
    verify: transformation(
      parameters(count(equalTo(1))).withMessage(
        "Wrong transformation parameters count.",
      ),
    ).and(
      transformationParameters(
        map(
          (detail) => detail?.[0]?.parameterValue ?? "",
          among(["left", "right", "both"]),
        ),
      ).withMessage("Trim side must be one of left, right, both."),
    ),
  },
  {
    case: transformation(hasName(equalTo("replaceAll"))),
    verify: transformation(
      parameters(count(equalTo(2))).withMessage(
        "Wrong transformation parameters count.",
      ),
    )
      .and(
        transformationParameters(
          map((detail) => detail?.[0]?.parameterValue ?? "", isRegex()),
        ),
      )
      .and(
        transformationParameters(
          map(
            (detail) => detail?.[1]?.parameterValue ?? "",
            isReplacementString(),
          ),
        ),
      ),
  },
  {
    case: transformation(
      exists().and(hasName(not(among(TRANSFORMATIONS.map((t) => t.name))))),
    ),
    verify: fail<MappingActionDetail>().withMessage(
      (context) =>
        `Unknown transformation: ${context.entity.action.transformation.name}`,
    ),
  },
]);

export function verifyMappingAction(
  action: MappingAction,
  mapping: MappingDescription,
): VerificationError[] {
  return MAPPING_ACTION_VERIFIER.verify({ action, mapping });
}
