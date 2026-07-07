import { describe, it, expect } from "@jest/globals";
import { LogOperation, EntityType } from "../../src/api/apiTypes.ts";
import { convertTableFiltersToApi } from "../../src/misc/action-log-filter-utils.ts";

describe("convertTableFiltersToApi", () => {
  it("returns empty array when no filters are active", () => {
    expect(convertTableFiltersToApi({})).toEqual([]);
  });

  it("converts initiator text filter", () => {
    expect(
      convertTableFiltersToApi({
        username: [JSON.stringify({ condition: "contains", value: "alice" })],
      }),
    ).toEqual([
      {
        column: "INITIATOR",
        condition: "CONTAINS",
        value: "alice",
      },
    ]);
  });

  it("converts entity name text filter with is-not condition", () => {
    expect(
      convertTableFiltersToApi({
        entityName: [JSON.stringify({ condition: "is-not", value: "temp" })],
      }),
    ).toEqual([
      {
        column: "ENTITY_NAME",
        condition: "IS_NOT",
        value: "temp",
      },
    ]);
  });

  it("converts action time is-within filter", () => {
    expect(
      convertTableFiltersToApi({
        actionTime: [
          JSON.stringify({
            condition: "is-within",
            value: [1000, 2000],
          }),
        ],
      }),
    ).toEqual([
      {
        column: "ACTION_TIME",
        condition: "IS_WITHIN",
        value: "1000,2000",
      },
    ]);
  });

  it("converts operation multi-select to IN filter", () => {
    expect(
      convertTableFiltersToApi({
        operation: [LogOperation.CREATE, LogOperation.DELETE],
      }),
    ).toEqual([
      {
        column: "OPERATION",
        condition: "IN",
        value: "CREATE,DELETE",
      },
    ]);
  });

  it("converts entity type multi-select to IN filter", () => {
    expect(
      convertTableFiltersToApi({
        entityType: [EntityType.CHAIN, EntityType.FOLDER],
      }),
    ).toEqual([
      {
        column: "ENTITY_TYPE",
        condition: "IN",
        value: "CHAIN,FOLDER",
      },
    ]);
  });

  it("skips text filters with empty values", () => {
    expect(
      convertTableFiltersToApi({
        parentName: [JSON.stringify({ condition: "contains", value: "" })],
      }),
    ).toEqual([]);
  });

  it.each([
    ["not-contains", "DOES_NOT_CONTAIN", "PARENT_NAME", "parentName"],
    ["starts-with", "STARTS_WITH", "ENTITY_NAME", "entityName"],
    ["ends-with", "ENDS_WITH", "INITIATOR", "username"],
    ["is", "IS", "INITIATOR", "username"],
  ])(
    "converts %s text filter on %s column",
    (
      condition: string,
      apiCondition: string,
      apiColumn: string,
      tableKey: string,
    ) => {
      expect(
        convertTableFiltersToApi({
          [tableKey]: [JSON.stringify({ condition, value: "test" })],
        }),
      ).toEqual([
        {
          column: apiColumn,
          condition: apiCondition,
          value: "test",
        },
      ]);
    },
  );

  it.each([
    ["is-before", "IS_BEFORE", "1000"],
    ["is-after", "IS_AFTER", "2000"],
  ])(
    "converts action time %s filter",
    (condition: string, apiCondition: string, timestamp: string) => {
      expect(
        convertTableFiltersToApi({
          actionTime: [
            JSON.stringify({
              condition,
              value: [Number(timestamp)],
            }),
          ],
        }),
      ).toEqual([
        {
          column: "ACTION_TIME",
          condition: apiCondition,
          value: timestamp,
        },
      ]);
    },
  );

  it("skips timestamp filters with empty values", () => {
    expect(
      convertTableFiltersToApi({
        actionTime: [
          JSON.stringify({
            condition: "is-before",
            value: [],
          }),
        ],
      }),
    ).toEqual([]);
  });
});
