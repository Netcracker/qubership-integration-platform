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

import { describe, it, expect } from "@jest/globals";
import { renderHook } from "@testing-library/react";
import {
  FilterCondition,
  FilterValueType,
} from "../../src/components/table/filter/filterTypes";
import type { FilterConditions } from "../../src/components/table/filter/filterTypes";

const mockShowModal = jest.fn();

jest.mock("../../src/Modals", () => ({
  useModalsContext: () => ({
    showModal: mockShowModal,
  }),
}));

const LabelsStringTableFilter: FilterConditions = {
  defaultCondition: FilterCondition.CONTAINS,
  allowedConditions: [
    FilterCondition.IS,
    FilterCondition.IS_NOT,
    FilterCondition.CONTAINS,
    FilterCondition.DOES_NOT_CONTAIN,
    FilterCondition.EMPTY,
    FilterCondition.NOT_EMPTY,
  ],
  valueType: FilterValueType.STRING,
};

jest.mock("../../src/hooks/useChainFilter", () => ({
  LabelsStringTableFilter,
}));

import {
  SERVICE_FILTER_COLUMNS,
  useServiceFilters,
} from "../../src/hooks/useServiceFilter";

describe("useServiceFilter", () => {
  it("returns empty filters array and a filterButton element", () => {
    const { result } = renderHook(() => useServiceFilters());
    expect(result.current.filters).toEqual([]);
    expect(result.current.filterButton).toBeDefined();
  });

  // The column id is the backend FilterFeature value; the old SPECIFICATION_GROUP silently matches nothing now.
  it("exposes the API group column under the renamed filter feature id", () => {
    const ids = SERVICE_FILTER_COLUMNS.map((column) => column.id);

    expect(ids).toContain("API_GROUP");
    expect(ids).not.toContain("SPECIFICATION_GROUP");
    expect(
      SERVICE_FILTER_COLUMNS.find((column) => column.id === "API_GROUP")?.name,
    ).toBe("API Group");
  });
});
