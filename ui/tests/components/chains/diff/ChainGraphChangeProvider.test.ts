import { describe, expect, it } from "@jest/globals";
import type { Chain, Element } from "../../../../src/api/apiTypes";
import type { Change } from "../../../../src/components/chains/diff/compare/types";
import {
  createNodeStateMap,
  NodeState,
} from "../../../../src/components/chains/diff/ChainGraphChangeProvider";

function element(id: string): Element {
  return { id, name: id, type: "sender", properties: {} } as unknown as Element;
}

function chainOf(...elements: Element[]): Chain {
  return { id: "chain", elements, dependencies: [] } as unknown as Chain;
}

function elementChange(side: "one" | "another", e: Element): Change {
  return { id: `change-${e.id}`, kind: "element", [side]: e };
}

function propertyChange(
  entityIds: { one?: string; another?: string },
  name = "prop",
): Change {
  return {
    id: `change-${name}-${entityIds.one ?? entityIds.another}`,
    kind: "element-property",
    one: entityIds.one
      ? { entityId: entityIds.one, name, value: "v1" }
      : undefined,
    another: entityIds.another
      ? { entityId: entityIds.another, name, value: "v2" }
      : undefined,
  };
}

describe("createNodeStateMap", () => {
  it("should mark element NOT_CHANGED when no change matches and the element is mapped", () => {
    const a = element("a");
    const elementMap = new Map([
      ["a", "b"],
      ["b", "a"],
    ]);

    const states = createNodeStateMap(chainOf(a), "one", [], elementMap);

    expect(states.get("a")).toBe(NodeState.NOT_CHANGED);
  });

  it("should mark element CHANGED when a two-sided property change matches", () => {
    const a = element("a");
    const changes = [propertyChange({ one: "a", another: "b" })];
    const elementMap = new Map([
      ["a", "b"],
      ["b", "a"],
    ]);

    const states = createNodeStateMap(chainOf(a), "one", changes, elementMap);

    expect(states.get("a")).toBe(NodeState.CHANGED);
  });

  it("should mark element REMOVED on side one when the element has no counterpart", () => {
    const a = element("a");
    const changes = [elementChange("one", a)];

    const states = createNodeStateMap(chainOf(a), "one", changes, new Map());

    expect(states.get("a")).toBe(NodeState.REMOVED);
  });

  it("should mark element CREATED on side another when the element has no counterpart", () => {
    const b = element("b");
    const changes = [elementChange("another", b)];

    const states = createNodeStateMap(
      chainOf(b),
      "another",
      changes,
      new Map(),
    );

    expect(states.get("b")).toBe(NodeState.CREATED);
  });

  it("should mark element REMOVED on side one when its only property change is one-sided", () => {
    const a = element("a");
    const changes = [propertyChange({ one: "a" })];
    const elementMap = new Map([
      ["a", "b"],
      ["b", "a"],
    ]);

    const states = createNodeStateMap(chainOf(a), "one", changes, elementMap);

    expect(states.get("a")).toBe(NodeState.REMOVED);
  });

  it("should mark element CREATED on side another when its only property change is one-sided", () => {
    const b = element("b");
    const changes = [propertyChange({ another: "b" })];
    const elementMap = new Map([
      ["a", "b"],
      ["b", "a"],
    ]);

    const states = createNodeStateMap(
      chainOf(b),
      "another",
      changes,
      elementMap,
    );

    expect(states.get("b")).toBe(NodeState.CREATED);
  });

  it("should mark element CHANGED when both one-sided and two-sided property changes match", () => {
    const a = element("a");
    const changes = [
      propertyChange({ one: "a" }, "removed-prop"),
      propertyChange({ one: "a", another: "b" }, "modified-prop"),
    ];
    const elementMap = new Map([
      ["a", "b"],
      ["b", "a"],
    ]);

    const states = createNodeStateMap(chainOf(a), "one", changes, elementMap);

    expect(states.get("a")).toBe(NodeState.CHANGED);
  });

  it("should fall back to the one-sided state when an unmapped element has no matching change", () => {
    const a = element("a");
    const b = element("b");

    const oneStates = createNodeStateMap(chainOf(a), "one", [], new Map());
    const anotherStates = createNodeStateMap(
      chainOf(b),
      "another",
      [],
      new Map(),
    );

    expect(oneStates.get("a")).toBe(NodeState.REMOVED);
    expect(anotherStates.get("b")).toBe(NodeState.CREATED);
  });

  it("should classify nested children independently of their parents", () => {
    const child = element("child");
    const parent = { ...element("parent"), children: [child] };
    const changes = [elementChange("one", child)];
    const elementMap = new Map([
      ["parent", "parent-2"],
      ["parent-2", "parent"],
    ]);

    const states = createNodeStateMap(
      chainOf(parent),
      "one",
      changes,
      elementMap,
    );

    expect(states.get("parent")).toBe(NodeState.NOT_CHANGED);
    expect(states.get("child")).toBe(NodeState.REMOVED);
  });
});
