import {
  Element,
  ElementColorType,
  LibraryElement,
} from "../../src/api/apiTypes";
import {
  ChainGraphNode,
  ChainGraphNodeData,
} from "../../src/components/graph/nodes/ChainGraphNodeTypes";
import type { Edge, Node } from "@xyflow/react";

const mockedGetLibraryElementByType = jest.fn();

jest.mock("../../src/api/api.ts", () => ({
  api: {
    getLibraryElementByType: (...args: unknown[]) =>
      mockedGetLibraryElementByType(...args) as unknown,
  },
}));

import {
  applyHighlight,
  buildGraphNodes,
  collectChildren,
  collectSubgraphByParents,
  computeNestedUnitCounts,
  depthOf,
  edgesForSubgraph,
  expandWithParent,
  findUpdatedElement,
  getContainerIdsForEdges,
  getDataFromElement,
  getEffectiveParentId,
  getElementColor,
  getFakeNode,
  getIntersectionParent,
  getLeastCommonParent,
  getLibraryElement,
  getNodeFromElement,
  getParentChain,
  getPossibleGraphIntersection,
  isSwimlanesOnly,
  mergeWithPinnedPositions,
  nonEmptyContainerExists,
  normalizeHandleId,
  sanitizeEdge,
  sortParentsBeforeChildren,
} from "../../src/misc/chain-graph-utils";

function makeElement(overrides: Partial<Element> = {}): Element {
  return {
    id: "e1",
    name: "Element 1",
    description: "desc",
    type: "script",
    chainId: "chain-1",
    properties: {} as never,
    mandatoryChecksPassed: true,
    ...overrides,
  };
}

function makeLibraryElement(
  overrides: Partial<LibraryElement> = {},
): LibraryElement {
  return {
    name: "script",
    title: "Script",
    description: "",
    inputEnabled: true,
    outputEnabled: true,
    deprecated: false,
    parentRestriction: [],
    allowedChildren: {},
    container: false,
    ...overrides,
  } as LibraryElement;
}

function makeNode(overrides: Partial<ChainGraphNode> = {}): ChainGraphNode {
  return {
    id: "n1",
    type: "unit",
    position: { x: 0, y: 0 },
    data: {
      elementType: "script",
      label: "",
      description: "",
      properties: {} as ChainGraphNodeData["properties"],
    },
    ...overrides,
  } as ChainGraphNode;
}

describe("getDataFromElement", () => {
  test("maps element fields to node data without library element", () => {
    const element = makeElement({
      name: "My",
      description: "d",
      type: "script",
      mandatoryChecksPassed: false,
    });
    const data = getDataFromElement(element);

    expect(data).toEqual({
      elementType: "script",
      label: "My",
      description: "d",
      properties: {},
      mandatoryChecksPassed: false,
    });
  });

  test("merges library element fields when provided", () => {
    const element = makeElement();
    const library = makeLibraryElement({
      title: "Script Title",
      inputEnabled: false,
      outputEnabled: true,
      deprecated: true,
    });
    const data = getDataFromElement(element, library);

    expect(data.typeTitle).toBe("Script Title");
    expect(data.inputEnabled).toBe(false);
    expect(data.outputEnabled).toBe(true);
    expect(data.deprecated).toBe(true);
  });
});

describe("getLibraryElement", () => {
  test("returns matching library element by type", () => {
    const lib = [
      makeLibraryElement({ name: "script" }),
      makeLibraryElement({ name: "container" }),
    ];
    const result = getLibraryElement(makeElement({ type: "script" }), lib);
    expect(result.name).toBe("script");
  });

  test("returns default when not found", () => {
    const result = getLibraryElement(makeElement({ type: "unknown" }), [
      makeLibraryElement({ name: "other" }),
    ]);
    expect(result.name).toBe("default");
  });

  test("returns default when library list is null", () => {
    const result = getLibraryElement(makeElement(), null);
    expect(result.name).toBe("default");
  });
});

describe("getEffectiveParentId", () => {
  test("returns parentElementId when set", () => {
    expect(
      getEffectiveParentId({ parentElementId: "p1", swimlaneId: "s1" }),
    ).toBe("p1");
  });

  test("falls back to swimlaneId when parentElementId missing", () => {
    expect(getEffectiveParentId({ swimlaneId: "s1" })).toBe("s1");
  });

  test("returns undefined when neither is set", () => {
    expect(getEffectiveParentId({})).toBeUndefined();
  });

  test("returns undefined for undefined element", () => {
    expect(getEffectiveParentId(undefined)).toBeUndefined();
  });
});

describe("getElementColor", () => {
  test("returns default yellow when library element missing", () => {
    expect(getElementColor(undefined)).toBe("#fdf39d");
  });

  test.each([
    [ElementColorType.SENDER, "#bddcf2"],
    [ElementColorType.TRIGGER, "#a5e1d2"],
    [ElementColorType.CHAIN_CALL, "#cfc3ef"],
    [ElementColorType.COMPOSITE_TRIGGER, "#c9e1a5"],
    [ElementColorType.UNSUPPORTED, "#b8b8b8"],
  ])("returns color for %s", (colorType, expected) => {
    expect(getElementColor(makeLibraryElement({ colorType }))).toBe(expected);
  });

  test("returns default yellow for unknown colorType", () => {
    expect(
      getElementColor(
        makeLibraryElement({ colorType: "x" as ElementColorType }),
      ),
    ).toBe("#fdf39d");
  });
});

describe("getNodeFromElement", () => {
  test("builds unit node with position when no parent", () => {
    const element = makeElement({ id: "e1" });
    const node = getNodeFromElement(element, makeLibraryElement(), "DOWN", {
      x: 10,
      y: 20,
    });
    expect(node.id).toBe("e1");
    expect(node.type).toBe("unit");
    expect(node.position).toEqual({ x: 10, y: 20 });
    expect(node.parentId).toBeUndefined();
  });

  test("forces position to origin when element has parent", () => {
    const element = makeElement({ parentElementId: "p1" });
    const node = getNodeFromElement(element, makeLibraryElement(), "DOWN", {
      x: 10,
      y: 20,
    });
    expect(node.position).toEqual({ x: 0, y: 0 });
    expect(node.parentId).toBe("p1");
  });

  test("uses swimlaneId as parentId when parentElementId missing", () => {
    const element = makeElement({ swimlaneId: "s1" });
    const node = getNodeFromElement(element, makeLibraryElement());
    expect(node.parentId).toBe("s1");
  });

  test("classifies container node type", () => {
    const element = makeElement({ type: "container" });
    const node = getNodeFromElement(
      element,
      makeLibraryElement({ container: true }),
    );
    expect(node.type).toBe("container");
  });

  test("classifies swimlane node type and makes it non-draggable", () => {
    const node = getNodeFromElement(
      makeElement({ type: "swimlane" }),
      makeLibraryElement({ name: "swimlane" }),
    );
    expect(node.type).toBe("swimlane");
    expect(node.draggable).toBe(false);
  });

  test("makes node non-draggable when library has parentRestriction", () => {
    const node = getNodeFromElement(
      makeElement(),
      makeLibraryElement({ parentRestriction: ["container"] }),
    );
    expect(node.draggable).toBe(false);
  });

  test("uses bigger default size for container with children", () => {
    const element = makeElement({
      type: "container",
      children: [makeElement({ id: "child" })],
    });
    const node = getNodeFromElement(
      element,
      makeLibraryElement({ container: true }),
    );
    expect(node.width).toBe(300);
    expect(node.height).toBe(300);
  });
});

describe("buildGraphNodes", () => {
  test("flattens element tree into nodes", () => {
    const tree: Element[] = [
      makeElement({
        id: "root",
        type: "container",
        children: [
          makeElement({ id: "c1", parentElementId: "root" }),
          makeElement({ id: "c2", parentElementId: "root" }),
        ],
      }),
    ];
    const nodes = buildGraphNodes(tree, [
      makeLibraryElement({ name: "container", container: true }),
      makeLibraryElement({ name: "script" }),
    ]);
    expect(nodes.map((node) => node.id)).toEqual(["root", "c1", "c2"]);
  });

  test("handles empty list", () => {
    expect(buildGraphNodes([], [])).toEqual([]);
  });
});

describe("collectChildren", () => {
  test("returns all descendants of parent", () => {
    const nodes: ChainGraphNode[] = [
      makeNode({ id: "p" }),
      makeNode({ id: "a", parentId: "p" }),
      makeNode({ id: "b", parentId: "a" }),
      makeNode({ id: "other" }),
    ];
    expect(collectChildren("p", nodes).map((n) => n.id)).toEqual(["a", "b"]);
  });

  test("returns empty when no children", () => {
    expect(collectChildren("missing", [makeNode()])).toEqual([]);
  });
});

describe("getPossibleGraphIntersection", () => {
  test("picks smallest container/swimlane intersection, filtering dragged children", () => {
    const big = {
      id: "big",
      type: "container",
      width: 300,
      height: 300,
    } as Node;
    const small = {
      id: "small",
      type: "swimlane",
      width: 100,
      height: 100,
    } as Node;
    const unit = { id: "u", type: "unit", width: 50, height: 50 } as Node;
    const dragged = {
      id: "dragged",
      type: "container",
      width: 10,
      height: 10,
    } as Node;

    const res = getPossibleGraphIntersection(
      [big, small, unit, dragged],
      [dragged],
    );
    expect(res?.id).toBe("small");
  });

  test("returns undefined when no valid intersections", () => {
    expect(
      getPossibleGraphIntersection([
        { id: "u", type: "unit", width: 1, height: 1 } as Node,
      ]),
    ).toBeUndefined();
  });
});

describe("getIntersectionParent", () => {
  const dragged = {
    id: "d",
    data: { elementType: "script" },
  } as unknown as Node;

  test("returns parent when library element allows any children", () => {
    const parent = {
      id: "p",
      data: { elementType: "customContainer" },
    } as unknown as Node;
    const lib = [
      makeLibraryElement({ name: "customContainer", allowedChildren: {} }),
    ];
    expect(getIntersectionParent(dragged, parent, lib)?.id).toBe("p");
  });

  test("returns parent when dragged type is in allowedChildren", () => {
    const parent = {
      id: "p",
      data: { elementType: "customContainer" },
    } as unknown as Node;
    const lib = [
      makeLibraryElement({
        name: "customContainer",
        allowedChildren: { script: "one" as never },
      }),
    ];
    expect(getIntersectionParent(dragged, parent, lib)?.id).toBe("p");
  });

  test("returns undefined when dragged type not allowed", () => {
    const parent = {
      id: "p",
      data: { elementType: "customContainer" },
    } as unknown as Node;
    const lib = [
      makeLibraryElement({
        name: "customContainer",
        allowedChildren: { other: "one" as never },
      }),
    ];
    expect(getIntersectionParent(dragged, parent, lib)).toBeUndefined();
  });

  test("returns parent for plain container element without descriptor", () => {
    const parent = {
      id: "p",
      data: { elementType: "container" },
    } as unknown as Node;
    expect(getIntersectionParent(dragged, parent, [])?.id).toBe("p");
  });
});

describe("findUpdatedElement", () => {
  test("finds root-level element", () => {
    const elements = [makeElement({ id: "a" }), makeElement({ id: "b" })];
    expect(findUpdatedElement(elements, "b")?.id).toBe("b");
  });

  test("finds element among children", () => {
    const elements = [
      makeElement({
        id: "root",
        children: [makeElement({ id: "child" })],
      }),
    ];
    expect(findUpdatedElement(elements, "child")?.id).toBe("child");
  });

  test("returns undefined when updatedElements is undefined", () => {
    expect(findUpdatedElement(undefined, "x")).toBeUndefined();
  });

  test("returns undefined when id not present", () => {
    expect(findUpdatedElement([makeElement({ id: "a" })], "z")).toBeUndefined();
  });
});

describe("getFakeNode", () => {
  test("builds fake node at given position", () => {
    const node = getFakeNode({ x: 5, y: 6 });
    expect(node.id).toBe("fake");
    expect(node.position).toEqual({ x: 5, y: 6 });
    expect(node.width).toBe(1);
    expect(node.height).toBe(1);
  });
});

describe("applyHighlight", () => {
  test("highlights matching node and clears others", () => {
    const nodes = [makeNode({ id: "a" }), makeNode({ id: "b" })];
    const result = applyHighlight(nodes, "a");
    expect(result[0].className).toBe("highlight");
    expect(result[1].className).toBe("");
  });

  test("clears all highlights when id undefined", () => {
    const nodes = [makeNode({ id: "a", className: "highlight" })];
    expect(applyHighlight(nodes)[0].className).toBe("");
  });
});

describe("computeNestedUnitCounts", () => {
  test("counts only non-container descendants for container nodes", () => {
    const nodes: ChainGraphNode[] = [
      makeNode({ id: "c", type: "container" }),
      makeNode({ id: "u1", type: "unit", parentId: "c" }),
      makeNode({ id: "inner", type: "container", parentId: "c" }),
      makeNode({ id: "u2", type: "unit", parentId: "inner" }),
    ];
    const counts = computeNestedUnitCounts(nodes);
    expect(counts.get("c")).toBe(2);
    expect(counts.get("inner")).toBe(1);
  });

  test("returns empty map when no containers", () => {
    const nodes = [makeNode({ id: "a", type: "unit" })];
    expect(computeNestedUnitCounts(nodes).size).toBe(0);
  });
});

describe("collectSubgraphByParents + edgesForSubgraph", () => {
  const nodes: ChainGraphNode[] = [
    makeNode({ id: "p1" }),
    makeNode({ id: "c1", parentId: "p1" }),
    makeNode({ id: "p2" }),
    makeNode({ id: "orphan" }),
  ];

  test("collects parent and its descendants", () => {
    const result = collectSubgraphByParents(["p1"], nodes);
    expect(result.map((n) => n.id).sort()).toEqual(["c1", "p1"]);
  });

  test("skips parents that don't exist", () => {
    expect(collectSubgraphByParents(["missing"], nodes)).toEqual([]);
  });

  test("edgesForSubgraph keeps only edges within the subset", () => {
    const edges: Edge[] = [
      { id: "e1", source: "p1", target: "c1" },
      { id: "e2", source: "c1", target: "orphan" },
      { id: "e3", source: "p2", target: "orphan" },
    ];
    const subset = [makeNode({ id: "p1" }), makeNode({ id: "c1" })];
    expect(edgesForSubgraph(edges, subset).map((e) => e.id)).toEqual(["e1"]);
  });
});

describe("sortParentsBeforeChildren", () => {
  test("sorts deeper nodes after their ancestors", () => {
    const input = [
      { id: "deep", parentId: "mid" },
      { id: "mid", parentId: "root" },
      { id: "root" },
    ];
    const result = sortParentsBeforeChildren(input);
    expect(result.map((n) => n.id)).toEqual(["root", "mid", "deep"]);
  });

  test("preserves relative order at same depth", () => {
    const input = [{ id: "a" }, { id: "b" }, { id: "c" }];
    expect(sortParentsBeforeChildren(input).map((n) => n.id)).toEqual([
      "a",
      "b",
      "c",
    ]);
  });
});

describe("isSwimlanesOnly", () => {
  test("true when only swimlane nodes at root", () => {
    const nodes = [
      makeNode({ id: "s1", type: "swimlane" }),
      makeNode({ id: "s2", type: "swimlane" }),
    ];
    expect(isSwimlanesOnly(nodes)).toBe(true);
  });

  test("false when a root node is not swimlane", () => {
    const nodes = [
      makeNode({ id: "s1", type: "swimlane" }),
      makeNode({ id: "u1", type: "unit" }),
    ];
    expect(isSwimlanesOnly(nodes)).toBe(false);
  });

  test("ignores children (only root-level matters)", () => {
    const nodes = [
      makeNode({ id: "s1", type: "swimlane" }),
      makeNode({ id: "u1", type: "unit", parentId: "s1" }),
    ];
    expect(isSwimlanesOnly(nodes)).toBe(true);
  });
});

describe("nonEmptyContainerExists", () => {
  beforeEach(() => {
    mockedGetLibraryElementByType.mockReset();
  });

  test("false for empty list", async () => {
    await expect(nonEmptyContainerExists([])).resolves.toBe(false);
  });

  test("false when no nodes have children", async () => {
    const nodes = [makeNode({ id: "a", type: "unit" })];
    await expect(nonEmptyContainerExists(nodes)).resolves.toBe(false);
  });

  test("true when descriptor has no allowedChildren and has kids", async () => {
    mockedGetLibraryElementByType.mockResolvedValue(
      makeLibraryElement({ name: "c", allowedChildren: {} }),
    );
    const nodes: ChainGraphNode[] = [
      makeNode({ id: "c", type: "container" }),
      makeNode({ id: "u", type: "unit", parentId: "c" }),
    ];
    await expect(nonEmptyContainerExists(nodes)).resolves.toBe(true);
  });

  test("skips descriptor lookup for type 'container'", async () => {
    const nodes: ChainGraphNode[] = [
      makeNode({
        id: "c",
        type: "container",
        data: {
          elementType: "container",
          label: "",
          description: "",
          properties: {} as ChainGraphNodeData["properties"],
        },
      }),
      makeNode({ id: "u", type: "unit", parentId: "c" }),
    ];
    await expect(nonEmptyContainerExists(nodes)).resolves.toBe(true);
    expect(mockedGetLibraryElementByType).not.toHaveBeenCalled();
  });
});

describe("getContainerIdsForEdges", () => {
  test("collects unique parentIds of nodes touched by edges", () => {
    const nodes: ChainGraphNode[] = [
      makeNode({ id: "a", parentId: "p1" }),
      makeNode({ id: "b", parentId: "p2" }),
      makeNode({ id: "c" }),
    ];
    const edges: Edge[] = [
      { id: "e1", source: "a", target: "b" },
      { id: "e2", source: "c", target: "a" },
    ];
    expect(getContainerIdsForEdges(edges, nodes).sort()).toEqual(["p1", "p2"]);
  });

  test("returns empty for edges without parented endpoints", () => {
    const nodes = [makeNode({ id: "a" })];
    const edges: Edge[] = [{ id: "e1", source: "a", target: "missing" }];
    expect(getContainerIdsForEdges(edges, nodes)).toEqual([]);
  });
});

describe("getParentChain + getLeastCommonParent", () => {
  const nodes: ChainGraphNode[] = [
    makeNode({ id: "root" }),
    makeNode({ id: "a", parentId: "root" }),
    makeNode({ id: "b", parentId: "root" }),
    makeNode({ id: "a1", parentId: "a" }),
  ];

  test("getParentChain walks up the parents", () => {
    expect(getParentChain("a1", nodes)).toEqual(["a1", "a", "root"]);
  });

  test("getParentChain returns [] when id undefined", () => {
    expect(getParentChain(undefined, nodes)).toEqual([]);
  });

  test("getLeastCommonParent finds shared ancestor", () => {
    expect(getLeastCommonParent("a1", "b", nodes)).toBe("root");
  });

  test("getLeastCommonParent returns same id when both equal", () => {
    expect(getLeastCommonParent("a", "a", nodes)).toBe("a");
  });

  test("getLeastCommonParent returns undefined when one input missing", () => {
    expect(getLeastCommonParent(undefined, "a", nodes)).toBeUndefined();
  });

  test("getLeastCommonParent returns undefined when no common ancestor", () => {
    const isolated: ChainGraphNode[] = [
      makeNode({ id: "x" }),
      makeNode({ id: "y" }),
    ];
    expect(getLeastCommonParent("x", "y", isolated)).toBeUndefined();
  });
});

describe("expandWithParent", () => {
  test("walks up adding each ancestor", () => {
    const nodes: ChainGraphNode[] = [
      makeNode({ id: "root" }),
      makeNode({ id: "a", parentId: "root" }),
      makeNode({ id: "a1", parentId: "a" }),
    ];
    expect(expandWithParent(["a1"], nodes).sort()).toEqual(["a", "a1", "root"]);
  });

  test("returns the input when nodes have no parents", () => {
    const nodes = [makeNode({ id: "x" })];
    expect(expandWithParent(["x"], nodes)).toEqual(["x"]);
  });
});

describe("mergeWithPinnedPositions", () => {
  test("merges laid positions but keeps pinned original position", () => {
    const base: ChainGraphNode[] = [
      makeNode({ id: "a", position: { x: 1, y: 1 } }),
      makeNode({ id: "b", position: { x: 2, y: 2 } }),
    ];
    const laid: ChainGraphNode[] = [
      makeNode({ id: "a", position: { x: 100, y: 100 } }),
      makeNode({ id: "b", position: { x: 200, y: 200 } }),
    ];
    const out = mergeWithPinnedPositions(base, laid, new Set(["a"]));
    expect(out[0].position).toEqual({ x: 1, y: 1 });
    expect(out[1].position).toEqual({ x: 200, y: 200 });
  });

  test("leaves base nodes untouched when not in laidSubset", () => {
    const base: ChainGraphNode[] = [
      makeNode({ id: "a", position: { x: 1, y: 1 } }),
    ];
    const out = mergeWithPinnedPositions(base, [], new Set());
    expect(out[0]).toBe(base[0]);
  });
});

describe("depthOf", () => {
  test("counts parent chain length", () => {
    const map = new Map<string, ChainGraphNode>([
      ["root", makeNode({ id: "root" })],
      ["a", makeNode({ id: "a", parentId: "root" })],
      ["a1", makeNode({ id: "a1", parentId: "a" })],
    ]);
    expect(depthOf("root", map)).toBe(0);
    expect(depthOf("a", map)).toBe(1);
    expect(depthOf("a1", map)).toBe(2);
  });

  test("returns 0 for missing id", () => {
    expect(depthOf("missing", new Map())).toBe(0);
  });
});

describe("normalizeHandleId", () => {
  test.each([
    [undefined, undefined],
    [null, undefined],
    ["", undefined],
    ["null", undefined],
    ["undefined", undefined],
    [123, undefined],
    ["real-handle", "real-handle"],
  ])("normalizes %p to %p", (input, expected) => {
    expect(normalizeHandleId(input)).toBe(expected);
  });
});

describe("sanitizeEdge", () => {
  test("strips invalid source/target handles", () => {
    const input = {
      id: "e",
      source: "a",
      target: "b",
      sourceHandle: "null",
      targetHandle: "undefined",
    } as unknown as Edge;
    const out = sanitizeEdge(input) as Edge & {
      sourceHandle?: string;
      targetHandle?: string;
    };
    expect(out.sourceHandle).toBeUndefined();
    expect(out.targetHandle).toBeUndefined();
    expect(out.source).toBe("a");
    expect(out.target).toBe("b");
  });

  test("preserves valid handle ids", () => {
    const input = {
      id: "e",
      source: "a",
      target: "b",
      sourceHandle: "out-1",
      targetHandle: "in-2",
    } as unknown as Edge;
    const out = sanitizeEdge(input) as Edge & {
      sourceHandle?: string;
      targetHandle?: string;
    };
    expect(out.sourceHandle).toBe("out-1");
    expect(out.targetHandle).toBe("in-2");
  });
});
