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
} from "../../src/components/table/filter/filter";
import type { FilterConditions } from "../../src/components/table/filter/filter";

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

import { useServiceFilters } from "../../src/hooks/useServiceFilter";

describe("useServiceFilter", () => {
  it("returns empty filters array and a filterButton element", () => {
    const { result } = renderHook(() => useServiceFilters());
    expect(result.current.filters).toEqual([]);
    expect(result.current.filterButton).toBeDefined();
  });
});
