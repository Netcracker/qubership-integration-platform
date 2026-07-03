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
});
