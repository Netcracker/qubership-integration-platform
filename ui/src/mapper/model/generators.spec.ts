import { DataType } from "./model.ts";
import { GENERATORS, getGeneratorsForType } from "./generators.ts";

describe("Mapper", () => {
  describe("getGeneratorsForType", () => {
    it("should return empty list when type is undefined or null", () => {
      ([undefined, null] as unknown as DataType[]).forEach((type: DataType) => {
        expect(
          getGeneratorsForType(type),
          `with ${type as unknown as undefined | null}`,
        ).toEqual([]);
      });
    });

    it("should return proper generators list for string type", () => {
      expect(getGeneratorsForType({ name: "string" })).toEqual(GENERATORS);
    });

    it("should return proper generators list for number type", () => {
      expect(getGeneratorsForType({ name: "number" })).toEqual([]);
    });

    it("should return proper generators list for boolean type", () => {
      expect(getGeneratorsForType({ name: "boolean" })).toEqual([]);
    });
  });
});
