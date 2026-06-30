import { Element, LibraryElement } from "../api/apiTypes";
import { EntityFilterModel } from "../components/table/filter/filterTypes";
import { getLibraryElement } from "./chain-graph-utils";

type FieldGetter = (
  element: Element,
  libraryElements?: LibraryElement[] | null,
) => string;

function matchesEntityFilterCondition(
  fieldValue: string,
  filter: EntityFilterModel,
): boolean {
  const value = fieldValue ?? "";
  const filterValue = filter.value ?? "";

  switch (filter.condition) {
    case "CONTAINS":
      return (
        !filterValue || value.toLowerCase().includes(filterValue.toLowerCase())
      );
    case "DOES_NOT_CONTAIN":
      return (
        !filterValue || !value.toLowerCase().includes(filterValue.toLowerCase())
      );
    case "STARTS_WITH":
      return (
        !filterValue ||
        value.toLowerCase().startsWith(filterValue.toLowerCase())
      );
    case "ENDS_WITH":
      return (
        !filterValue || value.toLowerCase().endsWith(filterValue.toLowerCase())
      );
    case "IS":
      return !filterValue || value.toLowerCase() === filterValue.toLowerCase();
    case "IS_NOT":
      return !filterValue || value.toLowerCase() !== filterValue.toLowerCase();
    case "EMPTY":
      return value.trim().length === 0;
    case "NOT_EMPTY":
      return value.trim().length > 0;
    case "IN": {
      const values = filterValue.split(",").filter(Boolean);
      return values.length === 0 || values.includes(value);
    }
    case "NOT_IN": {
      const values = filterValue.split(",").filter(Boolean);
      return values.length === 0 || !values.includes(value);
    }
    default:
      return true;
  }
}

const chainElementFieldGetters: Record<string, FieldGetter> = {
  ELEMENT_NAME: (element, libraryElements) => {
    const libraryElement = getLibraryElement(element, libraryElements ?? null);
    return element.name || libraryElement.title || element.type;
  },
  ELEMENT_TYPE: (element) => element.type,
};

export function applyEntityFiltersToElements(
  elements: Element[],
  filters: EntityFilterModel[],
  libraryElements?: LibraryElement[] | null,
): Element[] {
  if (filters.length === 0) {
    return elements;
  }

  return elements.filter((element) =>
    filters.every((filter) => {
      const getter = chainElementFieldGetters[filter.column];
      if (!getter) {
        return true;
      }
      const fieldValue = getter(element, libraryElements);
      return matchesEntityFilterCondition(fieldValue, filter);
    }),
  );
}
