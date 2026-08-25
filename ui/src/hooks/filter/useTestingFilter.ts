import { ReactNode, useMemo } from "react";
import dayjs from "dayjs";
import {
  BooleanFilterConditions,
  DateFilterConditions,
  EntityFilterModel,
  ExtendedStringFilterConditions,
  FilterColumn,
  FilterCondition,
  ListFilterConditions,
  ListValue,
  NumberFilterConditions,
} from "../../components/table/filter/filterTypes";
import { useFilter } from "../../components/table/filter/useFilter";
import {
  TestingFilter,
  TestingFilterCondition,
  TestRunStatus,
} from "../../api/apiTypes";
import { formatSnakeCased } from "../../misc/format-utils";

/** Lists the testing service serves, each with its own features and sort fields. */
export type TestingEntityKind =
  | "testCases"
  | "endpointMocks"
  | "testsRuns"
  | "testCaseRuns";

export type NamedEntity = { id: string; name: string };

/** Chains and elements a name filter is matched against. */
export type TestingFilterLookup = {
  chains?: NamedEntity[];
  elements?: NamedEntity[];
};

export type TestingFilterSelection = {
  filters: TestingFilter[];
  /** A name filter matched nothing, so the list has no rows to ask the service for. */
  isEmpty: boolean;
};

export const TESTING_CHAIN_FEATURE = "chain_id";
export const TESTING_ELEMENT_FEATURE = "element_id";
/** Feature carrying the run a case run belongs to. */
export const TESTING_TESTS_RUN_FEATURE = "tests_run_id";

/** Columns whose values are names; the ids they resolve to go on the wire. */
export const TESTING_CHAIN_NAME_COLUMN = "chain_name";
export const TESTING_ELEMENT_NAME_COLUMN = "element_name";

const RESOLVED_FEATURES: Record<string, string> = {
  [TESTING_CHAIN_NAME_COLUMN]: TESTING_CHAIN_FEATURE,
  [TESTING_ELEMENT_NAME_COLUMN]: TESTING_ELEMENT_FEATURE,
};

// Wire token of every condition the filter dialog can produce. `empty` and
// `not_empty` are here because the service names them, but no feature declares
// them, so no column below offers them either.
const CONDITION_TOKENS: Record<string, TestingFilterCondition> = {
  [FilterCondition.CONTAINS.id]: TestingFilterCondition.CONTAINS,
  [FilterCondition.DOES_NOT_CONTAIN.id]:
    TestingFilterCondition.DOES_NOT_CONTAIN,
  [FilterCondition.STARTS_WITH.id]: TestingFilterCondition.STARTS_WITH,
  [FilterCondition.ENDS_WITH.id]: TestingFilterCondition.ENDS_WITH,
  [FilterCondition.IS.id]: TestingFilterCondition.IS,
  [FilterCondition.IS_NOT.id]: TestingFilterCondition.IS_NOT,
  [FilterCondition.IN.id]: TestingFilterCondition.IN,
  [FilterCondition.NOT_IN.id]: TestingFilterCondition.NOT_IN,
  [FilterCondition.EMPTY.id]: TestingFilterCondition.EMPTY,
  [FilterCondition.NOT_EMPTY.id]: TestingFilterCondition.NOT_EMPTY,
  [FilterCondition.LESS_THAN.id]: TestingFilterCondition.LESS_THAN,
  [FilterCondition.GREATER_THAN.id]: TestingFilterCondition.GREATER_THAN,
  [FilterCondition.IS_BEFORE.id]: TestingFilterCondition.IS_BEFORE,
  [FilterCondition.IS_AFTER.id]: TestingFilterCondition.IS_AFTER,
  [FilterCondition.IS_WITHIN.id]: TestingFilterCondition.IS_WITHIN,
};

/** Positive twin of a negated condition, used to match names before excluding them. */
const POSITIVE_CONDITIONS: Record<string, string> = {
  [FilterCondition.DOES_NOT_CONTAIN.id]: FilterCondition.CONTAINS.id,
  [FilterCondition.IS_NOT.id]: FilterCondition.IS.id,
  [FilterCondition.NOT_IN.id]: FilterCondition.IN.id,
};

const DATE_CONDITIONS = new Set<string>([
  FilterCondition.IS_AFTER.id,
  FilterCondition.IS_BEFORE.id,
  FilterCondition.IS_WITHIN.id,
]);

const MULTI_VALUE_CONDITIONS = new Set<string>([
  FilterCondition.IN.id,
  FilterCondition.NOT_IN.id,
]);

// The service parses a 24-hour clock with a colon-less offset. A 12-hour one
// lands twelve hours off for every afternoon bound.
const TIMESTAMP_FORMAT = "YYYY-MM-DD HH:mm:ss.SSS ZZ";

export function formatTestingTimestamp(epochMillis: number): string {
  return dayjs(epochMillis).format(TIMESTAMP_FORMAT);
}

/** Fields each list accepts as `sort_by`; the service answers anything else with 400. */
export const TEST_CASES_SORT_FIELDS = [
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
] as const;

export const ENDPOINT_MOCKS_SORT_FIELDS = [
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
] as const;

// The updated pair is missing on purpose: the service does not sort runs by it.
export const TESTS_RUNS_SORT_FIELDS = [
  "id",
  "start",
  "finish",
  "status",
  "errors",
  "test_cases",
  "created_by",
  "created_at",
] as const;

export const TEST_CASE_RUNS_SORT_FIELDS = [
  "id",
  "test_case_name",
  "chain_id",
  "start",
  "finish",
  "status",
  "errors",
] as const;

function toStatusOptions(statuses: TestRunStatus[]): ListValue[] {
  return statuses.map((value) => ({ value, label: formatSnakeCased(value) }));
}

/** A case run carries the status of one attempt, so every value can occur. */
const CASE_RUN_STATUS_OPTIONS = toStatusOptions(Object.values(TestRunStatus));

// The run aggregate maps pending to running and skipped to finished before it is
// stored, so filtering runs by either would always come back empty.
const RUN_STATUS_OPTIONS = toStatusOptions([
  TestRunStatus.RUNNING,
  TestRunStatus.FINISHED,
  TestRunStatus.CANCELED,
]);

const NAME_COLUMN: FilterColumn = {
  id: "name",
  name: "Name",
  conditions: ExtendedStringFilterConditions,
};

const DESCRIPTION_COLUMN: FilterColumn = {
  id: "description",
  name: "Description",
  conditions: ExtendedStringFilterConditions,
};

const ENABLED_COLUMN: FilterColumn = {
  id: "enabled",
  name: "Enabled",
  conditions: BooleanFilterConditions,
};

const CHAIN_COLUMN: FilterColumn = {
  id: TESTING_CHAIN_NAME_COLUMN,
  name: "Chain",
  conditions: ExtendedStringFilterConditions,
};

const ELEMENT_COLUMN: FilterColumn = {
  id: TESTING_ELEMENT_NAME_COLUMN,
  name: "Element",
  conditions: ExtendedStringFilterConditions,
};

const RUN_STATUS_COLUMN: FilterColumn = {
  id: "status",
  name: "Status",
  conditions: ListFilterConditions,
  allowedValues: RUN_STATUS_OPTIONS,
};

const CASE_RUN_STATUS_COLUMN: FilterColumn = {
  id: "status",
  name: "Status",
  conditions: ListFilterConditions,
  allowedValues: CASE_RUN_STATUS_OPTIONS,
};

const AUDIT_COLUMNS: FilterColumn[] = [
  {
    id: "created_by",
    name: "Created By",
    conditions: ExtendedStringFilterConditions,
  },
  { id: "created_at", name: "Created At", conditions: DateFilterConditions },
  {
    id: "updated_by",
    name: "Updated By",
    conditions: ExtendedStringFilterConditions,
  },
  { id: "updated_at", name: "Updated At", conditions: DateFilterConditions },
];

/**
 * Columns of one list. The chain column appears only outside a chain, where the
 * chain still varies; the element column only inside one, since element names
 * are resolved from the elements of a single chain.
 */
export function getTestingFilterColumns(
  kind: TestingEntityKind,
  chainId?: string,
): FilterColumn[] {
  const chainColumn = chainId ? [] : [CHAIN_COLUMN];
  const elementColumn = chainId ? [ELEMENT_COLUMN] : [];
  switch (kind) {
    case "testCases":
      return [
        NAME_COLUMN,
        DESCRIPTION_COLUMN,
        ENABLED_COLUMN,
        ...chainColumn,
        ...elementColumn,
        {
          id: "validation_rule_count",
          name: "Validation Rules",
          conditions: NumberFilterConditions,
        },
        {
          id: "enabled_rule_count",
          name: "Enabled Rules",
          conditions: NumberFilterConditions,
        },
        ...AUDIT_COLUMNS,
      ];
    case "endpointMocks":
      return [
        NAME_COLUMN,
        DESCRIPTION_COLUMN,
        ENABLED_COLUMN,
        ...chainColumn,
        ...elementColumn,
        {
          id: "status",
          name: "Response Status",
          conditions: NumberFilterConditions,
        },
        {
          id: "delay",
          name: "Response Delay",
          conditions: NumberFilterConditions,
        },
        ...AUDIT_COLUMNS,
      ];
    case "testsRuns":
      return [
        ...chainColumn,
        { id: "start", name: "Start", conditions: DateFilterConditions },
        { id: "finish", name: "Finish", conditions: DateFilterConditions },
        RUN_STATUS_COLUMN,
        // The aggregate counts the cases that failed, not the errors they recorded.
        {
          id: "errors",
          name: "Test Cases With Errors",
          conditions: NumberFilterConditions,
        },
        {
          id: "test_cases",
          name: "Test Cases",
          conditions: NumberFilterConditions,
        },
        ...AUDIT_COLUMNS.slice(0, 2),
      ];
    case "testCaseRuns":
      return [
        {
          id: "test_case_name",
          name: "Test Case",
          conditions: ExtendedStringFilterConditions,
        },
        ...chainColumn,
        { id: "start", name: "Start", conditions: DateFilterConditions },
        { id: "finish", name: "Finish", conditions: DateFilterConditions },
        CASE_RUN_STATUS_COLUMN,
        { id: "errors", name: "Errors", conditions: NumberFilterConditions },
      ];
  }
}

function splitValue(value: string | undefined): string[] {
  return (value ?? "").split(",");
}

function toEpochMillis(value: string | undefined): number | undefined {
  const millis = Number(value);
  return Number.isFinite(millis) && millis > 0 ? millis : undefined;
}

/**
 * The range picker reports a missing bound as zero, so a half-filled range
 * degrades to the open-ended condition instead of asking for a `is_within` the
 * service refuses for want of a second value.
 */
function toTimestampFilter(
  feature: string,
  conditionId: string,
  value: string | undefined,
): TestingFilter | undefined {
  const parts = splitValue(value);
  const from = toEpochMillis(parts[0]);
  const to = toEpochMillis(parts[1]);
  if (conditionId !== FilterCondition.IS_WITHIN.id) {
    return from === undefined
      ? undefined
      : {
          feature,
          condition: CONDITION_TOKENS[conditionId],
          values: [formatTestingTimestamp(from)],
        };
  }
  if (from !== undefined && to !== undefined) {
    return {
      feature,
      condition: TestingFilterCondition.IS_WITHIN,
      values: [formatTestingTimestamp(from), formatTestingTimestamp(to)],
    };
  }
  if (from !== undefined) {
    return {
      feature,
      condition: TestingFilterCondition.IS_AFTER,
      values: [formatTestingTimestamp(from)],
    };
  }
  if (to !== undefined) {
    return {
      feature,
      condition: TestingFilterCondition.IS_BEFORE,
      values: [formatTestingTimestamp(to)],
    };
  }
  return undefined;
}

function toWireFilter(filter: EntityFilterModel): TestingFilter | undefined {
  const condition = FilterCondition.getById(filter.condition);
  const token = CONDITION_TOKENS[filter.condition];
  if (!condition || !token) {
    return undefined;
  }
  if (!condition.valueRequired) {
    return { feature: filter.column, condition: token, values: [] };
  }
  if (DATE_CONDITIONS.has(filter.condition)) {
    return toTimestampFilter(filter.column, filter.condition, filter.value);
  }
  // Only `in` and `not_in` read a comma as a separator. Every other condition
  // takes the value whole, so a name carrying a comma is not cut short.
  const values = MULTI_VALUE_CONDITIONS.has(filter.condition)
    ? splitValue(filter.value)
        .map((value) => value.trim())
        .filter((value) => value.length > 0)
    : [(filter.value ?? "").trim()].filter((value) => value.length > 0);
  if (values.length === 0) {
    return undefined;
  }
  return { feature: filter.column, condition: token, values };
}

function matchNames(
  entities: NamedEntity[],
  filter: EntityFilterModel,
): string[] {
  const conditionId = POSITIVE_CONDITIONS[filter.condition] ?? filter.condition;
  const condition = FilterCondition.getById(conditionId);
  if (!condition) {
    return [];
  }
  return entities
    .filter((entity) => condition.func(filter.value, entity.name))
    .map((entity) => entity.id);
}

/**
 * Turns the filter dialog's output into the selection the service takes. Chain
 * and element filters are written against names, so they are matched here and
 * sent as the ids they picked out: a positive condition that picks out nothing
 * leaves the list empty, because `in` without values is a bad request.
 */
export function buildTestingFilters(
  filters: EntityFilterModel[],
  lookup: TestingFilterLookup = {},
): TestingFilterSelection {
  const result: TestingFilter[] = [];
  for (const filter of filters) {
    const feature = RESOLVED_FEATURES[filter.column];
    if (!feature) {
      const wireFilter = toWireFilter(filter);
      if (wireFilter) {
        result.push(wireFilter);
      }
      continue;
    }
    if (!filter.value?.trim()) {
      continue;
    }
    const entities =
      feature === TESTING_CHAIN_FEATURE
        ? (lookup.chains ?? [])
        : (lookup.elements ?? []);
    const ids = matchNames(entities, filter);
    const negated = filter.condition in POSITIVE_CONDITIONS;
    if (ids.length === 0) {
      if (negated) {
        continue;
      }
      return { filters: [], isEmpty: true };
    }
    result.push({
      feature,
      condition: negated
        ? TestingFilterCondition.NOT_IN
        : TestingFilterCondition.IN,
      values: ids,
    });
  }
  return { filters: result, isEmpty: false };
}

/** The filter dialog of one testing list, over the columns that list declares. */
export const useTestingFilter = (
  kind: TestingEntityKind,
  chainId?: string,
): { filters: EntityFilterModel[]; filterButton: ReactNode } => {
  const filterColumns = useMemo(
    () => getTestingFilterColumns(kind, chainId),
    [kind, chainId],
  );
  const { filters, filterButton } = useFilter(filterColumns);
  return { filters, filterButton };
};
