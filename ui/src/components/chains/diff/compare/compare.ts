import { Chain, Connection, Element } from "../../../../api/apiTypes.ts";
import {
  ChainPropertyChange,
  Change,
  ConnectionChange,
  ElementChange,
  ElementPropertyChange,
} from "./types.ts";
import { v4 as uuidv4 } from "uuid";
import { intersection, sortBy, sortElementsTopologically } from "./utils.ts";

export function compareChains(one: Chain, another: Chain): Change[] {
  const elementMap = buildElementMap(one, another);
  return [
    ...compareChainProperties(one, another),
    ...compareElements(one.elements, another.elements, elementMap),
    ...compareConnections(one.dependencies, another.dependencies, elementMap),
  ].sort((a, b) => a.kind.localeCompare(b.kind));
}

export function buildElementMap(
  one: Chain,
  another: Chain,
): Map<string, string> {
  const result = new Map<string, string>();
  sortElementsTopologically(one.elements, one.dependencies).forEach(
    (element) => {
      const elementsWithSameType = another.elements.filter(
        (anotherElement) =>
          anotherElement.id === element.id ||
          (anotherElement.originalId &&
            element.originalId &&
            anotherElement.originalId === element.originalId) ||
          anotherElement.type === element.type,
      );
      if (elementsWithSameType.length === 0) {
        return;
      }
      const bestFitElement = sortBy(
        elementsWithSameType,
        (e) =>
          getElementRank(
            e,
            another.dependencies,
            element,
            one.dependencies,
            result,
          ),
        (a, b) => b - a,
      )[0];
      result.set(element.id, bestFitElement.id);
      result.set(bestFitElement.id, element.id);
    },
  );
  return result;
}

export function getElementRank(
  element: Element,
  connections: Connection[],
  referenceElement: Element,
  referenceConnections: Connection[],
  elementMap: Map<string, string>,
): number {
  let rank = 0;
  if (
    element.id === referenceElement.id ||
    element.id === elementMap.get(referenceElement.id)
  ) {
    rank += 1;
  }
  if (element.originalId === referenceElement.originalId) {
    rank += 1;
  }
  if (element.type === referenceElement.type) {
    rank += 1;
  }
  if (element.name === referenceElement.name) {
    rank += 1;
  }

  const fromElements = new Set<string>(
    connections
      .filter((c) => c.to === element.id)
      .map((c) => c.from)
      .map((id) => elementMap.get(id) ?? id),
  );
  const referenceFromElements = new Set<string>(
    referenceConnections
      .filter((c) => c.to === referenceElement.id)
      .map((c) => c.from),
  );
  rank += intersection(fromElements, referenceFromElements).size;

  const toElements = new Set<string>(
    connections
      .filter((c) => c.from === element.id)
      .map((c) => c.to)
      .map((id) => elementMap.get(id) ?? id),
  );
  const referenceToElements = new Set<string>(
    referenceConnections
      .filter((c) => c.from === referenceElement.id)
      .map((c) => c.to),
  );
  rank += intersection(toElements, referenceToElements).size;

  if (
    (!element.parentElementId && !referenceElement.parentElementId) ||
    (element.parentElementId &&
      referenceElement.parentElementId &&
      element.parentElementId === elementMap.get(element.parentElementId))
  ) {
    rank += 1;
  }

  if (
    (!element.swimlaneId && !referenceElement.swimlaneId) ||
    (element.swimlaneId &&
      referenceElement.swimlaneId &&
      element.swimlaneId === elementMap.get(element.swimlaneId))
  ) {
    rank += 1;
  }

  return rank;
}

export function compareElements(
  oneElements: Element[],
  anotherElements: Element[],
  elementMap: Map<string, string>,
): (ElementChange | ElementPropertyChange)[] {
  return [
    ...oneElements.flatMap(
      (element): (ElementChange | ElementPropertyChange)[] => {
        if (elementMap.has(element.id)) {
          const id = elementMap.get(element.id);
          const anotherElement = anotherElements.find((e) => e.id === id);
          return anotherElement
            ? compareElementProperties(element, anotherElement, elementMap)
            : [{ id: uuidv4(), kind: "element" as const, one: element }];
        } else {
          return [
            {
              id: uuidv4(),
              kind: "element" as const,
              one: element,
            },
          ];
        }
      },
    ),
    ...anotherElements
      .filter((element) => !elementMap.has(element.id))
      .map((element) => ({
        id: uuidv4(),
        kind: "element" as const,
        another: element,
      })),
  ];
}

export function compareConnections(
  oneConnections: Connection[],
  anotherConnections: Connection[],
  elementMap: Map<string, string>,
): ConnectionChange[] {
  return [
    ...oneConnections
      .filter(
        (connection) =>
          !connectionExists(connection, anotherConnections, elementMap),
      )
      .map((connection) => ({
        id: uuidv4(),
        kind: "connection" as const,
        one: connection,
      })),
    ...anotherConnections
      .filter(
        (connection) =>
          !connectionExists(connection, oneConnections, elementMap),
      )
      .map((connection) => ({
        id: uuidv4(),
        kind: "connection" as const,
        another: connection,
      })),
  ];
}

export function connectionExists(
  connection: Connection,
  connections: Connection[],
  elementMap: Map<string, string>,
): boolean {
  const fromId = elementMap.get(connection.from);
  const toId = elementMap.get(connection.to);
  return (
    !!fromId &&
    !!toId &&
    connections.some((c) => c.from === fromId && c.to === toId)
  );
}

export function compareChainProperties(
  one: Chain,
  another: Chain,
): ChainPropertyChange[] {
  const keys: (keyof Chain)[] = [
    "description",
    "labels",
    "deployAction",
    "businessDescription",
    "assumptions",
    "outOfScope",
    "overriddenByChainId",
    "overriddenByChainName",
    "overridesChainId",
    "overridesChainName",
  ];
  return keys
    .filter((key) => {
      if (key === "labels") {
        const v1 = JSON.stringify(
          [...one[key]].sort((a, b) => a.name.localeCompare(b.name)),
        );
        const v2 = JSON.stringify(
          [...another[key]].sort((a, b) => a.name.localeCompare(b.name)),
        );
        return v1 !== v2;
      } else if (
        [
          "description",
          "businessDescription",
          "assumptions",
          "outOfScope",
        ].includes(key)
      ) {
        return (one[key] ?? "") !== (another[key] ?? "");
      } else {
        return one[key] !== another[key];
      }
    })
    .map((key) => ({
      id: uuidv4(),
      kind: "chain-property",
      one:
        one[key] !== undefined && one[key] !== null
          ? {
              entityId: one.id,
              name: key,
              value: one[key],
            }
          : undefined,
      another:
        another[key] !== undefined && another[key] !== null
          ? {
              entityId: another.id,
              name: key,
              value: another[key],
            }
          : undefined,
    }));
}

export function compareElementProperties(
  one: Element,
  another: Element,
  elementMap: Map<string, string>,
): ElementPropertyChange[] {
  const propertyNames = new Set<string>();
  [one.properties, another.properties]
    .flatMap((obj) => Object.keys(obj))
    .forEach((name) => propertyNames.add(name));
  return [
    ...(["type", "name", "description"] as (keyof Element)[])
      .filter((key) => one[key] !== another[key])
      .map((key) => getElementPropertyChange(one, another, key)),
    ...(["parentElementId", "swimlaneId"] as (keyof Element)[])
      .filter((key) => {
        return (
          !!one[key] !== !!another[key] ||
          (one[key] &&
            another[key] &&
            one[key] !==
              elementMap.get((another[key] as string | undefined) ?? ""))
        );
      })
      .map((key) => getElementPropertyChange(one, another, key)),
    ...Array.from(propertyNames.keys())
      .filter((name) => {
        const v1 = JSON.stringify(one.properties[name]);
        const v2 = JSON.stringify(another.properties[name]);
        return v1 !== v2;
      })
      .map((name) => ({
        id: uuidv4(),
        kind: "element-property" as const,
        one:
          one.properties[name] !== undefined && one.properties[name] !== null
            ? {
                entityId: one.id,
                name,
                value: one.properties[name],
              }
            : undefined,
        another:
          another.properties[name] !== undefined &&
          another.properties[name] !== null
            ? {
                entityId: another.id,
                name,
                value: another.properties[name],
              }
            : undefined,
      })),
  ];
}

export function getElementPropertyChange(
  one: Element,
  another: Element,
  key: keyof Element,
): ElementPropertyChange {
  return {
    id: uuidv4(),
    kind: "element-property" as const,
    one:
      one[key] !== undefined && one[key] !== null
        ? {
            entityId: one.id,
            name: key,
            value: one[key],
          }
        : undefined,
    another:
      another[key] !== undefined && another[key] !== null
        ? {
            entityId: another.id,
            name: key,
            value: another[key],
          }
        : undefined,
  };
}
