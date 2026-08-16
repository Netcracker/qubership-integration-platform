import {
  MatcherEntityType,
  MatcherType,
  TestingMatcher,
} from "../../../src/api/apiTypes.ts";
import {
  createMatcher,
  getEntityTypesForOwnerKind,
  getMatcherParameterEditor,
  isMatcherValid,
  MATCHER_PARAMETER_NAMES,
  matcherHasParameters,
  matcherMatchesSearch,
  matcherParametersAreValid,
  matcherRequiresEntityName,
  matchersAreValid,
  validateMatcherParameters,
  withEntityType,
  withMatcherType,
} from "../../../src/components/testing/matchers.ts";

function matcher(overrides: Partial<TestingMatcher> = {}): TestingMatcher {
  return {
    id: "m1",
    name: "rule",
    description: "",
    enabled: true,
    type: MatcherType.EQUAL,
    entityType: MatcherEntityType.BODY,
    entityName: null,
    parameters: [{ name: "value", value: "42" }],
    ...overrides,
  };
}

describe("matcher parameter names", () => {
  test.each([
    [MatcherType.EMPTY, []],
    [MatcherType.EXIST, []],
    [MatcherType.EQUAL, ["value"]],
    [MatcherType.CONTAIN, ["value"]],
    [MatcherType.START_WITH, ["value"]],
    [MatcherType.END_WITH, ["value"]],
    [MatcherType.MATCH, ["pattern"]],
    [MatcherType.MATCH_JSON_SCHEMA, ["path", "schema"]],
    [MatcherType.MATCH_JSON, ["path", "sample"]],
  ])("should read %s parameters under %s", (type, names) => {
    expect(MATCHER_PARAMETER_NAMES[type]).toEqual(names);
  });

  test("should cover every matcher type", () => {
    expect(Object.keys(MATCHER_PARAMETER_NAMES).sort()).toEqual(
      Object.values(MatcherType).sort(),
    );
  });

  test("should report no parameters when the type takes none", () => {
    expect(matcherHasParameters(MatcherType.EMPTY)).toBe(false);
    expect(matcherHasParameters(MatcherType.EXIST)).toBe(false);
    expect(matcherHasParameters(MatcherType.MATCH)).toBe(true);
  });
});

describe("entity type scoping", () => {
  test("should offer request entity types without status", () => {
    expect(getEntityTypesForOwnerKind("request")).toEqual([
      MatcherEntityType.BODY,
      MatcherEntityType.HEADER,
      MatcherEntityType.PATH_PARAMETER,
      MatcherEntityType.QUERY_PARAMETER,
    ]);
  });

  test("should offer response entity types without path or query parameters", () => {
    expect(getEntityTypesForOwnerKind("response")).toEqual([
      MatcherEntityType.BODY,
      MatcherEntityType.STATUS,
      MatcherEntityType.HEADER,
    ]);
  });

  test.each([
    [MatcherEntityType.HEADER, true],
    [MatcherEntityType.PATH_PARAMETER, true],
    [MatcherEntityType.QUERY_PARAMETER, true],
    [MatcherEntityType.BODY, false],
    [MatcherEntityType.STATUS, false],
  ])("should require an entity name for %s: %s", (entityType, required) => {
    expect(matcherRequiresEntityName(entityType)).toBe(required);
  });
});

describe("parameter editor selection", () => {
  test("should select no editor when the type takes no parameters", () => {
    expect(
      getMatcherParameterEditor(MatcherType.EMPTY, MatcherEntityType.BODY),
    ).toEqual({ kind: "none" });
    expect(
      getMatcherParameterEditor(MatcherType.EXIST, MatcherEntityType.HEADER),
    ).toEqual({ kind: "none" });
  });

  test("should select the plain editor writing value", () => {
    expect(
      getMatcherParameterEditor(MatcherType.CONTAIN, MatcherEntityType.BODY),
    ).toEqual({ kind: "single", parameterName: "value" });
  });

  test("should select the plain editor writing pattern for match", () => {
    expect(
      getMatcherParameterEditor(MatcherType.MATCH, MatcherEntityType.BODY),
    ).toEqual({ kind: "single", parameterName: "pattern" });
  });

  test("should select the status picker only for equal over status", () => {
    expect(
      getMatcherParameterEditor(MatcherType.EQUAL, MatcherEntityType.STATUS),
    ).toEqual({ kind: "status" });
    expect(
      getMatcherParameterEditor(MatcherType.EQUAL, MatcherEntityType.BODY),
    ).toEqual({ kind: "single", parameterName: "value" });
    expect(
      getMatcherParameterEditor(MatcherType.CONTAIN, MatcherEntityType.STATUS),
    ).toEqual({ kind: "single", parameterName: "value" });
  });

  test("should select the JSON editor with the document parameter of its type", () => {
    expect(
      getMatcherParameterEditor(
        MatcherType.MATCH_JSON_SCHEMA,
        MatcherEntityType.BODY,
      ),
    ).toEqual({ kind: "json", documentParameterName: "schema" });
    expect(
      getMatcherParameterEditor(MatcherType.MATCH_JSON, MatcherEntityType.BODY),
    ).toEqual({ kind: "json", documentParameterName: "sample" });
  });
});

describe("parameter validity", () => {
  test("should accept the exact parameter set", () => {
    expect(
      matcherParametersAreValid(MatcherType.MATCH, [
        { name: "pattern", value: "a+" },
      ]),
    ).toBe(true);
  });

  test("should report a missing parameter", () => {
    expect(validateMatcherParameters(MatcherType.EQUAL, [])).toEqual([
      "Missing parameter: value",
    ]);
  });

  test("should report a parameter name the engine never reads", () => {
    expect(
      validateMatcherParameters(MatcherType.MATCH, [
        { name: "value", value: "a+" },
      ]),
    ).toEqual(["Missing parameter: pattern", "Unknown parameter: value"]);
  });

  test("should require both JSON parameters", () => {
    expect(
      validateMatcherParameters(MatcherType.MATCH_JSON, [
        { name: "sample", value: "{}" },
      ]),
    ).toEqual(["Missing parameter: path"]);
    expect(
      matcherParametersAreValid(MatcherType.MATCH_JSON, [
        { name: "path", value: "$" },
        { name: "sample", value: "{}" },
      ]),
    ).toBe(true);
  });

  test("should reject parameters on a type that takes none", () => {
    expect(
      validateMatcherParameters(MatcherType.EMPTY, [
        { name: "value", value: "x" },
      ]),
    ).toEqual(["Unknown parameter: value"]);
  });

  test("should reject an unknown matcher type", () => {
    expect(validateMatcherParameters(null, [])).toEqual([
      "Unknown matcher type: null",
    ]);
  });
});

describe("matcher validity", () => {
  test("should accept a complete matcher", () => {
    expect(isMatcherValid(matcher())).toBe(true);
  });

  test("should reject a matcher without a name", () => {
    expect(isMatcherValid(matcher({ name: "" }))).toBe(false);
  });

  test("should reject a named entity type without an entity name", () => {
    expect(
      isMatcherValid(
        matcher({ entityType: MatcherEntityType.HEADER, entityName: null }),
      ),
    ).toBe(false);
    expect(
      isMatcherValid(
        matcher({
          entityType: MatcherEntityType.HEADER,
          entityName: "X-Trace",
        }),
      ),
    ).toBe(true);
  });

  test("should reject a matcher whose parameters do not fit its type", () => {
    expect(isMatcherValid(matcher({ parameters: [] }))).toBe(false);
  });

  test("should hold for every matcher in the set", () => {
    expect(matchersAreValid([matcher(), matcher({ id: "m2" })])).toBe(true);
    expect(matchersAreValid([matcher(), matcher({ id: "m2", name: "" })])).toBe(
      false,
    );
    expect(matchersAreValid(null)).toBe(true);
  });
});

describe("edit-time clearing", () => {
  test("should clear parameters when the matcher type changes", () => {
    expect(withMatcherType(matcher(), MatcherType.MATCH)).toEqual(
      expect.objectContaining({ type: MatcherType.MATCH, parameters: [] }),
    );
  });

  test("should keep parameters when the type is unchanged", () => {
    const current = matcher();
    expect(withMatcherType(current, MatcherType.EQUAL)).toBe(current);
  });

  test("should clear the entity name when it stops being required", () => {
    const current = matcher({
      entityType: MatcherEntityType.HEADER,
      entityName: "X-Trace",
    });
    expect(withEntityType(current, MatcherEntityType.BODY)).toEqual(
      expect.objectContaining({
        entityType: MatcherEntityType.BODY,
        entityName: null,
      }),
    );
  });

  test("should keep the entity name when it stays required", () => {
    const current = matcher({
      entityType: MatcherEntityType.HEADER,
      entityName: "X-Trace",
    });
    expect(
      withEntityType(current, MatcherEntityType.QUERY_PARAMETER).entityName,
    ).toBe("X-Trace");
  });
});

describe("local search", () => {
  test("should match on name, description, labels, entity name and parameter values", () => {
    const current = matcher({
      name: "status rule",
      description: "checks the payload",
      entityType: MatcherEntityType.HEADER,
      entityName: "X-Trace",
      parameters: [{ name: "value", value: "abc123" }],
    });
    expect(matcherMatchesSearch(current, "payload")).toBe(true);
    expect(matcherMatchesSearch(current, "x-trace")).toBe(true);
    expect(matcherMatchesSearch(current, "abc123")).toBe(true);
    expect(matcherMatchesSearch(current, "Equals")).toBe(true);
    expect(matcherMatchesSearch(current, "enabled")).toBe(true);
    expect(matcherMatchesSearch(current, "nothing here")).toBe(false);
  });

  test("should match everything on an empty term", () => {
    expect(matcherMatchesSearch(matcher(), "  ")).toBe(true);
  });
});

describe("createMatcher", () => {
  test("should start from the first entity type of the owner kind", () => {
    expect(createMatcher("response").entityType).toBe(MatcherEntityType.BODY);
    expect(createMatcher("request").entityType).toBe(MatcherEntityType.BODY);
  });

  test("should start invalid so the owner cannot save a blank rule", () => {
    expect(isMatcherValid(createMatcher("response"))).toBe(false);
  });

  test("should give each row its own key", () => {
    expect(createMatcher("response").id).not.toBe(createMatcher("response").id);
  });
});
