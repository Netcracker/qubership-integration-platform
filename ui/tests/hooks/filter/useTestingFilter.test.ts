/**
 * @jest-environment jsdom
 */

Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: jest.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: jest.fn(),
    removeListener: jest.fn(),
    addEventListener: jest.fn(),
    removeEventListener: jest.fn(),
    dispatchEvent: jest.fn(),
  })),
});

import { describe, expect, it } from "@jest/globals";
import { renderHook } from "@testing-library/react";
import dayjs from "dayjs";
import { TestingFilterCondition } from "../../../src/api/apiTypes";
import {
  EntityFilterModel,
  FilterCondition,
} from "../../../src/components/table/filter/filterTypes";

jest.mock("../../../src/Modals", () => ({
  useModalsContext: () => ({ showModal: jest.fn() }),
}));

import {
  buildTestingFilters,
  ENDPOINT_MOCKS_SORT_FIELDS,
  formatTestingTimestamp,
  getTestingFilterColumns,
  TEST_CASES_SORT_FIELDS,
  TEST_CASE_RUNS_SORT_FIELDS,
  TESTS_RUNS_SORT_FIELDS,
  useTestingFilter,
} from "../../../src/hooks/filter/useTestingFilter";

const chains = [
  { id: "chain-1", name: "Order Intake" },
  { id: "chain-2", name: "Billing" },
];

const elements = [
  { id: "element-1", name: "HTTP Trigger" },
  { id: "element-2", name: "Service Call" },
];

function filter(
  column: string,
  condition: string,
  value?: string,
): EntityFilterModel {
  return { column, condition, value };
}

function epochMillis(
  year: number,
  month: number,
  day: number,
  hours: number,
  minutes: number,
  seconds = 0,
  millis = 0,
): number {
  return new Date(year, month, day, hours, minutes, seconds, millis).getTime();
}

// The tokens below are written out rather than read off TestingFilterCondition:
// the enum is what produces them, so asserting against it would pin nothing. The
// service answers an unknown token with 400, and a hyphen for an underscore is
// exactly the defect this branch set out to avoid.
describe("condition wire tokens", () => {
  it.each([
    [FilterCondition.CONTAINS.id, "contains"],
    [FilterCondition.DOES_NOT_CONTAIN.id, "does_not_contain"],
    [FilterCondition.STARTS_WITH.id, "starts_with"],
    [FilterCondition.ENDS_WITH.id, "ends_with"],
    [FilterCondition.IS.id, "is"],
    [FilterCondition.IS_NOT.id, "is_not"],
    [FilterCondition.LESS_THAN.id, "less_than"],
    [FilterCondition.GREATER_THAN.id, "greater_than"],
  ])("should send %s on the wire as %s", (conditionId, token) => {
    const { filters } = buildTestingFilters([
      filter("name", conditionId, "order"),
    ]);
    expect(filters).toEqual([
      { feature: "name", condition: token, values: ["order"] },
    ]);
  });

  it.each([
    [FilterCondition.IN.id, "in"],
    [FilterCondition.NOT_IN.id, "not_in"],
  ])("should send %s on the wire as %s", (conditionId, token) => {
    const { filters } = buildTestingFilters([
      filter("status", conditionId, "pending,running"),
    ]);
    expect(filters).toEqual([
      { feature: "status", condition: token, values: ["pending", "running"] },
    ]);
  });

  it.each([
    [FilterCondition.IS_AFTER.id, "is_after"],
    [FilterCondition.IS_BEFORE.id, "is_before"],
  ])("should send %s on the wire as %s", (conditionId, token) => {
    const { filters } = buildTestingFilters([
      filter("created_at", conditionId, String(epochMillis(2026, 0, 15, 9, 0))),
    ]);
    expect(filters[0].condition).toBe(token);
  });

  it("should send is_within on the wire with an underscore", () => {
    const from = epochMillis(2026, 0, 15, 0, 0);
    const to = epochMillis(2026, 0, 16, 0, 0);
    const { filters } = buildTestingFilters([
      filter("start", FilterCondition.IS_WITHIN.id, `${from},${to}`),
    ]);
    expect(filters[0].condition).toBe("is_within");
  });

  it("should resolve a name filter to in and not_in with underscores", () => {
    const included = buildTestingFilters(
      [filter("chain_name", FilterCondition.CONTAINS.id, "order")],
      { chains },
    );
    const excluded = buildTestingFilters(
      [filter("chain_name", FilterCondition.DOES_NOT_CONTAIN.id, "order")],
      { chains },
    );
    expect(included.filters[0].condition).toBe("in");
    expect(excluded.filters[0].condition).toBe("not_in");
  });

  it("should spell every condition the enum declares with underscores only", () => {
    for (const token of Object.values(TestingFilterCondition)) {
      expect(token).toMatch(/^[a-z]+(_[a-z]+)*$/);
    }
  });
});

describe("buildTestingFilters", () => {
  it("should map the numeric conditions when the column is a count", () => {
    const { filters } = buildTestingFilters([
      filter("errors", FilterCondition.LESS_THAN.id, "5"),
      filter("test_cases", FilterCondition.GREATER_THAN.id, "2"),
    ]);
    expect(filters).toEqual([
      { feature: "errors", condition: "less_than", values: ["5"] },
      { feature: "test_cases", condition: "greater_than", values: ["2"] },
    ]);
  });

  it("should format a timestamp bound on a 24-hour clock", () => {
    const afternoon = epochMillis(2026, 0, 15, 14, 30, 5, 123);
    const { filters } = buildTestingFilters([
      filter("created_at", FilterCondition.IS_AFTER.id, String(afternoon)),
    ]);
    expect(filters).toEqual([
      {
        feature: "created_at",
        condition: TestingFilterCondition.IS_AFTER,
        values: [dayjs(afternoon).format("YYYY-MM-DD HH:mm:ss.SSS ZZ")],
      },
    ]);
    expect(filters[0].values[0]).toMatch(
      /^2026-01-15 14:30:05\.123 [+-]\d{4}$/,
    );
  });

  it("should send both bounds when a range carries them", () => {
    const from = epochMillis(2026, 0, 15, 0, 0);
    const to = epochMillis(2026, 0, 16, 0, 0);
    const { filters } = buildTestingFilters([
      filter("start", FilterCondition.IS_WITHIN.id, `${from},${to}`),
    ]);
    expect(filters).toEqual([
      {
        feature: "start",
        condition: TestingFilterCondition.IS_WITHIN,
        values: [formatTestingTimestamp(from), formatTestingTimestamp(to)],
      },
    ]);
  });

  it("should degrade a range with one bound to the open-ended condition", () => {
    const from = epochMillis(2026, 0, 15, 9, 0);
    const to = epochMillis(2026, 0, 16, 9, 0);
    const openEnd = buildTestingFilters([
      filter("start", FilterCondition.IS_WITHIN.id, `${from},0`),
    ]);
    const openStart = buildTestingFilters([
      filter("start", FilterCondition.IS_WITHIN.id, `0,${to}`),
    ]);
    expect(openEnd.filters).toEqual([
      {
        feature: "start",
        condition: TestingFilterCondition.IS_AFTER,
        values: [formatTestingTimestamp(from)],
      },
    ]);
    expect(openStart.filters).toEqual([
      {
        feature: "start",
        condition: TestingFilterCondition.IS_BEFORE,
        values: [formatTestingTimestamp(to)],
      },
    ]);
  });

  it("should send no values for a condition that takes none", () => {
    const { filters } = buildTestingFilters([
      filter("description", FilterCondition.EMPTY.id),
      filter("description", FilterCondition.NOT_EMPTY.id),
    ]);
    expect(filters).toEqual([
      { feature: "description", condition: "empty", values: [] },
      { feature: "description", condition: "not_empty", values: [] },
    ]);
  });

  it("should keep a comma inside the value when the condition takes one value", () => {
    const { filters } = buildTestingFilters([
      filter("name", FilterCondition.IS.id, "Orders, EU"),
    ]);
    expect(filters).toEqual([
      { feature: "name", condition: "is", values: ["Orders, EU"] },
    ]);
  });

  it("should drop a filter when it carries no value or an unknown condition", () => {
    const { filters, isEmpty } = buildTestingFilters([
      filter("name", FilterCondition.CONTAINS.id, ""),
      filter("start", FilterCondition.IS_WITHIN.id, "0,0"),
      filter("name", "SOUNDS_LIKE", "order"),
    ]);
    expect(filters).toEqual([]);
    expect(isEmpty).toBe(false);
  });

  it("should send the ids a chain name filter picks out", () => {
    const { filters, isEmpty } = buildTestingFilters(
      [filter("chain_name", FilterCondition.CONTAINS.id, "order")],
      { chains },
    );
    expect(isEmpty).toBe(false);
    expect(filters).toEqual([
      {
        feature: "chain_id",
        condition: TestingFilterCondition.IN,
        values: ["chain-1"],
      },
    ]);
  });

  it("should exclude the matched ids when the name condition is negated", () => {
    const { filters } = buildTestingFilters(
      [filter("chain_name", FilterCondition.DOES_NOT_CONTAIN.id, "order")],
      { chains },
    );
    expect(filters).toEqual([
      {
        feature: "chain_id",
        condition: TestingFilterCondition.NOT_IN,
        values: ["chain-1"],
      },
    ]);
  });

  it("should resolve an element name filter against the elements of the chain", () => {
    const { filters } = buildTestingFilters(
      [filter("element_name", FilterCondition.STARTS_WITH.id, "http")],
      { elements },
    );
    expect(filters).toEqual([
      {
        feature: "element_id",
        condition: TestingFilterCondition.IN,
        values: ["element-1"],
      },
    ]);
  });

  it("should report an empty result when a name filter matches nothing", () => {
    const { filters, isEmpty } = buildTestingFilters(
      [
        filter("name", FilterCondition.CONTAINS.id, "order"),
        filter("chain_name", FilterCondition.IS.id, "Missing"),
      ],
      { chains },
    );
    expect(isEmpty).toBe(true);
    expect(filters).toEqual([]);
  });

  it("should drop a negated name filter that matches nothing", () => {
    const { filters, isEmpty } = buildTestingFilters(
      [filter("chain_name", FilterCondition.IS_NOT.id, "Missing")],
      { chains },
    );
    expect(isEmpty).toBe(false);
    expect(filters).toEqual([]);
  });
});

describe("getTestingFilterColumns", () => {
  it("should offer the chain column outside a chain and the element column inside one", () => {
    const global = getTestingFilterColumns("testCases").map(
      (column) => column.id,
    );
    const inChain = getTestingFilterColumns("testCases", "chain-1").map(
      (column) => column.id,
    );
    expect(global).toContain("chain_name");
    expect(global).not.toContain("element_name");
    expect(inChain).toContain("element_name");
    expect(inChain).not.toContain("chain_name");
  });

  it("should offer the mock-specific columns for endpoint mocks", () => {
    const columns = getTestingFilterColumns("endpointMocks", "chain-1").map(
      (column) => column.id,
    );
    expect(columns).toEqual(
      expect.arrayContaining(["status", "delay", "enabled"]),
    );
  });

  it("should keep the run columns within the features the service declares", () => {
    const runs = getTestingFilterColumns("testsRuns").map(
      (column) => column.id,
    );
    const caseRuns = getTestingFilterColumns("testCaseRuns", "chain-1").map(
      (column) => column.id,
    );
    expect(runs).toEqual([
      "chain_name",
      "start",
      "finish",
      "status",
      "errors",
      "test_cases",
      "created_by",
      "created_at",
    ]);
    expect(caseRuns).toEqual([
      "test_case_name",
      "start",
      "finish",
      "status",
      "errors",
    ]);
  });

  it("should leave the updated fields out of the run sort fields", () => {
    expect(TESTS_RUNS_SORT_FIELDS).not.toContain("updated_at");
    expect(TESTS_RUNS_SORT_FIELDS).not.toContain("updated_by");
  });
});

// The service validates sort_by per entity and answers anything it does not
// declare with 400, so each set is pinned whole rather than sampled.
describe("sort fields", () => {
  it("should accept exactly the fields test cases sort by", () => {
    expect([...TEST_CASES_SORT_FIELDS]).toEqual([
      "id",
      "name",
      "description",
      "enabled",
      "chain_id",
      "element_id",
      "created_by",
      "created_at",
      "updated_by",
      "updated_at",
      "validation_rule_count",
      "enabled_rule_count",
    ]);
  });

  it("should accept exactly the fields endpoint mocks sort by", () => {
    expect([...ENDPOINT_MOCKS_SORT_FIELDS]).toEqual([
      "id",
      "name",
      "description",
      "chain_id",
      "element_id",
      "enabled",
      "status",
      "delay",
      "created_by",
      "created_at",
      "updated_by",
      "updated_at",
    ]);
  });

  it("should accept exactly the fields test runs sort by", () => {
    expect([...TESTS_RUNS_SORT_FIELDS]).toEqual([
      "id",
      "start",
      "finish",
      "status",
      "errors",
      "test_cases",
      "created_by",
      "created_at",
    ]);
  });

  it("should accept exactly the fields test case runs sort by", () => {
    expect([...TEST_CASE_RUNS_SORT_FIELDS]).toEqual([
      "id",
      "test_case_name",
      "chain_id",
      "start",
      "finish",
      "status",
      "errors",
    ]);
  });
});

describe("useTestingFilter", () => {
  it("should start with no filters and expose the filter button", () => {
    const { result } = renderHook(() => useTestingFilter("testCases"));
    expect(result.current.filters).toEqual([]);
    expect(result.current.filterButton).toBeDefined();
  });
});
