import { Edge } from "@xyflow/react";
import type { ChainGraphNode } from "../../src/components/graph/nodes/ChainGraphNodeTypes";
import {
  SWIMLANE_GAP,
  SWIMLANE_PADDING,
  DEFAULT_NODE_WIDTH,
  DEFAULT_NODE_HEIGHT,
  isHorizontalDirection,
  isDefaultSwimlane,
  ensureDefaultSwimlaneAtTop,
  arrangeSwimlaneChildren,
} from "../../src/misc/chain-graph-swimlane-utils";

function makeNode(overrides: Partial<ChainGraphNode> = {}): ChainGraphNode {
  return {
    id: "n1",
    type: "unit",
    position: { x: 0, y: 0 },
    width: DEFAULT_NODE_WIDTH,
    height: DEFAULT_NODE_HEIGHT,
    data: {
      elementType: "script",
      label: "",
      description: "",
      properties: {},
    },
    ...overrides,
  } as ChainGraphNode;
}

function makeSwimlane(
  overrides: Partial<ChainGraphNode> = {},
): ChainGraphNode {
  return makeNode({
    id: "swimlane-1",
    type: "swimlane",
    draggable: false,
    width: DEFAULT_NODE_WIDTH,
    height: DEFAULT_NODE_HEIGHT,
    ...overrides,
  });
}

function makeEdge(overrides: Partial<Edge> = {}): Edge {
  return { id: "e1", source: "a", target: "b", ...overrides };
}

describe("isHorizontalDirection", () => {
  it("should return true for RIGHT", () => {
    expect(isHorizontalDirection("RIGHT")).toBe(true);
  });

  it("should return false for DOWN", () => {
    expect(isHorizontalDirection("DOWN")).toBe(false);
  });

  it("should return false for undefined", () => {
    expect(isHorizontalDirection(undefined)).toBe(false);
  });
});

describe("isDefaultSwimlane", () => {
  it("should return true when node is a swimlane with matching id", () => {
    const node = makeSwimlane({ id: "default" });
    expect(isDefaultSwimlane(node, "default")).toBe(true);
  });

  it("should return false when node type is not swimlane", () => {
    const node = makeNode({ id: "default" });
    expect(isDefaultSwimlane(node, "default")).toBe(false);
  });

  it("should return false when id does not match", () => {
    const node = makeSwimlane({ id: "other" });
    expect(isDefaultSwimlane(node, "default")).toBe(false);
  });
});

describe("ensureDefaultSwimlaneAtTop", () => {
  it("should return nodes unchanged when default swimlane is not found", () => {
    const nodes = [makeSwimlane({ id: "s1" })];
    const result = ensureDefaultSwimlaneAtTop(nodes, "RIGHT", "missing");
    expect(result).toEqual(nodes);
  });

  it("should position default at (0,0) and others below with SWIMLANE_GAP in RIGHT direction", () => {
    const defaultSwimlane = makeSwimlane({ id: "default", height: 100 });
    const other = makeSwimlane({ id: "other", height: 150 });
    const nodes = [other, defaultSwimlane];

    const result = ensureDefaultSwimlaneAtTop(nodes, "RIGHT", "default");

    expect(result.find((n) => n.id === "default")?.position).toEqual({
      x: 0,
      y: 0,
    });
    expect(result.find((n) => n.id === "other")?.position).toEqual({
      x: 0,
      y: 100 + SWIMLANE_GAP,
    });
  });

  it("should position default at (0,0) and others to the right with SWIMLANE_GAP in DOWN direction", () => {
    const defaultSwimlane = makeSwimlane({ id: "default", width: 120 });
    const other = makeSwimlane({ id: "other", width: 80 });
    const nodes = [other, defaultSwimlane];

    const result = ensureDefaultSwimlaneAtTop(nodes, "DOWN", "default");

    expect(result.find((n) => n.id === "default")?.position).toEqual({
      x: 0,
      y: 0,
    });
    expect(result.find((n) => n.id === "other")?.position).toEqual({
      x: 120 + SWIMLANE_GAP,
      y: 0,
    });
  });

  it("should leave non-swimlane nodes unchanged", () => {
    const defaultSwimlane = makeSwimlane({ id: "default", height: 100 });
    const unit = makeNode({ id: "unit1" });
    const nodes = [defaultSwimlane, unit];

    const result = ensureDefaultSwimlaneAtTop(nodes, "RIGHT", "default");

    expect(result.find((n) => n.id === "unit1")).toEqual(unit);
    expect(result.find((n) => n.id === "default")?.position).toEqual({
      x: 0,
      y: 0,
    });
  });
});

describe("arrangeSwimlaneChildren", () => {
  it("should return nodes unchanged when defaultSwimlaneId is undefined", () => {
    const nodes = [makeSwimlane({ id: "s1" })];
    const result = arrangeSwimlaneChildren(nodes, [], "RIGHT", undefined);
    expect(result).toEqual(nodes);
  });

  describe("in RIGHT (horizontal) direction", () => {
    // getPos -> x, getSize -> width
    // expandSwimlanesToFitChildren expands width
    // equalizeSwimlaneSizes equalizes width
    // ensureDefaultSwimlaneAtTop uses height for vertical stacking

    it("should expand swimlane width to fit children x-extent plus SWIMLANE_PADDING", () => {
      const swimlane = makeSwimlane({
        id: "s1",
        width: DEFAULT_NODE_WIDTH,
        height: 200,
      });
      // child x-extent = 120 + 50 = 170, required = 170 + 20 = 190 > 150
      const child = makeNode({
        id: "n1",
        parentId: "s1",
        position: { x: 120, y: 0 },
        width: 50,
      });
      const nodes = [swimlane, child];

      const result = arrangeSwimlaneChildren(nodes, [], "RIGHT", "s1");

      expect(result.find((n) => n.id === "s1")?.width).toBe(
        120 + 50 + SWIMLANE_PADDING,
      );
    });

    it("should equalize widths of multiple swimlanes to the largest", () => {
      const s1 = makeSwimlane({ id: "s1", width: 200, height: 100 });
      const s2 = makeSwimlane({ id: "s2", width: 150, height: 100 });
      const nodes = [s1, s2];

      const result = arrangeSwimlaneChildren(nodes, [], "RIGHT", "s1");

      expect(result.find((n) => n.id === "s1")?.width).toBe(200);
      expect(result.find((n) => n.id === "s2")?.width).toBe(200);
    });

    it("should resolve cross-swimlane edge conflicts by shifting target node on the x-axis", () => {
      const s1 = makeSwimlane({ id: "s1", width: 300, height: 200 });
      const s2 = makeSwimlane({ id: "s2", width: 300, height: 200 });
      // sourceEdge = 0 + 50 = 50, required = 50 + 40 = 90
      const nodeA = makeNode({
        id: "a",
        parentId: "s1",
        position: { x: 0, y: 0 },
        width: 50,
      });
      const nodeB = makeNode({
        id: "b",
        parentId: "s2",
        position: { x: 0, y: 0 },
        width: 50,
      });
      const edges = [makeEdge({ source: "a", target: "b" })];
      const nodes = [s1, s2, nodeA, nodeB];

      const result = arrangeSwimlaneChildren(nodes, edges, "RIGHT", "s1");

      expect(result.find((n) => n.id === "b")?.position.x).toBe(
        50 + SWIMLANE_GAP,
      );
    });

    it("should run the full pipeline correctly", () => {
      // s1 (default, width=200, height=100) and s2 (width=150, height=100)
      // a in s1 at x=0, b in s2 at x=0, edge a->b
      // resolve: b.x = 90
      // expand: b extent = 90+50=140, required=160 > s2.width=150 => s2.width=160
      // equalize: max(200,160)=200 => both width=200
      // ensure: s1 at (0,0), s2 at (0, 100+40)
      const s1 = makeSwimlane({ id: "default", width: 200, height: 100 });
      const s2 = makeSwimlane({ id: "other", width: 150, height: 100 });
      const nodeA = makeNode({
        id: "a",
        parentId: "default",
        position: { x: 0, y: 0 },
        width: 50,
      });
      const nodeB = makeNode({
        id: "b",
        parentId: "other",
        position: { x: 0, y: 0 },
        width: 50,
      });
      const edges = [makeEdge({ source: "a", target: "b" })];
      const nodes = [s2, s1, nodeA, nodeB];

      const result = arrangeSwimlaneChildren(nodes, edges, "RIGHT", "default");

      expect(result.find((n) => n.id === "default")?.position).toEqual({
        x: 0,
        y: 0,
      });
      expect(result.find((n) => n.id === "default")?.width).toBe(200);
      expect(result.find((n) => n.id === "other")?.width).toBe(200);
      expect(result.find((n) => n.id === "other")?.position.y).toBe(
        100 + SWIMLANE_GAP,
      );
      expect(result.find((n) => n.id === "b")?.position.x).toBe(
        50 + SWIMLANE_GAP,
      );
    });
  });

  describe("in DOWN (vertical) direction", () => {
    // getPos -> y, getSize -> height
    // expandSwimlanesToFitChildren expands height
    // equalizeSwimlaneSizes equalizes height
    // ensureDefaultSwimlaneAtTop uses width for horizontal stacking

    it("should expand swimlane height to fit children y-extent plus SWIMLANE_PADDING", () => {
      const swimlane = makeSwimlane({
        id: "s1",
        width: 300,
        height: DEFAULT_NODE_HEIGHT,
      });
      // child y-extent = 120 + 50 = 170, required = 170 + 20 = 190 > 50
      const child = makeNode({
        id: "n1",
        parentId: "s1",
        position: { x: 0, y: 120 },
        height: 50,
      });
      const nodes = [swimlane, child];

      const result = arrangeSwimlaneChildren(nodes, [], "DOWN", "s1");

      expect(result.find((n) => n.id === "s1")?.height).toBe(
        120 + 50 + SWIMLANE_PADDING,
      );
    });

    it("should equalize heights of multiple swimlanes to the largest", () => {
      const s1 = makeSwimlane({ id: "s1", width: 100, height: 200 });
      const s2 = makeSwimlane({ id: "s2", width: 100, height: 150 });
      const nodes = [s1, s2];

      const result = arrangeSwimlaneChildren(nodes, [], "DOWN", "s1");

      expect(result.find((n) => n.id === "s1")?.height).toBe(200);
      expect(result.find((n) => n.id === "s2")?.height).toBe(200);
    });

    it("should resolve cross-swimlane edge conflicts by shifting target node on the y-axis", () => {
      const s1 = makeSwimlane({ id: "s1", width: 200, height: 300 });
      const s2 = makeSwimlane({ id: "s2", width: 200, height: 300 });
      const nodeA = makeNode({
        id: "a",
        parentId: "s1",
        position: { x: 0, y: 0 },
        height: 50,
      });
      const nodeB = makeNode({
        id: "b",
        parentId: "s2",
        position: { x: 0, y: 0 },
        height: 50,
      });
      const edges = [makeEdge({ source: "a", target: "b" })];
      const nodes = [s1, s2, nodeA, nodeB];

      const result = arrangeSwimlaneChildren(nodes, edges, "DOWN", "s1");

      expect(result.find((n) => n.id === "b")?.position.y).toBe(
        50 + SWIMLANE_GAP,
      );
    });
  });

  it("should preserve root-level non-swimlane nodes through the pipeline", () => {
    const swimlane = makeSwimlane({ id: "s1", width: 200, height: 100 });
    const unit = makeNode({ id: "rootUnit", position: { x: 10, y: 10 } });
    const child = makeNode({
      id: "child1",
      parentId: "s1",
      position: { x: 0, y: 0 },
    });
    const nodes = [swimlane, unit, child];

    const result = arrangeSwimlaneChildren(nodes, [], "RIGHT", "s1");

    expect(result.find((n) => n.id === "rootUnit")?.position).toEqual({
      x: 10,
      y: 10,
    });
  });
});
