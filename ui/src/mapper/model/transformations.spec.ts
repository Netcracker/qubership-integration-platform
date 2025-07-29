import {
  bindParameterValues,
  TransformationParameterInfo,
} from "./transformations.ts";

describe("Mapper", () => {
  describe("bindParameterValues", () => {
    it("should split parameters list by given parameters info and combine them", () => {
      const parameters: TransformationParameterInfo[] = [
        { name: "foo", minArguments: 1, maxArguments: 1 },
        { name: "bar", minArguments: 1, maxArguments: 2 },
        { name: "baz", minArguments: 1, maxArguments: 1 },
      ];
      const values = ["a", "b", "c", "d"];
      expect(bindParameterValues(parameters, values)).toEqual([
        [parameters[0], ["a"]],
        [parameters[1], ["b", "c"]],
        [parameters[2], ["d"]],
      ]);
    });
  });
});
