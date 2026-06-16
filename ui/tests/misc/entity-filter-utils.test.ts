jest.mock("../../src/api/rest/vscodeExtensionApi", () => ({
  isVsCode: false,
  VSCodeExtensionApi: class MockedVSCode {},
}));

import { Element } from "../../src/api/apiTypes";
import { EntityFilterModel } from "../../src/components/table/filter/filterTypes";
import { applyEntityFiltersToElements } from "../../src/misc/entity-filter-utils";

describe("applyEntityFiltersToElements", () => {
  const elements: Element[] = [
    {
      id: "el-1",
      name: "HTTP Trigger",
      description: "",
      chainId: "chain-1",
      type: "http-trigger",
      properties: undefined as never,
      mandatoryChecksPassed: true,
    },
    {
      id: "el-2",
      name: "My Script",
      description: "",
      chainId: "chain-1",
      type: "script",
      properties: undefined as never,
      mandatoryChecksPassed: true,
    },
  ];

  it("returns all elements when filters array is empty", () => {
    expect(applyEntityFiltersToElements(elements, [])).toEqual(elements);
  });

  it("filters elements by ELEMENT_NAME CONTAINS condition", () => {
    const filters: EntityFilterModel[] = [
      { column: "ELEMENT_NAME", condition: "CONTAINS", value: "script" },
    ];
    expect(applyEntityFiltersToElements(elements, filters)).toEqual([
      elements[1],
    ]);
  });

  it("filters elements by ELEMENT_TYPE IN condition", () => {
    const filters: EntityFilterModel[] = [
      { column: "ELEMENT_TYPE", condition: "IN", value: "http-trigger" },
    ];
    expect(applyEntityFiltersToElements(elements, filters)).toEqual([
      elements[0],
    ]);
  });

  it("filters elements by ELEMENT_NAME IS condition", () => {
    const filters: EntityFilterModel[] = [
      { column: "ELEMENT_NAME", condition: "IS", value: "My Script" },
    ];
    expect(applyEntityFiltersToElements(elements, filters)).toEqual([
      elements[1],
    ]);
  });
});
