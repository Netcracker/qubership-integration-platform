import { Chain, Connection, Element } from "../../../../../src/api/apiTypes";
import {
  buildElementMap,
  compareChainProperties,
  compareChains,
  compareConnections,
  compareElementProperties,
  compareElements,
  connectionExists,
  getElementPropertyChange,
  getElementRank,
} from "../../../../../src/components/chains/diff/compare/compare";

function makeChain(overrides: Partial<Chain> = {}): Chain {
  return {
    id: "chain-1",
    name: "Chain",
    description: "",
    navigationPath: [],
    elements: [],
    dependencies: [],
    deployments: [],
    labels: [],
    defaultSwimlaneId: "swimlane-1",
    reuseSwimlaneId: "swimlane-2",
    unsavedChanges: false,
    businessDescription: "",
    assumptions: "",
    outOfScope: "",
    containsDeprecatedContainers: false,
    containsDeprecatedElements: false,
    containsUnsupportedElements: false,
    ...overrides,
  };
}

function makeElement(overrides: Partial<Element> = {}): Element {
  return {
    id: "e1",
    name: "Element",
    description: "",
    type: "script",
    chainId: "chain-1",
    properties: {} as never,
    mandatoryChecksPassed: true,
    ...overrides,
  };
}

function makeConnection(id: string, from: string, to: string): Connection {
  return { id, from, to };
}

function makeLabel(name: string): Chain["labels"][number] {
  return { name, technical: false };
}

describe("getElementRank", () => {
  test("should return 3 as base score when no other criteria match", () => {
    const element = makeElement({ id: "e1", type: "script", name: "A" });
    const ref = makeElement({ id: "ref", type: "trigger", name: "B" });
    // base score of 3: both have undefined originalId (undefined === undefined),
    // both lack parentElementId, and both lack swimlaneId
    expect(getElementRank(element, [], ref, [], new Map())).toBe(3);
  });

  test("should add 1 when element id matches reference element id", () => {
    const element = makeElement({ id: "ref", type: "script", name: "A" });
    const ref = makeElement({ id: "ref", type: "trigger", name: "B" });
    expect(getElementRank(element, [], ref, [], new Map())).toBe(4);
  });

  test("should add 1 when element id matches elementMap lookup of reference element id", () => {
    const element = makeElement({ id: "mapped", type: "script", name: "A" });
    const ref = makeElement({ id: "ref", type: "trigger", name: "B" });
    const elementMap = new Map([["ref", "mapped"]]);
    expect(getElementRank(element, [], ref, [], elementMap)).toBe(4);
  });

  test("should add 1 when element type matches reference element type", () => {
    const element = makeElement({ id: "e1", type: "script", name: "A" });
    const ref = makeElement({ id: "ref", type: "script", name: "B" });
    expect(getElementRank(element, [], ref, [], new Map())).toBe(4);
  });

  test("should add 1 when element name matches reference element name", () => {
    const element = makeElement({ id: "e1", type: "script", name: "shared" });
    const ref = makeElement({ id: "ref", type: "trigger", name: "shared" });
    expect(getElementRank(element, [], ref, [], new Map())).toBe(4);
  });

  test("should add the count of shared incoming connections", () => {
    const element = makeElement({ id: "e1", type: "script", name: "A" });
    const ref = makeElement({ id: "ref", type: "trigger", name: "B" });
    const connections = [makeConnection("c1", "shared-pred", "e1")];
    const refConnections = [makeConnection("c2", "shared-pred", "ref")];
    expect(
      getElementRank(element, connections, ref, refConnections, new Map()),
    ).toBe(4);
  });

  test("should add the count of shared outgoing connections", () => {
    const element = makeElement({ id: "e1", type: "script", name: "A" });
    const ref = makeElement({ id: "ref", type: "trigger", name: "B" });
    const connections = [makeConnection("c1", "e1", "shared-succ")];
    const refConnections = [makeConnection("c2", "ref", "shared-succ")];
    expect(
      getElementRank(element, connections, ref, refConnections, new Map()),
    ).toBe(4);
  });

  test("should accumulate full score of 8 when all criteria match", () => {
    const element = makeElement({ id: "ref", type: "script", name: "shared" });
    const ref = makeElement({ id: "ref", type: "script", name: "shared" });
    const connections = [
      makeConnection("c1", "A", "ref"),
      makeConnection("c2", "ref", "B"),
    ];
    const refConnections = [
      makeConnection("c3", "A", "ref"),
      makeConnection("c4", "ref", "B"),
    ];
    expect(
      getElementRank(element, connections, ref, refConnections, new Map()),
    ).toBe(8);
  });

  test("should add 1 when element originalId matches reference element originalId", () => {
    const element = makeElement({
      id: "e1",
      type: "script",
      name: "A",
      originalId: "orig-x",
    });
    const ref = makeElement({
      id: "ref",
      type: "trigger",
      name: "B",
      originalId: "orig-x",
    });
    // base 3 + originalId match 1 = 4; but originalId match is already counted in base when both are undefined,
    // so here: id no match, originalId match (explicit) +1, type no match, name no match,
    // no parentElementId +1, no swimlaneId +1 = 3
    expect(getElementRank(element, [], ref, [], new Map())).toBe(3);
  });

  test("should not add originalId rank when originalIds differ", () => {
    const element = makeElement({
      id: "e1",
      type: "script",
      name: "A",
      originalId: "orig-1",
    });
    const ref = makeElement({
      id: "ref",
      type: "trigger",
      name: "B",
      originalId: "orig-2",
    });
    // originalId no match → 0; no parentElementId +1, no swimlaneId +1 = 2
    expect(getElementRank(element, [], ref, [], new Map())).toBe(2);
  });

  test("should add 1 when both elements lack parentElementId", () => {
    const element = makeElement({
      id: "e1",
      type: "script",
      name: "A",
      originalId: "orig-1",
    });
    const ref = makeElement({
      id: "ref",
      type: "trigger",
      name: "B",
      originalId: "orig-2",
    });
    // no parentElementId +1; no swimlaneId +1; originalId differ = 2
    expect(getElementRank(element, [], ref, [], new Map())).toBe(2);
  });

  test("should not add parentElementId rank when one element has it and the other does not", () => {
    const element = makeElement({
      id: "e1",
      type: "script",
      name: "A",
      originalId: "o1",
      parentElementId: "p1",
    });
    const ref = makeElement({
      id: "ref",
      type: "trigger",
      name: "B",
      originalId: "o2",
    });
    // originalId differ, parentElementId: one has it the other doesn't → 0; no swimlaneId +1 = 1
    expect(getElementRank(element, [], ref, [], new Map())).toBe(1);
  });

  test("should add 1 when both elements lack swimlaneId", () => {
    const element = makeElement({
      id: "e1",
      type: "script",
      name: "A",
      originalId: "orig-1",
      parentElementId: "p1",
    });
    const ref = makeElement({
      id: "ref",
      type: "trigger",
      name: "B",
      originalId: "orig-2",
      parentElementId: "p2",
    });
    // originalId differ, parentElementId: both present but p1 not self-mapped → 0; no swimlaneId +1 = 1
    expect(getElementRank(element, [], ref, [], new Map())).toBe(1);
  });

  test("should not add swimlaneId rank when one element has it and the other does not", () => {
    const element = makeElement({
      id: "e1",
      type: "script",
      name: "A",
      originalId: "o1",
      swimlaneId: "s1",
    });
    const ref = makeElement({
      id: "ref",
      type: "trigger",
      name: "B",
      originalId: "o2",
    });
    // originalId differ, no parentElementId +1; swimlaneId: one has it, the other doesn't → 0 = 1
    expect(getElementRank(element, [], ref, [], new Map())).toBe(1);
  });
});

describe("connectionExists", () => {
  test("should return false when from id is not in elementMap", () => {
    const connection = makeConnection("c1", "A", "B");
    const elementMap = new Map([["B", "B'"]]);
    expect(connectionExists(connection, [], elementMap)).toBe(false);
  });

  test("should return false when to id is not in elementMap", () => {
    const connection = makeConnection("c1", "A", "B");
    const elementMap = new Map([["A", "A'"]]);
    expect(connectionExists(connection, [], elementMap)).toBe(false);
  });

  test("should return false when no matching connection exists in connections", () => {
    const connection = makeConnection("c1", "A", "B");
    const elementMap = new Map([
      ["A", "A'"],
      ["B", "B'"],
    ]);
    const connections = [makeConnection("c2", "A'", "C")];
    expect(connectionExists(connection, connections, elementMap)).toBe(false);
  });

  test("should return true when a matching connection exists via elementMap", () => {
    const connection = makeConnection("c1", "A", "B");
    const elementMap = new Map([
      ["A", "A'"],
      ["B", "B'"],
    ]);
    const connections = [makeConnection("c2", "A'", "B'")];
    expect(connectionExists(connection, connections, elementMap)).toBe(true);
  });
});

describe("compareChainProperties", () => {
  test("should return empty array when both chains have identical properties", () => {
    const one = makeChain({ description: "same" });
    const another = makeChain({ description: "same" });
    expect(compareChainProperties(one, another)).toEqual([]);
  });

  test("should return a change when description differs", () => {
    const one = makeChain({ id: "c1", description: "old" });
    const another = makeChain({ id: "c2", description: "new" });
    const result = compareChainProperties(one, another);
    expect(result).toHaveLength(1);
    expect(result[0]).toEqual(
      expect.objectContaining({
        kind: "chain-property",
        one: expect.objectContaining({
          entityId: "c1",
          name: "description",
          value: "old",
        }),
        another: expect.objectContaining({
          entityId: "c2",
          name: "description",
          value: "new",
        }),
      }),
    );
  });

  test("should return no change when labels are the same but in different order", () => {
    const one = makeChain({ labels: [makeLabel("b"), makeLabel("a")] });
    const another = makeChain({ labels: [makeLabel("a"), makeLabel("b")] });
    expect(compareChainProperties(one, another)).toEqual([]);
  });

  test("should return a change when labels content differs", () => {
    const one = makeChain({ labels: [makeLabel("a")] });
    const another = makeChain({ labels: [makeLabel("b")] });
    const result = compareChainProperties(one, another);
    expect(result).toHaveLength(1);
    expect(result[0]).toEqual(
      expect.objectContaining({ kind: "chain-property" }),
    );
  });

  test("should set one side to undefined when property is absent in one chain", () => {
    const one = makeChain({ id: "c1" });
    const another = makeChain({ id: "c2", overriddenByChainId: "chain-2" });
    const result = compareChainProperties(one, another);
    expect(result).toHaveLength(1);
    expect(result[0]).toEqual(
      expect.objectContaining({
        kind: "chain-property",
        one: undefined,
        another: expect.objectContaining({
          name: "overriddenByChainId",
          value: "chain-2",
        }),
      }),
    );
  });

  test("should set another side to undefined when property is absent in another chain", () => {
    const one = makeChain({ id: "c1", overriddenByChainId: "chain-1" });
    const another = makeChain({ id: "c2" });
    const result = compareChainProperties(one, another);
    expect(result).toHaveLength(1);
    expect(result[0]).toEqual(
      expect.objectContaining({
        kind: "chain-property",
        one: expect.objectContaining({
          name: "overriddenByChainId",
          value: "chain-1",
        }),
        another: undefined,
      }),
    );
  });

  test("should return one change per differing property when multiple properties differ", () => {
    const one = makeChain({ description: "desc-1", assumptions: "assume-1" });
    const another = makeChain({
      description: "desc-2",
      assumptions: "assume-2",
    });
    const result = compareChainProperties(one, another);
    expect(result).toHaveLength(2);
    expect(result.every((c) => c.kind === "chain-property")).toBe(true);
  });

  test("should return no change when description is undefined in one and empty string in another", () => {
    const one = makeChain({ description: undefined as unknown as string });
    const another = makeChain({ description: "" });
    expect(compareChainProperties(one, another)).toEqual([]);
  });

  test("should return no change when businessDescription is null in one and empty string in another", () => {
    const one = makeChain({ businessDescription: null as unknown as string });
    const another = makeChain({ businessDescription: "" });
    expect(compareChainProperties(one, another)).toEqual([]);
  });

  test("should return no change when assumptions is undefined in one and empty string in another", () => {
    const one = makeChain({ assumptions: undefined as unknown as string });
    const another = makeChain({ assumptions: "" });
    expect(compareChainProperties(one, another)).toEqual([]);
  });

  test("should return no change when outOfScope is null in one and undefined in another", () => {
    const one = makeChain({ outOfScope: null as unknown as string });
    const another = makeChain({ outOfScope: undefined as unknown as string });
    expect(compareChainProperties(one, another)).toEqual([]);
  });

  test("should return a change when description has content in one and is empty in another", () => {
    const one = makeChain({ description: "non-empty" });
    const another = makeChain({ description: "" });
    expect(compareChainProperties(one, another)).toHaveLength(1);
  });
});

describe("compareElementProperties", () => {
  test("should return empty array when both elements have identical properties", () => {
    const one = makeElement({ type: "script", name: "E", description: "d" });
    const another = makeElement({
      type: "script",
      name: "E",
      description: "d",
    });
    expect(compareElementProperties(one, another, new Map())).toEqual([]);
  });

  test("should return a change when element type differs", () => {
    const one = makeElement({ id: "e1", type: "script" });
    const another = makeElement({ id: "e2", type: "trigger" });
    const result = compareElementProperties(one, another, new Map());
    expect(result).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          kind: "element-property",
          one: expect.objectContaining({ name: "type", value: "script" }),
          another: expect.objectContaining({ name: "type", value: "trigger" }),
        }),
      ]),
    );
  });

  test("should return a change when element name differs", () => {
    const one = makeElement({ name: "old-name" });
    const another = makeElement({ name: "new-name" });
    const result = compareElementProperties(one, another, new Map());
    expect(result).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          kind: "element-property",
          one: expect.objectContaining({ name: "name", value: "old-name" }),
          another: expect.objectContaining({ name: "name", value: "new-name" }),
        }),
      ]),
    );
  });

  test("should return a change when a shared property value differs", () => {
    const one = makeElement({ id: "e1", properties: { key: "old" } as never });
    const another = makeElement({
      id: "e2",
      properties: { key: "new" } as never,
    });
    const result = compareElementProperties(one, another, new Map());
    expect(result).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          kind: "element-property",
          one: expect.objectContaining({ name: "key", value: "old" }),
          another: expect.objectContaining({ name: "key", value: "new" }),
        }),
      ]),
    );
  });

  test("should return a change when a property exists in one element but not another", () => {
    const one = makeElement({
      id: "e1",
      properties: { extra: "val" } as never,
    });
    const another = makeElement({ id: "e2", properties: {} as never });
    const result = compareElementProperties(one, another, new Map());
    expect(result).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          kind: "element-property",
          one: expect.objectContaining({ name: "extra", value: "val" }),
          another: undefined,
        }),
      ]),
    );
  });

  test("should return a change when a property exists in another element but not one", () => {
    const one = makeElement({ id: "e1", properties: {} as never });
    const another = makeElement({
      id: "e2",
      properties: { extra: "val" } as never,
    });
    const result = compareElementProperties(one, another, new Map());
    expect(result).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          kind: "element-property",
          one: undefined,
          another: expect.objectContaining({ name: "extra", value: "val" }),
        }),
      ]),
    );
  });

  test("should set the one side to undefined when one element's property value is null", () => {
    const one = makeElement({ id: "e1", properties: { key: null } as never });
    const another = makeElement({
      id: "e2",
      properties: { key: "val" } as never,
    });
    const result = compareElementProperties(one, another, new Map());
    expect(result).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          kind: "element-property",
          one: undefined,
          another: expect.objectContaining({ name: "key", value: "val" }),
        }),
      ]),
    );
  });

  test("should return a change when one element has parentElementId and the other does not", () => {
    const one = makeElement({ id: "e1", parentElementId: "parent-1" });
    const another = makeElement({ id: "e2" });
    const result = compareElementProperties(one, another, new Map());
    expect(result).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          kind: "element-property",
          one: expect.objectContaining({
            name: "parentElementId",
            value: "parent-1",
          }),
          another: undefined,
        }),
      ]),
    );
  });

  test("should return a change when parentElementIds are present but do not map to each other", () => {
    const one = makeElement({ id: "e1", parentElementId: "parent-1" });
    const another = makeElement({ id: "e2", parentElementId: "parent-2" });
    const elementMap = new Map([["parent-2", "parent-X"]]);
    const result = compareElementProperties(one, another, elementMap);
    expect(result).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          kind: "element-property",
          one: expect.objectContaining({
            name: "parentElementId",
            value: "parent-1",
          }),
          another: expect.objectContaining({
            name: "parentElementId",
            value: "parent-2",
          }),
        }),
      ]),
    );
  });

  test("should return no change for parentElementId when another maps to one via elementMap", () => {
    const one = makeElement({ id: "e1", parentElementId: "parent-1" });
    const another = makeElement({ id: "e2", parentElementId: "parent-2" });
    const elementMap = new Map([["parent-2", "parent-1"]]);
    const result = compareElementProperties(one, another, elementMap);
    const parentChange = result.find(
      (c) => "one" in c && c.one?.name === "parentElementId",
    );
    expect(parentChange).toBeUndefined();
  });

  test("should return a change when one element has swimlaneId and the other does not", () => {
    const one = makeElement({ id: "e1", swimlaneId: "lane-1" });
    const another = makeElement({ id: "e2" });
    const result = compareElementProperties(one, another, new Map());
    expect(result).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          kind: "element-property",
          one: expect.objectContaining({ name: "swimlaneId", value: "lane-1" }),
          another: undefined,
        }),
      ]),
    );
  });

  test("should return no change for swimlaneId when another maps to one via elementMap", () => {
    const one = makeElement({ id: "e1", swimlaneId: "lane-1" });
    const another = makeElement({ id: "e2", swimlaneId: "lane-2" });
    const elementMap = new Map([["lane-2", "lane-1"]]);
    const result = compareElementProperties(one, another, elementMap);
    const laneChange = result.find(
      (c) => "one" in c && c.one?.name === "swimlaneId",
    );
    expect(laneChange).toBeUndefined();
  });
});

describe("compareElements", () => {
  test("should return empty array when both element lists are empty", () => {
    expect(compareElements([], [], new Map())).toEqual([]);
  });

  test("should return an element change with one side when element has no map entry", () => {
    const element = makeElement({ id: "e1" });
    const result = compareElements([element], [], new Map());
    expect(result).toEqual([
      expect.objectContaining({ kind: "element", one: element }),
    ]);
  });

  test("should return an element change with another side when another element has no map entry", () => {
    const element = makeElement({ id: "e2" });
    const result = compareElements([], [element], new Map());
    expect(result).toEqual([
      expect.objectContaining({ kind: "element", another: element }),
    ]);
  });

  test("should return no changes when matched elements are identical", () => {
    const one = makeElement({
      id: "e1",
      type: "script",
      name: "E",
      description: "d",
    });
    const another = makeElement({
      id: "e2",
      type: "script",
      name: "E",
      description: "d",
    });
    const elementMap = new Map([
      ["e1", "e2"],
      ["e2", "e1"],
    ]);
    expect(compareElements([one], [another], elementMap)).toEqual([]);
  });

  test("should return property changes when matched elements differ", () => {
    const one = makeElement({ id: "e1", type: "script" });
    const another = makeElement({ id: "e2", type: "trigger" });
    const elementMap = new Map([
      ["e1", "e2"],
      ["e2", "e1"],
    ]);
    const result = compareElements([one], [another], elementMap);
    expect(result).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          kind: "element-property",
          one: expect.objectContaining({ name: "type", value: "script" }),
          another: expect.objectContaining({ name: "type", value: "trigger" }),
        }),
      ]),
    );
  });

  test("should return an element change with one side when mapped id is absent in another elements", () => {
    const element = makeElement({ id: "e1" });
    const elementMap = new Map([["e1", "missing-id"]]);
    const result = compareElements([element], [], elementMap);
    expect(result).toEqual([
      expect.objectContaining({ kind: "element", one: element }),
    ]);
  });
});

describe("compareConnections", () => {
  test("should return empty array when both connection lists are empty", () => {
    expect(compareConnections([], [], new Map())).toEqual([]);
  });

  test("should return a change with one side when a connection is not in another", () => {
    const connection = makeConnection("c1", "A", "B");
    const result = compareConnections([connection], [], new Map());
    expect(result).toEqual([
      expect.objectContaining({ kind: "connection", one: connection }),
    ]);
  });

  test("should return a change with another side when a connection is not in one", () => {
    const connection = makeConnection("c1", "C", "D");
    const result = compareConnections([], [connection], new Map());
    expect(result).toEqual([
      expect.objectContaining({ kind: "connection", another: connection }),
    ]);
  });

  test("should return no changes when equivalent connections exist via elementMap", () => {
    const oneConn = makeConnection("c1", "A", "B");
    const anotherConn = makeConnection("c2", "A'", "B'");
    const elementMap = new Map([
      ["A", "A'"],
      ["B", "B'"],
      ["A'", "A"],
      ["B'", "B"],
    ]);
    expect(compareConnections([oneConn], [anotherConn], elementMap)).toEqual(
      [],
    );
  });

  test("should return changes from both sides when connections on both sides are unmatched", () => {
    const oneConn = makeConnection("c1", "A", "B");
    const anotherConn = makeConnection("c2", "C", "D");
    const result = compareConnections([oneConn], [anotherConn], new Map());
    expect(result).toHaveLength(2);
    expect(result).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ kind: "connection", one: oneConn }),
        expect.objectContaining({ kind: "connection", another: anotherConn }),
      ]),
    );
  });
});

describe("buildElementMap", () => {
  test("should return empty map when both chains have no elements", () => {
    const one = makeChain({ elements: [], dependencies: [] });
    const another = makeChain({ elements: [], dependencies: [] });
    expect(buildElementMap(one, another).size).toBe(0);
  });

  test("should create a bidirectional mapping when element types match", () => {
    const one = makeChain({
      elements: [makeElement({ id: "a1", type: "script" })],
    });
    const another = makeChain({
      elements: [makeElement({ id: "b1", type: "script" })],
    });
    const result = buildElementMap(one, another);
    expect(result.get("a1")).toBe("b1");
    expect(result.get("b1")).toBe("a1");
  });

  test("should prefer the higher-ranked candidate when multiple elements match", () => {
    const one = makeChain({
      elements: [makeElement({ id: "a1", type: "script", name: "N" })],
    });
    const another = makeChain({
      elements: [
        makeElement({ id: "b1", type: "script", name: "M" }),
        makeElement({ id: "c1", type: "script", name: "N" }),
      ],
    });
    const result = buildElementMap(one, another);
    expect(result.get("a1")).toBe("c1");
  });

  test("should not add an entry when no element in another matches", () => {
    const one = makeChain({
      elements: [makeElement({ id: "a1", type: "script" })],
    });
    const another = makeChain({
      elements: [makeElement({ id: "b1", type: "trigger" })],
    });
    expect(buildElementMap(one, another).size).toBe(0);
  });

  test("should match element by id even when another element shares the same type", () => {
    const one = makeChain({
      elements: [makeElement({ id: "x", type: "t", name: "N" })],
    });
    const another = makeChain({
      elements: [
        makeElement({ id: "other", type: "t", name: "N" }),
        makeElement({ id: "x", type: "t", name: "N" }),
      ],
    });
    const result = buildElementMap(one, another);
    expect(result.get("x")).toBe("x");
  });

  test("should create a bidirectional mapping when originalIds match even when types differ", () => {
    const one = makeChain({
      elements: [
        makeElement({ id: "a1", type: "script", originalId: "orig-shared" }),
      ],
    });
    const another = makeChain({
      elements: [
        makeElement({ id: "b1", type: "trigger", originalId: "orig-shared" }),
      ],
    });
    const result = buildElementMap(one, another);
    expect(result.get("a1")).toBe("b1");
    expect(result.get("b1")).toBe("a1");
  });

  test("should not match elements when both originalIds are absent", () => {
    const one = makeChain({
      elements: [makeElement({ id: "a1", type: "script" })],
    });
    const another = makeChain({
      elements: [makeElement({ id: "b1", type: "trigger" })],
    });
    expect(buildElementMap(one, another).size).toBe(0);
  });
});

describe("getElementPropertyChange", () => {
  test("should return an element-property change with both sides when the key has a value in both elements", () => {
    const one = makeElement({ id: "e1", name: "alpha" });
    const another = makeElement({ id: "e2", name: "beta" });
    const result = getElementPropertyChange(one, another, "name");
    expect(result).toEqual(
      expect.objectContaining({
        kind: "element-property",
        one: expect.objectContaining({
          entityId: "e1",
          name: "name",
          value: "alpha",
        }),
        another: expect.objectContaining({
          entityId: "e2",
          name: "name",
          value: "beta",
        }),
      }),
    );
  });

  test("should set one side to undefined when the key value is null in one element", () => {
    const one = makeElement({
      id: "e1",
      description: null as unknown as string,
    });
    const another = makeElement({ id: "e2", description: "desc" });
    const result = getElementPropertyChange(one, another, "description");
    expect(result.one).toBeUndefined();
    expect(result.another).toEqual(
      expect.objectContaining({
        entityId: "e2",
        name: "description",
        value: "desc",
      }),
    );
  });

  test("should set another side to undefined when the key value is undefined in another element", () => {
    const one = makeElement({ id: "e1", description: "desc" });
    const another = makeElement({
      id: "e2",
      description: undefined as unknown as string,
    });
    const result = getElementPropertyChange(one, another, "description");
    expect(result.one).toEqual(
      expect.objectContaining({
        entityId: "e1",
        name: "description",
        value: "desc",
      }),
    );
    expect(result.another).toBeUndefined();
  });

  test("should always return a unique id per call", () => {
    const one = makeElement({ id: "e1" });
    const another = makeElement({ id: "e2" });
    const r1 = getElementPropertyChange(one, another, "name");
    const r2 = getElementPropertyChange(one, another, "name");
    expect(r1.id).not.toBe(r2.id);
  });
});

describe("compareChains", () => {
  test("should return empty array when both chains are identical", () => {
    const element = makeElement({ id: "e1" });
    const connection = makeConnection("c1", "e1", "e1");
    const chain = makeChain({
      elements: [element],
      dependencies: [connection],
    });
    expect(compareChains(chain, chain)).toEqual([]);
  });

  test("should include changes of all three kinds when chains differ in properties, elements, and connections", () => {
    const one = makeChain({
      id: "c1",
      description: "desc-1",
      elements: [makeElement({ id: "e1", type: "script" })],
      dependencies: [makeConnection("c1", "e1", "e1")],
    });
    const another = makeChain({
      id: "c2",
      description: "desc-2",
      elements: [makeElement({ id: "e2", type: "trigger" })],
      dependencies: [],
    });
    const result = compareChains(one, another);
    const kinds = new Set(result.map((c) => c.kind));
    expect(kinds).toContain("chain-property");
    expect(kinds).toContain("element");
    expect(kinds).toContain("connection");
  });

  test("should return only property changes when elements and connections are identical", () => {
    const element = makeElement({ id: "e1" });
    const one = makeChain({
      id: "c1",
      description: "old",
      elements: [element],
    });
    const another = makeChain({
      id: "c2",
      description: "new",
      elements: [element],
    });
    const result = compareChains(one, another);
    expect(result.length).toBeGreaterThan(0);
    expect(result.every((c) => c.kind === "chain-property")).toBe(true);
  });

  test("should return changes sorted alphabetically by kind", () => {
    const one = makeChain({
      id: "c1",
      description: "desc-1",
      elements: [makeElement({ id: "e1", type: "script" })],
      dependencies: [makeConnection("conn1", "e1", "e1")],
    });
    const another = makeChain({
      id: "c2",
      description: "desc-2",
      elements: [makeElement({ id: "e2", type: "trigger" })],
      dependencies: [],
    });
    const result = compareChains(one, another);
    const kinds = result.map((c) => c.kind);
    expect(kinds).toEqual([...kinds].sort((a, b) => a.localeCompare(b)));
  });
});
