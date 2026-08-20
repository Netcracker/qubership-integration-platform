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
 * Parameter names the matching engine reads, per matcher type. An extra
 * parameter under a name no predicate reads is stored and then ignored, and this
 * map is the only guard against that half.
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

/**
 * Parameter names a matcher may leave out. The service reads `path` as optional
 * and falls back to the whole document, so a JSON matcher saved without one
 * matches perfectly well. The editor writes it all the same; a matcher that
 * arrives from an import or from the API is the one that would otherwise be
 * reported broken while it runs.
 */
const MATCHER_OPTIONAL_PARAMETER_NAMES: Partial<Record<MatcherType, string[]>> =
  {
    [MatcherType.MATCH_JSON_SCHEMA]: ["path"],
    [MatcherType.MATCH_JSON]: ["path"],
  };

/** The wire is not held to the enum, so a type the UI does not know has no entry. */
function matcherParameterNames(type: MatcherType): string[] | undefined {
  return (MATCHER_PARAMETER_NAMES as Partial<Record<MatcherType, string[]>>)[
    type
  ];
}

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

/**
 * Returns one message per missing, unknown or repeated parameter; an empty array
 * means valid. A parameter the service reads as optional is not reported missing,
 * so a matcher that runs is never shown as broken. A repeated name is refused
 * because the service reads a parameter as a single value and gets two.
 */
export function validateMatcherParameters(
  type: MatcherType | null | undefined,
  parameters: TestingNamedParameter[] | null | undefined,
): string[] {
  const names = type ? matcherParameterNames(type) : undefined;
  if (!names) {
    return ["Unknown matcher type"];
  }
  const expected = new Set(names);
  const optional = new Set(
    (type ? MATCHER_OPTIONAL_PARAMETER_NAMES[type] : undefined) ?? [],
  );
  const given = new Set<string>();
  const repeated = new Set<string>();
  for (const parameter of parameters ?? []) {
    if (given.has(parameter.name)) {
      repeated.add(parameter.name);
    }
    given.add(parameter.name);
  }

  const messages: string[] = [];
  for (const name of expected) {
    if (!given.has(name) && !optional.has(name)) {
      messages.push(`Missing parameter: ${name}`);
    }
  }
  for (const name of given) {
    if (!expected.has(name)) {
      messages.push(`Unknown parameter: ${name}`);
    }
  }
  for (const name of repeated) {
    messages.push(`Repeated parameter: ${name}`);
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

/** Renders the parameters in a stable order, so the same set yields one key. */
function parametersKey(
  parameters: TestingNamedParameter[] | null | undefined,
): string {
  return (parameters ?? [])
    .map(
      (parameter) =>
        `${JSON.stringify(parameter.name)}=${JSON.stringify(parameter.value)}`,
    )
    .sort()
    .join(",");
}

const KNOWN_ENTITY_TYPES = new Set<string>(Object.values(MatcherEntityType));

/** The wire is not held to the enum, and the service reads nothing off a type it has no getter for. */
function isKnownEntityType(entityType: MatcherEntityType): boolean {
  return KNOWN_ENTITY_TYPES.has(entityType);
}

/**
 * What a matcher carries that the save would refuse, one key per offending
 * value. The keys let an editor tell a value the user has just broken from one
 * the stored entity already carried, which the service lets an update keep; they
 * are read nowhere else and never go on the wire.
 */
export function matcherViolations(matcher: TestingMatcher): string[] {
  const violations: string[] = [];
  if (!matcher.name) {
    violations.push("matcher without a name");
  }
  if (!matcher.entityType) {
    violations.push("matcher without an entity type");
  } else if (
    !isKnownEntityType(matcher.entityType) ||
    !isEntityNameAddressable(matcher.entityType, matcher.entityName)
  ) {
    violations.push(
      `matcher entity ${matcher.entityType} ${JSON.stringify(matcher.entityName ?? "")}`,
    );
  }
  if (!matcher.type) {
    violations.push("matcher without a type");
  } else if (
    !matcherParametersAreValid(matcher.type, matcher.parameters) ||
    !matcherDocumentIsValid(matcher)
  ) {
    violations.push(
      `matcher predicate ${matcher.type} ${parametersKey(matcher.parameters)}`,
    );
  }
  return violations;
}

export function matchersViolations(
  matchers: TestingMatcher[] | null | undefined,
): string[] {
  return (matchers ?? []).flatMap(matcherViolations);
}

export function isMatcherValid(matcher: TestingMatcher): boolean {
  return matcherViolations(matcher).length === 0;
}

export type MatcherParameterEditor =
  | { kind: "none" }
  | { kind: "single"; parameterName: string }
  | { kind: "status"; parameterName: string }
  | { kind: "json"; documentParameterName: string };

/**
 * Picks the editor for a matcher's parameters. The status-code picker replaces
 * the plain field only for an `equal` matcher over the response status.
 */
export function getMatcherParameterEditor(
  type: MatcherType | null | undefined,
  entityType: MatcherEntityType | null | undefined,
): MatcherParameterEditor {
  const names = type ? matcherParameterNames(type) : undefined;
  if (!names || names.length === 0) {
    return { kind: "none" };
  }
  // A JSON matcher names `path` first and the document it is matched against second.
  if (
    type === MatcherType.MATCH_JSON_SCHEMA ||
    type === MatcherType.MATCH_JSON
  ) {
    return { kind: "json", documentParameterName: names[1] };
  }
  if (type === MatcherType.EQUAL && entityType === MatcherEntityType.STATUS) {
    return { kind: "status", parameterName: names[0] };
  }
  return { kind: "single", parameterName: names[0] };
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
