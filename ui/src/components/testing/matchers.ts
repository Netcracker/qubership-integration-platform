import {
  MatcherEntityType,
  MatcherType,
  TestingMatcher,
  TestingNamedParameter,
} from "../../api/apiTypes.ts";
import { isHttpFieldName } from "../../misc/http-field-utils.ts";
import { matchesByFields } from "../table/tableSearch.ts";

/** Which side of an exchange a matcher inspects: mock requests or test-case responses. */
export type MatcherOwnerKind = "request" | "response";

export const MATCHER_TYPE_LABELS: Record<MatcherType, string> = {
  [MatcherType.EMPTY]: "Empty",
  [MatcherType.EXIST]: "Exists",
  [MatcherType.EQUAL]: "Equals",
  [MatcherType.CONTAIN]: "Contains",
  [MatcherType.MATCH]: "Matches pattern",
  [MatcherType.START_WITH]: "Starts with",
  [MatcherType.END_WITH]: "Ends with",
  [MatcherType.MATCH_JSON_SCHEMA]: "Matches JSON Schema",
  [MatcherType.MATCH_JSON]: "Matches JSON",
};

export const MATCHER_ENTITY_TYPE_LABELS: Record<MatcherEntityType, string> = {
  [MatcherEntityType.BODY]: "Body",
  [MatcherEntityType.HEADER]: "Header",
  [MatcherEntityType.STATUS]: "HTTP response status code",
  [MatcherEntityType.QUERY_PARAMETER]: "Query parameter",
  [MatcherEntityType.PATH_PARAMETER]: "Path parameter",
};

/** A request carries no status, and a response carries no path or query parameter. */
const ENTITY_TYPES_BY_OWNER_KIND: Record<
  MatcherOwnerKind,
  MatcherEntityType[]
> = {
  request: [
    MatcherEntityType.BODY,
    MatcherEntityType.HEADER,
    MatcherEntityType.PATH_PARAMETER,
    MatcherEntityType.QUERY_PARAMETER,
  ],
  response: [
    MatcherEntityType.BODY,
    MatcherEntityType.STATUS,
    MatcherEntityType.HEADER,
  ],
};

export function getEntityTypesForOwnerKind(
  kind: MatcherOwnerKind,
): MatcherEntityType[] {
  return ENTITY_TYPES_BY_OWNER_KIND[kind];
}

/**
 * Parameter names the matching engine reads, per matcher type. Nothing on the
 * server checks these: a matcher stored under any other name is accepted and
 * then never fires, so this map is the only guard.
 */
export const MATCHER_PARAMETER_NAMES: Record<MatcherType, string[]> = {
  [MatcherType.EMPTY]: [],
  [MatcherType.EXIST]: [],
  [MatcherType.EQUAL]: ["value"],
  [MatcherType.CONTAIN]: ["value"],
  [MatcherType.MATCH]: ["pattern"],
  [MatcherType.START_WITH]: ["value"],
  [MatcherType.END_WITH]: ["value"],
  [MatcherType.MATCH_JSON_SCHEMA]: ["path", "schema"],
  [MatcherType.MATCH_JSON]: ["path", "sample"],
};

const ENTITY_TYPES_REQUIRING_NAME: MatcherEntityType[] = [
  MatcherEntityType.HEADER,
  MatcherEntityType.PATH_PARAMETER,
  MatcherEntityType.QUERY_PARAMETER,
];

export function matcherRequiresEntityName(
  entityType: MatcherEntityType | null | undefined,
): boolean {
  return !!entityType && ENTITY_TYPES_REQUIRING_NAME.includes(entityType);
}

/** Returns one message per missing or unknown parameter; an empty array means valid. */
export function validateMatcherParameters(
  type: MatcherType | null | undefined,
  parameters: TestingNamedParameter[] | null | undefined,
): string[] {
  if (!type) {
    return ["Unknown matcher type"];
  }
  const expected = new Set(MATCHER_PARAMETER_NAMES[type]);
  const given = new Set((parameters ?? []).map((parameter) => parameter.name));

  const messages: string[] = [];
  for (const name of expected) {
    if (!given.has(name)) {
      messages.push(`Missing parameter: ${name}`);
    }
  }
  for (const name of given) {
    if (!expected.has(name)) {
      messages.push(`Unknown parameter: ${name}`);
    }
  }
  return messages;
}

export function matcherParametersAreValid(
  type: MatcherType | null | undefined,
  parameters: TestingNamedParameter[] | null | undefined,
): boolean {
  return validateMatcherParameters(type, parameters).length === 0;
}

/**
 * Whether the name survives being written into a `{name}` template segment and
 * read back, which is how the service addresses a path parameter. A slash makes
 * two segments, a closing brace ends the placeholder early, a question mark or a
 * hash ends the path, and a percent sign is decoded into something else.
 */
function isAddressablePathParameterName(name: string): boolean {
  return [...name].every(
    (character) =>
      !"/}?#%".includes(character) && character.charCodeAt(0) >= 0x20,
  );
}

/**
 * Whether the entity name is one the service can build a data getter for. A
 * query parameter name is held to the blank rule alone, since the query string
 * carries it percent-encoded.
 */
export function isEntityNameAddressable(
  entityType: MatcherEntityType | null | undefined,
  entityName: string | null | undefined,
): boolean {
  if (!matcherRequiresEntityName(entityType)) {
    return true;
  }
  const name = entityName ?? "";
  if (name.trim().length === 0) {
    return false;
  }
  if (entityType === MatcherEntityType.HEADER) {
    return isHttpFieldName(name);
  }
  if (entityType === MatcherEntityType.PATH_PARAMETER) {
    return isAddressablePathParameterName(name);
  }
  return true;
}

/** Whether the document a JSON matcher carries is one the service can parse. */
export function isJsonDocumentValid(text: string): boolean {
  if (text.trim().length === 0) {
    return false;
  }
  try {
    JSON.parse(text);
    return true;
  } catch {
    return false;
  }
}

/**
 * The pattern of a `match` matcher is left to the service: Go compiles RE2, and a
 * browser check would refuse patterns RE2 accepts.
 */
function matcherDocumentIsValid(matcher: TestingMatcher): boolean {
  const editor = getMatcherParameterEditor(matcher.type, matcher.entityType);
  if (editor.kind !== "json") {
    return true;
  }
  const document = (matcher.parameters ?? []).find(
    (parameter) => parameter.name === editor.documentParameterName,
  );
  return isJsonDocumentValid(document?.value ?? "");
}

export function isMatcherValid(matcher: TestingMatcher): boolean {
  return (
    !!matcher.name &&
    !!matcher.type &&
    !!matcher.entityType &&
    isEntityNameAddressable(matcher.entityType, matcher.entityName) &&
    matcherParametersAreValid(matcher.type, matcher.parameters) &&
    matcherDocumentIsValid(matcher)
  );
}

export function matchersAreValid(
  matchers: TestingMatcher[] | null | undefined,
): boolean {
  return (matchers ?? []).every(isMatcherValid);
}

export type MatcherParameterEditor =
  | { kind: "none" }
  | { kind: "single"; parameterName: string }
  | { kind: "status" }
  | { kind: "json"; documentParameterName: string };

/**
 * Picks the editor for a matcher's parameters. The status-code picker replaces
 * the plain field only for an `equal` matcher over the response status.
 */
export function getMatcherParameterEditor(
  type: MatcherType | null | undefined,
  entityType: MatcherEntityType | null | undefined,
): MatcherParameterEditor {
  if (!type || MATCHER_PARAMETER_NAMES[type].length === 0) {
    return { kind: "none" };
  }
  if (type === MatcherType.MATCH_JSON_SCHEMA) {
    return { kind: "json", documentParameterName: "schema" };
  }
  if (type === MatcherType.MATCH_JSON) {
    return { kind: "json", documentParameterName: "sample" };
  }
  if (type === MatcherType.EQUAL && entityType === MatcherEntityType.STATUS) {
    return { kind: "status" };
  }
  return { kind: "single", parameterName: MATCHER_PARAMETER_NAMES[type][0] };
}

/** Parameters of the previous type never fit the new one, so they are dropped. */
export function withMatcherType(
  matcher: TestingMatcher,
  type: MatcherType,
): TestingMatcher {
  if (matcher.type === type) {
    return matcher;
  }
  return { ...matcher, type, parameters: [] };
}

export function withEntityType(
  matcher: TestingMatcher,
  entityType: MatcherEntityType,
): TestingMatcher {
  return {
    ...matcher,
    entityType,
    entityName: matcherRequiresEntityName(entityType)
      ? matcher.entityName
      : null,
  };
}

export function matcherMatchesSearch(
  matcher: TestingMatcher,
  term: string,
): boolean {
  return matchesByFields(term, [
    matcher.name,
    matcher.description,
    MATCHER_TYPE_LABELS[matcher.type],
    MATCHER_ENTITY_TYPE_LABELS[matcher.entityType],
    matcher.entityName,
    matcher.enabled ? "enabled" : "disabled",
    ...(matcher.parameters ?? []).map((parameter) => parameter.value),
  ]);
}

/**
 * A new row is deliberately incomplete: it carries no name and no parameters,
 * so the owning editor reports it invalid until the user fills it in.
 */
export function createMatcher(kind: MatcherOwnerKind): TestingMatcher {
  return {
    id: crypto.randomUUID(),
    name: "",
    description: "",
    enabled: true,
    type: MatcherType.EQUAL,
    entityType: getEntityTypesForOwnerKind(kind)[0],
    entityName: null,
    parameters: [],
  };
}
