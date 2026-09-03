import { describe, it, expect } from "@jest/globals";
import { FilterCondition } from "../../../../src/components/table/filter/filterTypes";

const run = (
  id: string,
  filter: string | undefined,
  value: string | undefined,
) => FilterCondition.getById(id)!.func(filter, value);

describe("FilterCondition funcs", () => {
  describe("IN", () => {
    it("should match when the value is in the comma-separated list", () => {
      expect(run("IN", "Draft,Deployed", "Deployed")).toBe(true);
    });

    it("should not match when the value is not in the list", () => {
      expect(run("IN", "Draft,Deployed", "Failed")).toBe(false);
    });

    it("should not match when the value is undefined", () => {
      expect(run("IN", "Draft", undefined)).toBe(false);
    });

    it("should match all when the filter is empty", () => {
      expect(run("IN", undefined, "Failed")).toBe(true);
    });

    it("should match case-insensitively", () => {
      expect(run("IN", "Draft,Deployed", "draft")).toBe(true);
      expect(run("IN", "Draft,deployed", "DEPLOYED")).toBe(true);
    });
  });

  describe("NOT_IN", () => {
    it("should match when the value is not in the list", () => {
      expect(run("NOT_IN", "Draft,Deployed", "Failed")).toBe(true);
    });

    it("should not match when the value is in the list", () => {
      expect(run("NOT_IN", "Draft,Deployed", "Draft")).toBe(false);
    });

    it("should match when the value is undefined", () => {
      expect(run("NOT_IN", "Draft", undefined)).toBe(true);
    });

    it("should match all when the filter is empty", () => {
      expect(run("NOT_IN", undefined, "Failed")).toBe(true);
    });

    it("should match case-insensitively", () => {
      expect(run("NOT_IN", "Draft,Deployed", "failed")).toBe(true);
      expect(run("NOT_IN", "Deployed", "DEPLOYED")).toBe(false);
    });
  });

  describe("CONTAINS", () => {
    it("should match when the value contains the filter", () => {
      expect(run("CONTAINS", "raft", "Draft")).toBe(true);
    });

    it("should not match when the value does not contain the filter", () => {
      expect(run("CONTAINS", "xyz", "Draft")).toBe(false);
    });

    it("should match all when the filter is empty", () => {
      expect(run("CONTAINS", undefined, "Draft")).toBe(true);
    });

    it("should not match when the value is undefined", () => {
      expect(run("CONTAINS", "Draft", undefined)).toBe(false);
    });

    it("should match case-insensitively", () => {
      expect(run("CONTAINS", "DRAFT", "draft")).toBe(true);
      expect(run("CONTAINS", "raft", "DRA")).toBe(false);
    });
  });

  describe("DOES_NOT_CONTAIN", () => {
    it("should match when the value does not contain the filter", () => {
      expect(run("DOES_NOT_CONTAIN", "xyz", "Draft")).toBe(true);
    });

    it("should not match when the value contains the filter", () => {
      expect(run("DOES_NOT_CONTAIN", "raft", "Draft")).toBe(false);
    });

    it("should match all when the filter is empty", () => {
      expect(run("DOES_NOT_CONTAIN", undefined, "Draft")).toBe(true);
    });

    it("should match when the value is undefined", () => {
      expect(run("DOES_NOT_CONTAIN", "Draft", undefined)).toBe(true);
    });

    it("should match case-insensitively", () => {
      expect(run("DOES_NOT_CONTAIN", "raft", "DRAFT")).toBe(false);
      expect(run("DOES_NOT_CONTAIN", "xyz", "DRAFT")).toBe(true);
    });
  });

  describe("STARTS_WITH", () => {
    it("should match when the value starts with the filter", () => {
      expect(run("STARTS_WITH", "Dra", "Draft")).toBe(true);
    });

    it("should not match when the value does not start with the filter", () => {
      expect(run("STARTS_WITH", "aft", "Draft")).toBe(false);
    });

    it("should match all when the filter is empty", () => {
      expect(run("STARTS_WITH", undefined, "Draft")).toBe(true);
    });

    it("should not match when the value is undefined", () => {
      expect(run("STARTS_WITH", "Draft", undefined)).toBe(false);
    });

    it("should match case-insensitively", () => {
      expect(run("STARTS_WITH", "dra", "Draft")).toBe(true);
      expect(run("STARTS_WITH", "DRA", "raft")).toBe(false);
    });
  });

  describe("ENDS_WITH", () => {
    it("should match when the value ends with the filter", () => {
      expect(run("ENDS_WITH", "aft", "Draft")).toBe(true);
    });

    it("should not match when the value does not end with the filter", () => {
      expect(run("ENDS_WITH", "Dra", "Draft")).toBe(false);
    });

    it("should match all when the filter is empty", () => {
      expect(run("ENDS_WITH", undefined, "Draft")).toBe(true);
    });

    it("should not match when the value is undefined", () => {
      expect(run("ENDS_WITH", "Draft", undefined)).toBe(false);
    });

    it("should match case-insensitively", () => {
      expect(run("ENDS_WITH", "AFT", "Draft")).toBe(true);
      expect(run("ENDS_WITH", "DRA", "raft")).toBe(false);
    });
  });

  describe("IS_BEFORE", () => {
    it("should match when the value is before the filter", () => {
      expect(run("IS_BEFORE", "200", "100")).toBe(true);
    });

    it("should not match when the value equals the filter", () => {
      expect(run("IS_BEFORE", "200", "200")).toBe(false);
    });

    it("should not match when the value is after the filter", () => {
      expect(run("IS_BEFORE", "200", "300")).toBe(false);
    });

    it("should not match when the value is undefined", () => {
      expect(run("IS_BEFORE", "200", undefined)).toBe(false);
    });
  });

  describe("IS_AFTER", () => {
    it("should match when the value is after the filter", () => {
      expect(run("IS_AFTER", "200", "300")).toBe(true);
    });

    it("should not match when the value equals the filter", () => {
      expect(run("IS_AFTER", "200", "200")).toBe(false);
    });

    it("should not match when the value is before the filter", () => {
      expect(run("IS_AFTER", "200", "100")).toBe(false);
    });
  });

  describe("IS_WITHIN", () => {
    it("should match when the value is inside the range", () => {
      expect(run("IS_WITHIN", "100,200", "150")).toBe(true);
    });

    it("should match when the value equals the lower bound", () => {
      expect(run("IS_WITHIN", "100,200", "100")).toBe(true);
    });

    it("should match when the value equals the upper bound", () => {
      expect(run("IS_WITHIN", "100,200", "200")).toBe(true);
    });

    it("should not match when the value is below the range", () => {
      expect(run("IS_WITHIN", "100,200", "99")).toBe(false);
    });

    it("should not match when the value is above the range", () => {
      expect(run("IS_WITHIN", "100,200", "201")).toBe(false);
    });
  });

  describe("LESS_THAN", () => {
    it("should match when the value is less than the filter", () => {
      expect(run("LESS_THAN", "200", "100")).toBe(true);
    });

    it("should not match when the value is greater than the filter", () => {
      expect(run("LESS_THAN", "200", "300")).toBe(false);
    });
  });

  describe("GREATER_THAN", () => {
    it("should match when the value is greater than the filter", () => {
      expect(run("GREATER_THAN", "200", "300")).toBe(true);
    });

    it("should not match when the value is less than the filter", () => {
      expect(run("GREATER_THAN", "200", "100")).toBe(false);
    });
  });

  describe("EMPTY / NOT_EMPTY", () => {
    it("should treat undefined as empty", () => {
      expect(run("EMPTY", undefined, undefined)).toBe(true);
      expect(run("NOT_EMPTY", undefined, undefined)).toBe(false);
    });

    it("should treat whitespace-only values as empty", () => {
      expect(run("EMPTY", undefined, "   ")).toBe(true);
      expect(run("NOT_EMPTY", undefined, "   ")).toBe(false);
    });

    it("should treat a non-blank value as not empty", () => {
      expect(run("EMPTY", undefined, "value")).toBe(false);
      expect(run("NOT_EMPTY", undefined, "value")).toBe(true);
    });
  });
});
