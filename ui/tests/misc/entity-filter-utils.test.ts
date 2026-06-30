jest.mock("../../src/api/rest/vscodeExtensionApi", () => ({
  isVsCode: false,
  VSCodeExtensionApi: class MockedVSCode {},
}));

import { Element, LibraryElement } from "../../src/api/apiTypes";
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

  it("filters elements by ELEMENT_NAME DOES_NOT_CONTAIN condition", () => {
    const filters: EntityFilterModel[] = [
      { column: "ELEMENT_NAME", condition: "DOES_NOT_CONTAIN", value: "script" },
    ];
    expect(applyEntityFiltersToElements(elements, filters)).toEqual([
      elements[0],
    ]);
  });

  it("filters elements by ELEMENT_NAME STARTS_WITH condition", () => {
    const filters: EntityFilterModel[] = [
      { column: "ELEMENT_NAME", condition: "STARTS_WITH", value: "http" },
    ];
    expect(applyEntityFiltersToElements(elements, filters)).toEqual([
      elements[0],
    ]);
  });

  it("filters elements by ELEMENT_NAME ENDS_WITH condition", () => {
    const filters: EntityFilterModel[] = [
      { column: "ELEMENT_NAME", condition: "ENDS_WITH", value: "trigger" },
    ];
    expect(applyEntityFiltersToElements(elements, filters)).toEqual([
      elements[0],
    ]);
  });

  it("filters elements by ELEMENT_TYPE IS_NOT condition", () => {
    const filters: EntityFilterModel[] = [
      { column: "ELEMENT_TYPE", condition: "IS_NOT", value: "script" },
    ];
    expect(applyEntityFiltersToElements(elements, filters)).toEqual([
      elements[0],
    ]);
  });

  it("filters elements by ELEMENT_NAME EMPTY condition", () => {
    const blankName = { ...elements[1], name: "   " };
    const filters: EntityFilterModel[] = [
      { column: "ELEMENT_NAME", condition: "EMPTY" },
    ];
    expect(applyEntityFiltersToElements([blankName], filters)).toEqual([
      blankName,
    ]);
    expect(applyEntityFiltersToElements(elements, filters)).toEqual([]);
  });

  it("filters elements by ELEMENT_NAME NOT_EMPTY condition", () => {
    const blankName = { ...elements[1], name: "   " };
    const filters: EntityFilterModel[] = [
      { column: "ELEMENT_NAME", condition: "NOT_EMPTY" },
    ];
    expect(applyEntityFiltersToElements(elements, filters)).toEqual(elements);
    expect(applyEntityFiltersToElements([blankName], filters)).toEqual([]);
  });

  it("filters elements by ELEMENT_TYPE NOT_IN condition", () => {
    const filters: EntityFilterModel[] = [
      { column: "ELEMENT_TYPE", condition: "NOT_IN", value: "script" },
    ];
    expect(applyEntityFiltersToElements(elements, filters)).toEqual([
      elements[0],
    ]);
  });

  it("returns all elements for an unknown column", () => {
    const filters: EntityFilterModel[] = [
      { column: "UNKNOWN", condition: "IS", value: "x" },
    ];
    expect(applyEntityFiltersToElements(elements, filters)).toEqual(elements);
  });

  it("returns all elements for an unknown condition", () => {
    const filters: EntityFilterModel[] = [
      { column: "ELEMENT_NAME", condition: "UNKNOWN", value: "x" },
    ];
    expect(applyEntityFiltersToElements(elements, filters)).toEqual(elements);
  });

  it("uses library element title when element name is empty", () => {
    const unnamed = { ...elements[0], name: "" };
    const libraryElements: LibraryElement[] = [
      {
        name: "http-trigger",
        title: "HTTP Trigger",
        type: "http-trigger",
      } as LibraryElement,
    ];
    const filters: EntityFilterModel[] = [
      { column: "ELEMENT_NAME", condition: "IS", value: "HTTP Trigger" },
    ];
    expect(
      applyEntityFiltersToElements([unnamed], filters, libraryElements),
    ).toEqual([unnamed]);
  });

  it("applies multiple filters together", () => {
    const filters: EntityFilterModel[] = [
      { column: "ELEMENT_NAME", condition: "CONTAINS", value: "script" },
      { column: "ELEMENT_TYPE", condition: "IS", value: "script" },
    ];
    expect(applyEntityFiltersToElements(elements, filters)).toEqual([
      elements[1],
    ]);
  });

  it("treats empty filter value as match-all for string conditions", () => {
    const filters: EntityFilterModel[] = [
      { column: "ELEMENT_NAME", condition: "CONTAINS", value: "" },
    ];
    expect(applyEntityFiltersToElements(elements, filters)).toEqual(elements);
  });

  it("treats empty IN value list as match-all", () => {
    const filters: EntityFilterModel[] = [
      { column: "ELEMENT_TYPE", condition: "IN", value: "" },
    ];
    expect(applyEntityFiltersToElements(elements, filters)).toEqual(elements);
  });
});
